import AVFoundation
import Foundation

enum StatusLog { static func add(_ s: String) { FileHandle.standardError.write((s + "\n").data(using: .utf8)!) } }

// Decode/playback alignment (ios/tests/run.sh, macOS host). The offset itself lives in iOS's AVAudioFile
// (its decode zero sits inside the container priming that AVAudioPlayer skips); macOS AVAudioFile applies the
// edit itself, so on the host the checks are: the plumbing (the stream starts where the player starts, times
// match AVAudioPlayer) and the cookie parser (synthetic 'prim'/'aacf' lists, the iOS constants).
@main struct PrimingTests {
    static func main() {
        parserChecks()
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("vox-priming-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let rate = 16000.0, secs = 12.0, bursts: [Double] = [1.0, 6.0, 11.0]
        let wav = writeWav(dir.appendingPathComponent("t.wav"), rate: rate, secs: secs, bursts: bursts)
        check(wav, bursts, "wav")
        let m4a = writeM4a(wav, dir.appendingPathComponent("t.m4a"))
        check(m4a, bursts, "m4a")
        print("priming tests ok")
    }

    /// The iOS cookie constants, checked against synthetic lists.
    static func parserChecks() {
        func be(_ v: UInt32) -> [UInt8] {
            [UInt8((v >> 24) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 8) & 0xff), UInt8(v & 0xff)]
        }
        func cookie(_ type: UInt32, _ payload: [UInt8]) -> [UInt8] {
            var d = be(type) + be(UInt32(payload.count))
            d += payload
            while d.count % 4 > 0 { d.append(0) }
            return d
        }
        func prim(_ scope: UInt32) -> [UInt8] { cookie(0x7072696D, be(scope)) }
        func aacf(_ lens: [UInt16]) -> [UInt8] {
            var p = be(UInt32(lens.count))
            for l in lens { p += [UInt8(l >> 8), UInt8(l & 0xff)] }
            return cookie(0x61616366, p)
        }
        let r = 44100.0
        precondition(AudioDecode.parseCookies(Data(prim(0)), rate: r) == 576 / r, "prim0")
        precondition(AudioDecode.parseCookies(Data(prim(1)), rate: r) == 1152 / r, "prim1")
        precondition(AudioDecode.parseCookies(Data(prim(2)), rate: r) == 48_000 / r, "prim2")
        precondition(AudioDecode.parseCookies(Data(prim(3)), rate: r) == 1152 / r, "prim3")
        precondition(AudioDecode.parseCookies(Data(aacf([1024, 1024])), rate: r) == 2048 / r, "aacf 1024+1024")
        precondition(AudioDecode.parseCookies(Data(aacf([1024])), rate: 16000) == 1024 / 16000, "aacf 1024")
        precondition(AudioDecode.parseCookies(Data(aacf([1024, 1024, 1024])), rate: r) == 3072 / r, "aacf 3 frames")
        precondition(AudioDecode.parseCookies(Data(cookie(0x64657363, [1, 2, 3]) + prim(1)), rate: r) == 1152 / r, "prim after a padded filler cookie")
        precondition(AudioDecode.parseCookies(Data(cookie(0x64657363, [1, 2, 3, 4])), rate: r) == 0, "no priming cookies")
        precondition(AudioDecode.parseCookies(Data(), rate: r) == 0, "empty")
        print("cookie parser ok")
    }

    static func writeWav(_ url: URL, rate: Double, secs: Double, bursts: [Double]) -> URL {
        let n = Int(rate * secs)
        var samples = [Double](repeating: 0, count: n)
        for i in 0..<n {
            let t = Double(i) / rate
            for b in bursts where t >= b && t < b + 0.05 {
                samples[i] = 0.8 * sin(2.0 * Double.pi * 1000.0 * (t - b))
            }
        }
        let fmt = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
        let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(n))!
        buf.frameLength = AVAudioFrameCount(n)
        let ch = buf.floatChannelData![0]
        for i in 0..<n { ch[i] = Float(samples[i]) }
        let out = try! AVAudioFile(forWriting: url, settings: [
            AVSampleRateKey: rate, AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
            AVNumberOfChannelsKey: 1,
        ])
        try! out.write(from: buf)
        return url
    }

    /// The app's own export path (SessionFile): a real m4a carrying the AAC priming.
    static func writeM4a(_ wav: URL, _ url: URL) -> URL {
        let ex = AVAssetExportSession(asset: AVURLAsset(url: wav), presetName: AVAssetExportPresetAppleM4A)!
        ex.outputURL = url; ex.outputFileType = .m4a
        let sem = DispatchSemaphore(value: 0)
        ex.exportAsynchronously { sem.signal() }
        sem.wait()
        guard ex.status == .completed else { fatalError("m4a export: \(ex.error?.localizedDescription ?? "?")") }
        return url
    }

    /// The player timeline is what the stream feeds: onsets must land on the burst times (within 15 ms),
    /// `duration` must match AVAudioPlayer, and priming must be in range for the container.
    static func check(_ url: URL, _ bursts: [Double], _ kind: String) {
        let pr = AudioDecode.priming(url)
        precondition(pr >= 0 && pr < 0.2, "\(kind): priming \(pr)")
        let dur = AudioDecode.duration(url) ?? -1
        let player = (try! AVAudioPlayer(contentsOf: url)).duration
        precondition(abs(dur - player) < 0.15, "\(kind): duration \(dur) vs player \(player)")
        let a = Onsets()
        try! AudioDecode.stream(url) { c in a.feed(c); return true }
        precondition(a.res.count == bursts.count, "\(kind): onsets \(a.res)")
        let off = zip(a.res, bursts).map { ($0 - $1) * 1000 }
        precondition(off.allSatisfy { abs($0) < 15 }, "\(kind): onsets off by \(off)")
        // resume: player time 5 s lands on the 6 s burst
        let b = Onsets()
        try! AudioDecode.stream(url, from: 5.0) { c in b.feed(c); return true }
        precondition(b.res.first.map { abs($0 - 1.0) < 0.015 } == true, "\(kind): resume onset \(b.res)")
        print(String(format: "%@ priming=%.1f ms, onsets within %.1f ms, duration matches player", kind, pr * 1000, off.map(abs).max() ?? -1))
    }

    /// Burst onsets: 10 ms blocks with RMS > 0.08 that follow at least 8 silent blocks — one onset per burst.
    final class Onsets {
        var res: [Double] = []
        private var block: [Float] = [], blockStart: Double = 0, quiet = 99
        func feed(_ c: [Float]) {
            for x in c {
                block.append(x)
                if block.count == 160 {
                    var s: Float = 0; for x in block { s += x * x }
                    let loud = sqrt(s / 160) > 0.08
                    if loud, quiet >= 8 { res.append(blockStart) }
                    quiet = loud ? 0 : quiet + 1
                    blockStart += 0.01
                    block.removeAll(keepingCapacity: true)
                }
            }
        }
    }
}
