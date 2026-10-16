import Foundation

/// The saved meetings as the UI lists them, over the on-disk `LibraryStore`; every change also asks the search
/// indexer to refresh that meeting's index.
@MainActor final class SessionLibrary: ObservableObject {
    @Published var sessions: [Session] = []
    let store = LibraryStore()
    /// Read to keep an audio file a queued job still needs.
    private let queue: JobQueue
    init(queue: JobQueue) { self.queue = queue }

    func all() async -> [Session] { await store.all() }
    func refresh() async { sessions = await store.all() }
    func update(_ s: Session) { Task { try? await store.save(s); sessions = await store.all(); LibraryIndexer.shared.request(s) } }
    func remove(_ s: Session) { Task {
        await store.delete(s.id); LibraryIndexer.shared.forget(s.id); sessions = await store.all()
        // Android deletes the whole entry: the audio goes too, unless another session or a queued job still uses it
        if let a = s.audio, !sessions.contains(where: { $0.audio == a }), !(await queue.all).contains(where: { $0.audio == a }) { try? FileManager.default.removeItem(at: JobQueue.audioDir.appendingPathComponent(a)) }
    } }
}
