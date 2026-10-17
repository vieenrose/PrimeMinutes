import Foundation

/// The voice lane's generation (Android VoiceStore.generation): bumped, under its lock, when recognition is turned off
/// or its data deleted. A meeting's vectors computed off the main actor are stored only if no bump happened since the
/// computation started, so "Delete all voice data" cannot be undone by a meeting the lane was still working on.
final class VoiceGeneration: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    var current: Int { lock.lock(); defer { lock.unlock() }; return value }

    /// Bumps the generation, running `body` (the deletion) under the same lock so no store interleaves with it.
    func bump(_ body: () -> Void = {}) {
        lock.lock(); defer { lock.unlock() }
        value += 1
        body()
    }

    /// Runs `write` for a computation started at `gen` unless a bump happened since. True when it was written.
    @discardableResult func store(_ gen: Int, _ write: () throws -> Void) rethrows -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard gen == value else { return false }
        try write()
        return true
    }
}
