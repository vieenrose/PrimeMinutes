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

/// The title a re-run (re-transcribe, re-summarize, a recording taken off the queue and processed later) carries
/// into its job, where it wins over the reader's. Only a real title does: the placeholders a session gets when no
/// reader named it ("Meeting" for an unprocessed recording, "No speech", the transcript's first words) are dropped,
/// so the run's generated title replaces them (Android keeps no title for such an entry: its date shows instead).
enum RerunTitle {
    static func kept(_ title: String, placeholders: [String], firstLine: String?) -> String? {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || placeholders.contains(t) { return nil }
        if let f = firstLine, t == String(f.prefix(20)) { return nil }
        return t
    }
}
