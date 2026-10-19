import AVFoundation

/// Any audio file the system can read (m4a, mp3, wav, caf, aac…) → 16 kHz mono float chunks of ~1 s,
/// streamed so an hour-long recording never sits in memory. `onChunk` returns false to stop early.
enum AudioDecode {
    static func duration(_ url: URL) -> Double? {
        guard let f = try? AVAudioFile(forReading: url) else { return nil }
        return Double(f.length) / f.processingFormat.sampleRate
    }

    static func stream(_ url: URL, from: Double = 0, onChunk: ([Float]) -> Bool) throws {
        let file = try AVAudioFile(forReading: url)
        let inFmt = file.processingFormat
        if from > 0 { file.framePosition = min(file.length, AVAudioFramePosition(from * inFmt.sampleRate)) }   // resume: decode only after the seam
        let out = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false)!
        guard let conv = AVAudioConverter(from: inFmt, to: out) else { throw CocoaError(.fileReadUnsupportedScheme) }
        let inCap: AVAudioFrameCount = 16384
        while file.framePosition < file.length {
            guard let inBuf = AVAudioPCMBuffer(pcmFormat: inFmt, frameCapacity: inCap) else { break }
            // A bad packet mid-file (seen at 2422 s of a 45 min mp3: avfaudio 560164718) cannot be skipped by AVAudioFile:
            // keep what was decoded instead of discarding the whole recording.
            do { try file.read(into: inBuf, frameCount: inCap) } catch {
                if file.framePosition == 0 { throw error }
                StatusLog.add("trace audio decode stopped at \(Int(Double(file.framePosition) / inFmt.sampleRate)) s: \(error)")
                return
            }
            if inBuf.frameLength == 0 { break }
            let cap = AVAudioFrameCount(Double(inBuf.frameLength) * 16000 / inFmt.sampleRate) + 32
            guard let o = AVAudioPCMBuffer(pcmFormat: out, frameCapacity: cap) else { break }
            try conv.convert(inBuf, to: o, then: .noDataNow)
            if let p = o.floatChannelData?[0], o.frameLength > 0,
               !onChunk(Array(UnsafeBufferPointer(start: p, count: Int(o.frameLength)))) { return }
        }
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
