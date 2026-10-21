import AVFoundation
import AudioToolbox

/// Any audio file the system can read (m4a, mp3, wav, caf, aac…) → 16 kHz mono float chunks of ~1 s,
/// streamed so an hour-long recording never sits in memory. `onChunk` returns false to stop early.
///
/// The stream is anchored to the *player* timeline — sample 0 is the first sample AVAudioPlayer plays
/// (SessionView's cursor, `currentTime`), which is what a tapped transcript line seeks to. A raw AVAudioFile
/// read starts at the container's decode zero, which for compressed files begins *before* that, inside the
/// encoding priming the player skips: mp3 encoder delay (576 samples; 1152 for LAME's full priming, 48000 for
/// the flagged full-prime variant) and AAC pre-fill (the m4a edit list skips 1024 priming + 1024 filler).
/// Times read off the raw decode are early on the player by that amount — 1152 samples at the 24 kHz of
/// voxsum.mp3 is 48 ms, 2048 at 44.1 kHz an hour on, and the drift is constant, so start, middle and end
/// are all off by the same offset. `priming` measures it per file and every read starts after it.
enum AudioDecode {
    /// Seconds from AVAudioFile's decode zero to the player's t = 0: 0 for wav/pcm and files with no priming.
    static func priming(_ url: URL) -> Double {
        let key = url.path as NSString
        if let v = primeCache.object(forKey: key) { return v.doubleValue }
        let v = measurePriming(url)
        primeCache.setObject(NSNumber(value: v), forKey: key)
        return v
    }

    /// Playable seconds, on the player timeline (what AVAudioPlayer.duration shows).
    static func duration(_ url: URL) -> Double? {
        guard let f = try? AVAudioFile(forReading: url) else { return nil }
        let prime = AVAudioFramePosition((priming(url) * f.processingFormat.sampleRate).rounded())
        return Double(max(0, f.length - prime)) / f.processingFormat.sampleRate
    }

    /// `from` is a player time (seconds), matching the transcript's line times.
    static func stream(_ url: URL, from: Double = 0, onChunk: ([Float]) -> Bool) throws {
        let file = try AVAudioFile(forReading: url)
        let inFmt = file.processingFormat
        let prime = Int((priming(url) * inFmt.sampleRate).rounded())
        let at = AVAudioFramePosition(Double(prime) + from * inFmt.sampleRate)
        if at > 0 { file.framePosition = min(file.length, at) }   // resume: decode only after the seam
        let out = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false)!
        guard let conv = AVAudioConverter(from: inFmt, to: out) else { throw CocoaError(.fileReadUnsupportedScheme) }
        let inCap: AVAudioFrameCount = 16384
        while file.framePosition < file.length {
            guard let inBuf = AVAudioPCMBuffer(pcmFormat: inFmt, frameCapacity: inCap) else { break }
            // A bad packet mid-file (seen at 2422 s of a 45 min mp3: avfaudio 560164718) cannot be skipped by AVAudioFile:
            // keep what was decoded instead of discarding the whole recording.
            do { try file.read(into: inBuf, frameCount: inCap) } catch {
                if file.framePosition == 0 { throw error }
                StatusLog.add("trace audio decode stopped at \(Int(Double(file.framePosition - AVAudioFramePosition(prime)) / inFmt.sampleRate)) s: \(error)")
                return
            }
            if inBuf.frameLength == 0 { break }
            #if os(macOS)   // the host conversion API requires capacity of at least the input length (decode is 16 kHz, so overallocating is fine)
            let cap = inBuf.frameLength + 32
            #else
            let cap = AVAudioFrameCount(Double(inBuf.frameLength) * 16000 / inFmt.sampleRate) + 32
            #endif
            guard let o = AVAudioPCMBuffer(pcmFormat: out, frameCapacity: cap) else { break }
            #if os(macOS)   // host tests build this file on macOS, where the same call carries the old labels
            try conv.convert(to: o, from: inBuf)
            #else
            try conv.convert(inBuf, to: o, then: .noDataNow)
            #endif
            if let p = o.floatChannelData?[0], o.frameLength > 0,
               !onChunk(Array(UnsafeBufferPointer(start: p, count: Int(o.frameLength)))) { return }
        }
    }

    // MARK: - priming measurement

    private static let primeCache = NSCache<NSString, NSNumber>()

    /// CoreMedia's own definition of what a player plays: the track's preferred timing starts exactly where
    /// AVAudioPlayer starts (mp3 prime scope, m4a edit list) — and for wav/pcm it starts at 0. Where CoreMedia
    /// says nothing, the file's magic cookies (below). Runs off the main thread (decode is streamed on a worker).
    private static func measurePriming(_ url: URL) -> Double {
        let asset = AVURLAsset(url: url)
        var measured: Double = -1
        let sem = DispatchSemaphore(value: 0)
        Task.detached(priority: .userInitiated) {
            defer { sem.signal() }
            #if os(iOS)
            guard let track = try? await asset.load(.tracks).first(where: { $0.mediaType == .audio }),
                  let first = try? await track.load(.preferredTimings)?.first else { return }
            let s = CMTimeGetSeconds(first.timeRange.start)
            if s.isFinite, s >= 0, s < 2 { measured = s }   // priming is never a full second of audio
            #endif
        }
        sem.wait()
        return measured >= 0 ? measured : cookiePriming(url)
    }

    /// Fallback when CoreMedia exposes no preferred timing. iOS: the file's magic cookies — the mp3 prime scope
    /// ('prim': 0 = 576, 1 = 1152, 2 = 48000, 3 = 1152 samples) and the AAC pre-fill ('aacf': the priming +
    /// filler frame lengths, 1024 + 1024 in the m4a files this app sees). macOS host tests: the playable duration
    /// is the decoded length minus the edit, so their difference is the priming.
    private static func cookiePriming(_ url: URL) -> Double {
        #if os(macOS)
        guard let f = try? AVAudioFile(forReading: url) else { return 0 }
        let p = Double(f.length) / f.processingFormat.sampleRate - CMTimeGetSeconds(AVURLAsset(url: url).duration)
        return (p > 0 && p < 2) ? p : 0
        #else
        var af: AudioFileID?
        guard AudioFileOpenURL(url as CFURL, .read, 0, &af) == noErr, let af else { return 0 }
        var size: UInt32 = 0
        guard AudioFileGetPropertyInfo(af, kAudioFileMagicCookieKey, &size, nil) == noErr, size >= 8, size < 65_536 else { return 0 }
        var data = Data(count: size)
        guard data.withUnsafeMutableBytes({ AudioFileGetProperty(af, kAudioFileMagicCookieKey, &size, $0.baseAddress!) }) == noErr else { return 0 }
        var rate: Double = 0
        var desc = AudioStreamBasicDescription()
        var dsz = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        withUnsafeMutablePointer(to: &desc) {
            if AudioFileGetProperty(af, kAudioFilePropertyDataFormat, &dsz, $0) == noErr { rate = desc.mSampleRate }
        }
        guard rate > 0 else { return 0 }
        return parseCookies(data, rate: rate)
        #endif
    }

    /// The cookie list: entries of u32 big-endian type, u32 size, payload padded to 4 bytes.
    /// 'prim' (kAudioFileMP3PrimeScopeCookie): scope 0 = 576, 1 and 3 = 1152, 2 = 48000 samples of encoder priming.
    /// 'aacf' (kAudioFileAACAEPCMagicCookie): u16 frame count, then a u16 sample count per frame — the priming +
    /// filler frames the player skips, 1024 + 1024 in the m4a files this app sees.
    static func parseCookies(_ data: Data, rate: Double) -> Double {
        func be16(_ d: Data, _ i: Int) -> Int { Int(d[i]) << 8 | Int(d[i + 1]) }
        func be32(_ d: Data, _ i: Int) -> Int { be16(d, i) << 16 | be16(d, i + 2) }
        var i = 0
        let size = data.count
        while i + 8 <= size {
            let type = be32(data, i), len = be32(data, i + 4), at = i + 8
            guard len >= 0, at + len <= size else { break }
            switch type {
            case 0x7072696D:   // 'prim'
                if len >= 4 {
                    switch be32(data, at) {
                    case 0: return 576 / rate
                    case 1, 3: return 1152 / rate
                    case 2: return 48_000 / rate
                    default: break
                    }
                }
            case 0x61616366:   // 'aacf'
                let n = be16(data, at)
                guard n > 0, n <= 8, at + 2 + 2 * n <= size else { break }
                var samples = 0
                for k in 0..<n { samples += be16(data, at + 2 + 2 * k) }
                return Double(samples) / rate
            default: break
            }
            i = at + (len + 3) & ~3
        }
        return 0
    }
}

/// An imported file's creation date from its own metadata (Android MediaDate): the meeting's start for the "Email
/// participants" time line. Never the file's modified time or the import time; nil when the file has no date, or an
/// implausible one (an MP4 creation_time left at zero, a date in the future).
enum MediaDate {
    static func captureStart(_ url: URL, now: Date = Date()) -> Date? {
        plausible(AVURLAsset(url: url).creationDate?.dateValue, now: now)
    }
    static func plausible(_ d: Date?, now: Date) -> Date? {
        guard let d, d.timeIntervalSince1970 >= 946_684_800, d <= now.addingTimeInterval(86_400) else { return nil }
        return d
    }
}
