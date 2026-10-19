import Foundation
import AVFoundation

/// Audio coming in from outside: a shared or picked file, a Prime Minutes session file, a podcast episode. Each
/// lands in the audio directory and is queued (a session file is restored to the library instead).
@MainActor final class Imports: ObservableObject {
    private let screen: MeetingScreen
    private let library: SessionLibrary
    private let runner: JobRunner
    init(screen: MeetingScreen, library: SessionLibrary, runner: JobRunner) { self.screen = screen; self.library = library; self.runner = runner }

    /// Podcast episodes (Android ImportDownloads): picking one creates its queue item FIRST, carrying the episode's
    /// title and a `download`; the row and the item's screen show the download (percent and bytes, paused, failed with
    /// its reason). Downloads run one at a time in pick order; a done one becomes an ordinary queued job, processed
    /// like any import. A pause (network lost, app suspended) or a kill keeps the item, and it resumes from its `.part`.
    /// The job downloading now, its bytes so far and in all (total -1 when the server does not say), and whether it
    /// waits for the network to come back.
    @Published var dlId: UUID?
    @Published var dlBytes: (Int64, Int64)?
    @Published var dlPaused = false
    private var worker: Task<Void, Never>?
    private var itemTask: Task<Void, Error>?

    func addEpisode(_ ep: Episode) {
        var j = Job(audio: UUID().uuidString + "." + Podcast.ext(ep.audioUrl), title: ep.title)
        j.captureStart = -1   // a podcast episode: no meeting time to show
        j.download = JobDownload(url: ep.audioUrl)
        StatusLog.add("podcast item \(j.id): \(ep.title)")
        Task { await runner.queue.add(j); await runner.syncQueue(); kick() }
    }
    /// At launch: items whose download a kill cut carry on (the pre-item UserDefaults record is dropped).
    func restorePending() { UserDefaults.standard.removeObject(forKey: "pendingEpisode"); kick() }
    /// Resume now (the paused item's button); it also resumes by itself when the app is back in front and online.
    func resumeDownload() { DownloadResumer.shared.kick() }
    /// A failed item downloads again (from its `.part`).
    func retry(_ j: Job) {
        guard var d = j.download else { return }
        d.error = nil
        Task { var n = j; n.download = d; await runner.queue.update(n); await runner.syncQueue(); kick() }
    }
    /// Delete a not-yet-downloaded item: its download stops and its partial file goes with it.
    func remove(_ j: Job) {
        Task {
            await runner.queue.remove(j.id); await runner.syncQueue()   // first: the worker then sees it gone
            if dlId == j.id { itemTask?.cancel() }
            Self.dropFiles(j)
        }
    }
    /// DEV probes: delete the item downloading now.
    func removeCurrent() { if let id = dlId { Task { if let j = await runner.queue.job(id) { remove(j) } } } }
    nonisolated static func dropFiles(_ j: Job) {
        if let d = j.download { try? FileManager.default.removeItem(at: Podcast.partURL(d.url)) }
        try? FileManager.default.removeItem(at: JobQueue.url(j))
    }
    func kick() {
        if worker != nil { DownloadResumer.shared.kick(); return }
        worker = Task { await work(); worker = nil }
    }
    private func work() async {
        while let j = await runner.queue.all.first(where: { $0.download != nil && $0.download?.error == nil }), let d = j.download {
            dlId = j.id; dlBytes = nil; dlPaused = false
            let ep = Episode(title: j.title ?? "", audioUrl: d.url, published: "", duration: "")
            let t = Task<Void, Error> {
                // Paused until bytes flow again: the resumer reports the end of a pause only when the attempt ends.
                try await DownloadResumer.shared.run("podcast", onPause: { on in self.dlPaused = on }) {
                    _ = try await Podcast.download(ep, name: j.audio) { got, total in Task { @MainActor in if self.dlId == j.id { self.dlBytes = (got, total); self.dlPaused = false }  } }
                }
            }
            itemTask = t
            let r = await t.result
            itemTask = nil; dlId = nil; dlBytes = nil; dlPaused = false
            guard var now = await runner.queue.job(j.id) else { Self.dropFiles(j); continue }   // deleted meanwhile
            switch r {
            case .success:
                now.download = nil
                StatusLog.add("podcast item \(j.id) downloaded")
                await runner.queue.update(now); await runner.syncQueue(); runner.drain()
            case .failure(let e):
                // A cancel without a delete (cannot happen today) leaves it to resume at the next launch.
                if e is CancellationError { await runner.syncQueue(); return }
                now.download?.error = (e as NSError).localizedDescription
                StatusLog.add("podcast item \(j.id) failed: \(e)")
                await runner.queue.update(now); await runner.syncQueue()
            }
        }
    }

    func importAudio(_ url: URL) {
        let j = Job(audio: UUID().uuidString + "." + (url.pathExtension.isEmpty ? "m4a" : url.pathExtension))
        let before = screen.status; screen.status = L("status_importing")   // Android: a long share (big file, cloud provider) shows progress
        Task {
            let ok = await Task.detached {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return (try? FileManager.default.copyItem(at: url, to: JobQueue.url(j))) != nil
            }.value
            if screen.status == L("status_importing") { screen.status = before }
            guard ok else { screen.status = L("import_failed"); return }
            imported(url, j)
        }
    }
    private func imported(_ url: URL, _ j: Job) {
        if let man = SessionFile.read(JobQueue.url(j)) {   // a Prime Minutes session: restore it instead of re-transcribing
            var s = SessionFile.session(man, audio: j.audio)
            if s.title.isEmpty { s.title = url.deletingPathExtension().lastPathComponent }
            if let t = AVURLAsset(url: JobQueue.url(j)).duration.seconds as Double?, t.isFinite, t > s.seconds { s.seconds = t }
            library.update(s); return
        }
        // When the meeting took place: the file's own creation date, never the import time (none: the time is left out).
        var j = j; j.captureStart = MediaDate.captureStart(JobQueue.url(j))?.timeIntervalSince1970 ?? -1
        Task { await runner.queue.add(j); runner.drain() }
    }
}
