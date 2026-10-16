import AVFoundation

/// Microphone → 16 kHz mono float chunks (what `NemoEngine.push` takes). Started and stopped from the main actor or
/// the record-only task, read on the audio tap / file feed thread: the state those threads share is behind `lock`.
final class Recorder: @unchecked Sendable {
    private let lock = NSLock()
    private let engine = AVAudioEngine()
    private var _converter: AVAudioConverter?
    private var converter: AVAudioConverter? { get { lock.withLock { _converter } } set { lock.withLock { _converter = newValue } } }
    private let out = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false)!

    static func requestPermission() async -> Bool {
        if Dev.env["VOX_MICFILE"] != nil { return true }   // the file stands in for the mic
        return await withCheckedContinuation { c in AVAudioApplication.requestRecordPermission { c.resume(returning: $0) } }
    }

    private var _feeding = false
    private var feeding: Bool { get { lock.withLock { _feeding } } set { lock.withLock { _feeding = newValue } } }

    func start(_ onChunk: @escaping @Sendable ([Float]) -> Void) throws {
        // VOX_MICFILE=a.wav[,b.wav…]: each recording plays the next file; at the end of one that has a successor
        // the app moves on with "Next talk", after the last one it stops (demo of consecutive meetings).
        if let fs = Dev.env["VOX_MICFILE"]?.split(separator: ",").map(String.init), !fs.isEmpty {
            let i = Recorder.nextMic()
            return try feed(fs[min(i, fs.count - 1)], more: i + 1 < fs.count, onChunk)
        }
        let s = AVAudioSession.sharedInstance()
        try s.setCategory(.record, mode: .measurement); try s.setActive(true)
        try tap(onChunk)
        // A route change (AirPods in or out, a USB mic) stops the engine and changes the input format:
        // without a restart the rest of the meeting was recorded as silence.
        configObs = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            guard let self, self.configObs != nil else { return }
            self.engine.inputNode.removeTap(onBus: 0)
            do { try self.tap(onChunk); StatusLog.add("trace mic route changed: restarted at \(self.engine.inputNode.outputFormat(forBus: 0).sampleRate) Hz") }
            catch { StatusLog.add("trace mic restart failed: \(error)") }
        }
    }
    private var _configObs: NSObjectProtocol?
    private var configObs: NSObjectProtocol? { get { lock.withLock { _configObs } } set { lock.withLock { _configObs = newValue } } }

    private func tap(_ onChunk: @escaping @Sendable ([Float]) -> Void) throws {
        let input = engine.inputNode, inFmt = input.outputFormat(forBus: 0)
        converter = AVAudioConverter(from: inFmt, to: out)
        input.installTap(onBus: 0, bufferSize: 4096, format: inFmt) { [self] buf, _ in
            let cap = AVAudioFrameCount(Double(buf.frameLength) * 16000 / inFmt.sampleRate) + 16
            guard let o = AVAudioPCMBuffer(pcmFormat: out, frameCapacity: cap), let conv = converter else { return }
            _ = try? conv.convert(buf, to: o, then: .noDataNow)
            if let p = o.floatChannelData?[0], o.frameLength > 0 { onChunk(Array(UnsafeBufferPointer(start: p, count: Int(o.frameLength)))) }
        }
        engine.prepare(); try engine.start()
    }

    /// DEV demo/test feed: an audio file played into the pipeline in real time as if it were the mic (the
    /// simulator mic is silent); stops the recording itself at the end of the file.
    /// Recordings started so far: the index of the file the next one plays (guarded by `micLock`).
    nonisolated(unsafe) private static var micIndex = 0
    private static let micLock = NSLock()
    private static func nextMic() -> Int { micLock.withLock { defer { micIndex += 1 }; return micIndex } }
    private func feed(_ path: String, more: Bool, _ onChunk: @escaping @Sendable ([Float]) -> Void) throws {
        let file = try AVAudioFile(forReading: URL(fileURLWithPath: path.hasPrefix("/") ? path : NSHomeDirectory() + "/" + path))
        guard let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)),
              let conv = AVAudioConverter(from: file.processingFormat, to: out) else { return }
        try file.read(into: buf)
        let o = AVAudioPCMBuffer(pcmFormat: out, frameCapacity: AVAudioFrameCount(Double(buf.frameLength) * 16000 / file.processingFormat.sampleRate) + 64)!
        _ = try? conv.convert(buf, to: o, then: .endOfStream)
        let all = Array(UnsafeBufferPointer(start: o.floatChannelData![0], count: Int(o.frameLength)))
        feeding = true
        Thread.detachNewThread { [weak self] in
            var i = 0; let n = 1600; let t0 = Date()
            while i < all.count, self?.feeding == true {
                onChunk(Array(all[i..<min(i + n, all.count)])); i += n
                let due = t0.addingTimeInterval(Double(i) / 16000); let d = due.timeIntervalSinceNow
                if d > 0 { Thread.sleep(forTimeInterval: d) }
            }
            if self?.feeding == true { Task { @MainActor in
                guard let m = BackgroundWork.model, m.capture.recording else { return }
                if more { m.capture.nextTalk() } else { m.capture.toggleRecord() }
            } }
        }
    }

    func stop() {
        if feeding { feeding = false; return }
        if let o = configObs { NotificationCenter.default.removeObserver(o); configObs = nil }
        engine.inputNode.removeTap(onBus: 0); engine.stop(); try? AVAudioSession.sharedInstance().setActive(false)
    }
}

/// Android LiveAgc: gentle boost-only gain for the live mic (far speaker → audible to VAD/ASR), applied before the
/// WAV writer and the recognizer so every consumer hears the same signal. Envelope rises instantly, decays ~5 s;
/// gain moves slowly (~2 s) toward targetPeak/envelope, capped at maxGain, and holds during silence.
/// Called from the mic thread (a route change restarts the tap): the state is behind `lock`.
final class LiveAgc: @unchecked Sendable {
    private let targetPeak: Float, maxGain: Float
    private let lock = NSLock()
    private var envelope: Float = 0
    private var gain: Float = 1
    init(targetPeak: Float = 0.35, maxGain: Float = 8) { self.targetPeak = targetPeak; self.maxGain = maxGain }
    @discardableResult func process(_ block: inout [Float]) -> Float {
        lock.lock(); defer { lock.unlock() }
        var pk: Float = 0
        for v in block { let a = abs(v); if a > pk { pk = a } }
        envelope = pk > envelope ? pk : max(envelope * 0.985, pk)
        if envelope > 0.004 { gain += (min(max(targetPeak / envelope, 1), maxGain) - gain) * 0.06 }
        if gain > 1.001 { for i in block.indices { block[i] = min(max(block[i] * gain, -1), 1) } }
        return gain
    }
}

extension AVAudioConverter {
    /// One input buffer through the converter, then `then` (.noDataNow between mic blocks, .endOfStream for a whole
    /// file). The input block runs synchronously inside `convert`, on this thread: the unsafe locals are never shared.
    @discardableResult func convert(_ input: AVAudioPCMBuffer, to out: AVAudioPCMBuffer, then: AVAudioConverterInputStatus) throws -> AVAudioConverterOutputStatus {
        nonisolated(unsafe) var fed = false
        nonisolated(unsafe) let buf = input
        var err: NSError?
        let st = convert(to: out, error: &err) { _, st in
            if fed { st.pointee = then; return nil }; fed = true; st.pointee = .haveData; return buf
        }
        if let err { throw err }
        return st
    }
}
