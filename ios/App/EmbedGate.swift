import Foundation

/// The embedder's one-at-a-time gate, with priority for user queries (Android EmbedGate). A
/// foreground holder (an Ask, a semantic search) is admitted before any waiting background holder,
/// and the indexer, holding it for a whole meeting, calls `checkpoint()` between two chunks: when a
/// query waits, the gate is handed over and taken back after it. A query issued during a catch-up
/// thus waits at most one embed call, not the whole meeting. Blocking: never call it on the main thread.
final class EmbedGate: @unchecked Sendable {
    private let cond = NSCondition()
    private var held = false
    private var fgWaiting = 0
    private(set) var handovers = 0

    func acquire(foreground: Bool) {
        cond.lock(); defer { cond.unlock() }
        if foreground { fgWaiting += 1 }
        while held || (!foreground && fgWaiting > 0) { cond.wait() }
        if foreground { fgWaiting -= 1 }
        held = true
    }

    func release() { cond.lock(); held = false; cond.broadcast(); cond.unlock() }

    var queryWaiting: Bool { cond.lock(); defer { cond.unlock() }; return fgWaiting > 0 }

    /// Hands the gate to waiting queries, then takes it back; true if it yielded.
    @discardableResult func checkpoint() -> Bool {
        guard queryWaiting else { return false }
        cond.lock(); handovers += 1; cond.unlock()
        release(); acquire(foreground: false)
        return true
    }

    func with<T>(foreground: Bool, _ block: () throws -> T) rethrows -> T {
        acquire(foreground: foreground); defer { release() }
        return try block()
    }
}
