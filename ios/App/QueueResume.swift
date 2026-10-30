import Foundation

/// What the queue bar's "Process all" does with the waiting jobs. A job stopped by the user is parked (kept queued,
/// skipped by the drain) until resumed; parked used to live only in memory, so "Process all" counted it but the drain
/// skipped it and the tap did nothing until a relaunch. "Process all" is an explicit action: it unparks the stopped
/// jobs. A failed one keeps its error and its own Retry (never looped on), a downloading one waits for its file.
enum QueueResume {
    /// The jobs "Process all" will run (in queue order) and the parked ones it must release first.
    static func plan(waiting: [(id: UUID, downloading: Bool)], parked: Set<UUID>, failed: Set<UUID>) -> (run: [UUID], unpark: Set<UUID>) {
        let run = waiting.filter { !$0.downloading && !failed.contains($0.id) }.map(\.id)
        return (run, parked.intersection(run))
    }
}
