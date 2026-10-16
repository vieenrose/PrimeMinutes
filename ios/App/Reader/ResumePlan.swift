import Foundation

/// Where a queued job picks up after a kill (Android: the queue skips ASR when `pending_transcript.json`
/// is there). The complete transcript (`Checkpoint.save`, written when ASR ends) wins over the partial
/// one saved every minute during ASR: a job killed while the reader worked runs the reader alone,
/// instead of transcribing again from the last partial seam.
enum ResumePlan<Transcript, Partial> {
    /// ASR had finished: only the reader is left.
    case readerOnly(Transcript)
    /// Stopped or killed during ASR: transcribe after the partial's seam.
    case afterSeam(Partial)
    case fromStart

    static func decide(full: Transcript?, partial: Partial?) -> ResumePlan {
        if let full { return .readerOnly(full) }
        if let partial { return .afterSeam(partial) }
        return .fromStart
    }

    /// The partial to resume from (nil when the transcript is complete or there is none).
    var partial: Partial? { if case .afterSeam(let p) = self { return p }; return nil }
}
