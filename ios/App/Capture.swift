import SwiftUI
import AVFoundation

/// The microphone side: Record / Stop / Next talk, the recording's audio file and job, the elapsed time. With the
/// queue idle and the notes model on the phone the recording is processed live (`JobRunner.process(live:)`);
/// otherwise it is only transcribed for the screen and queued for the full pipeline after Stop.
@MainActor final class CaptureController: ObservableObject {
    private let screen: MeetingScreen
    private let runner: JobRunner
    private let downloads: ModelDownloads
    init(screen: MeetingScreen, runner: JobRunner, downloads: ModelDownloads) { self.screen = screen; self.runner = runner; self.downloads = downloads }

    @Published var recording = false
    @Published var elapsed = 0
    /// Being recorded: not for the queue yet.
    private(set) var recordingJob: UUID?
    private var recorder: Recorder?
    private let buffer = ChunkBuffer()
    private var liveBuffer: ChunkBuffer?

    private var interruptObs: NSObjectProtocol?
    /// Set from the tap until the recorder exists: a second tap during the permission prompt or the
    /// model download started a second recorder and a second job.
    private var startingRecord = false
    func toggleRecord() {
        // A call, Siri or another app taking the mic: save what was recorded (queued like any stop) and say so, instead of a stalled tap.
        if interruptObs == nil { interruptObs = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            guard (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init) == .began else { return }
            Task { @MainActor in guard let self, self.recording else { return }; self.toggleRecord(); self.screen.status = L("rec_interrupted") }
        } }
        if recording { recorder?.stop(); recorder = nil; liveBuffer?.close(); liveBuffer = nil; recording = false; screen.status = L("stopping"); return }
        guard !startingRecord else { return }
        startingRecord = true
        Task {
            defer { startingRecord = false }   // early returns; cleared as soon as the recorder exists
            guard await Recorder.requestPermission() else { screen.status = L("mic_permission_required"); return }
            guard await downloads.downloadSpeech() else { return }
            screen.status = L("loading_models"); screen.clear()
            elapsed = 0
            let r = Recorder(); recorder = r; recording = true; startingRecord = false; UIApplication.shared.isIdleTimerDisabled = true
            let job = Job(audio: UUID().uuidString + ".wav")
            guard let wav = try? WavWriter(JobQueue.url(job)) else { recording = false; return }
            recordingJob = job.id
            await runner.queue.add(job)       // on the list before the first sample: a kill mid-recording keeps the audio
            // Android: the reader takes notes while the meeting is recorded, so the summary is ready moments after Stop. One
            // engine pair at a time: with the queue at work (or the previous talk still being read), record only; it is processed after.
            let readerReady = Dev.env["VOX_READER_DIR"] != nil ? true : await downloads.store.isComplete(Prefs.reader)
            if runner.liveJob == nil && runner.activeJob == nil && readerReady && Dev.env["VOX_LEGACYREC"] == nil { await recordLive(job, wav, r); return }
            Task.detached { [weak self] in
                guard let self else { return }
                guard let eng = ModelDownloads.nemoEngine() else {
                    await MainActor.run { self.screen.status = L("models_missing"); self.recording = false; UIApplication.shared.isIdleTimerDisabled = false }; return }
                let agc = LiveAgc()
                do { try r.start { [buffer = self.buffer] c in var c = c; agc.process(&c); wav.append(c); buffer.add(c) } }
                catch { await MainActor.run { self.screen.status = L("mic_error", "\(error)"); self.recording = false }; return }
                var frozen: [Utterance] = []; let conv = TextConv()
                while await MainActor.run(body: { self.recording }) {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    let chunk = self.buffer.take()
                    if !chunk.isEmpty { _ = eng.push(chunk) }
                    let (fresh, tail) = eng.live(); frozen += fresh   // the engine returns only the newly frozen lines
                    await MainActor.run { [frozen] in self.screen.lines = conv.utterances(frozen + tail); self.elapsed = Int(eng.fedSeconds); self.screen.status = L("recording_s", Int(eng.fedSeconds)) }
                }
                wav.finish()
                await MainActor.run { self.recordingJob = nil }
                await MainActor.run { self.runner.drain() }   // full pipeline (diarization settled + notes) from the saved audio
            }
        }
    }
    /// The recording fed straight into `process` (ASR + diarization + reader live), archived at the end like a queued job.
    private func recordLive(_ job: Job, _ wav: WavWriter, _ r: Recorder) async {
        let buf = ChunkBuffer(), agc = LiveAgc()
        do { try r.start { c in var c = c; agc.process(&c); wav.append(c); buf.add(c) } }
        catch { screen.status = L("mic_error", "\(error)"); recording = false; recordingJob = nil; return }
        liveBuffer = buf; runner.beginLive(job.id)
        let t0 = Date()   // the timer runs on the clock, not on what the recognizer has caught up with (models still loading)
        Task { while self.recording, self.runner.liveJob == job.id { self.elapsed = Int(Date().timeIntervalSince(t0)); try? await Task.sleep(nanoseconds: 500_000_000) } }
        await runner.process(seconds: 0, job: job, live: true) { [weak self] sink in
            while true {
                // Half-second blocks, as the record-only loop: the live diarizer settles speakers on what it is pushed.
                Thread.sleep(forTimeInterval: 0.5)
                let done = buf.closed, c = buf.take()
                if !c.isEmpty, !sink(c) { break }
                if done { break }
            }
            wav.finish()
            Task { @MainActor in self?.recordingJob = nil }   // the audio is complete: Next talk may start the next one
        }
        // The run ended while this talk is still being recorded (models failed to load, or Stop processing): stop the
        // mic, the audio stays queued. After Next talk the mic belongs to the next recording (another Recorder), which
        // must keep going. Not `recordingJob == job.id`: the feed clears it as it returns, so after a Stop the check
        // failed and the mic ran on for good (recording shown, nothing saved past the stop, the live screen frozen).
        if recording, recorder === r { toggleRecord() }
        await runner.settleLive(job.id)
        // After Next talk, recordingJob is the next recording: clearing it would let the queue take that talk while
        // it is still being recorded (processed from its first seconds, the rest of the audio lost).
        if recordingJob == job.id { recordingJob = nil }
        await runner.endLive()
    }
    /// Android "Next talk": save this recording (it is queued like any stop) and start the next one straight away.
    func nextTalk() {
        guard recording else { return }
        toggleRecord()
        Task { while recordingJob != nil || recording { try? await Task.sleep(nanoseconds: 100_000_000) }; toggleRecord() }
    }
}
