import Foundation
import AVFoundation

/// Audio coming in from outside: a shared or picked file, a Prime Minutes session file, a podcast episode. Each
/// lands in the audio directory and is queued (a session file is restored to the library instead).
@MainActor final class Imports: ObservableObject {
    private let screen: MeetingScreen
    private let library: SessionLibrary
    private let runner: JobRunner
    init(screen: MeetingScreen, library: SessionLibrary, runner: JobRunner) { self.screen = screen; self.library = library; self.runner = runner }

    /// Podcast episode: download into the audio directory, then queue it like any import.
    @Published var downloading: String?
    /// Import download banner (Android ImportDownloadBanner): fraction done, nil while unknown.
    @Published var downloadFraction: Double?
    /// Bytes so far and in all (total -1 when the server does not say).
    @Published var downloadBytes: (Int64, Int64)?
    /// Interrupted (the app was suspended, the network dropped): waiting to resume by itself, or on Resume.
    @Published var downloadPaused = false
    private var downloadTask: Task<Void, Never>?
    /// The episode being downloaded, kept until it is queued or cancelled: a download cut by a kill or a relaunch
    /// comes back paused and resumes (from its `.part`) instead of being lost.
    private struct Pending: Codable { let episode: Episode; let job: Job }
    private static let pendingKey = "pendingEpisode"
    private var pending: Pending? {
        get { UserDefaults.standard.data(forKey: Self.pendingKey).flatMap { try? JSONDecoder().decode(Pending.self, from: $0) } }
        set { if let v = newValue, let d = try? JSONEncoder().encode(v) { UserDefaults.standard.set(d, forKey: Self.pendingKey) } else { UserDefaults.standard.removeObject(forKey: Self.pendingKey) } }
    }
    /// Cancel (the user's only way to drop it) keeps the `.part`, so the same episode resumes if picked again.
    func cancelDownload() { pending = nil; downloadTask?.cancel(); DownloadResumer.shared.kick() }
    /// Resume now (the paused card's button); it also resumes by itself when the app is back in front and online.
    func resumeDownload() { DownloadResumer.shared.kick() }
    func addEpisode(_ ep: Episode) {
        guard downloading == nil else { screen.status = L("import_busy"); return }   // the sheet is already gone: say why nothing starts (as addEpisode does)
        let j = Job(audio: UUID().uuidString + "." + Podcast.ext(ep.audioUrl), title: ep.title)
        pending = Pending(episode: ep, job: j)
        startDownload(ep, j)
    }
    /// At launch: an episode download the app did not finish (killed, relaunched) carries on.
    func restorePending() {
        guard downloading == nil, let p = pending else { return }
        StatusLog.add("podcast download restored: \(p.episode.title)")
        startDownload(p.episode, p.job)
    }
    private func startDownload(_ ep: Episode, _ j: Job) {
        downloading = ep.title; downloadFraction = nil; downloadBytes = nil; downloadPaused = false; screen.status = L("podcast_downloading", 0)
        downloadTask = Task {
            defer { downloading = nil; downloadFraction = nil; downloadBytes = nil; downloadPaused = false; downloadTask = nil }
            do {
                try await DownloadResumer.shared.run("podcast", onPause: { on in
                    self.downloadPaused = on
                    if on { self.screen.status = L("download_paused") }
                }) {
                    _ = try await Podcast.download(ep, name: j.audio) { got, total in Task { @MainActor in
                        self.downloadBytes = (got, total)
                        if total > 0 { let f = Double(got) / Double(total); self.downloadFraction = f; self.screen.status = L("podcast_downloading", Int(f * 100)) }
                    } }
                }
                pending = nil
                await runner.queue.add(j); runner.drain()
            } catch {
                // Cancel, or a permanent error (gone from the server, too large): only these end it.
                if !Task.isCancelled { pending = nil }
                screen.status = Task.isCancelled ? L("app_ready") : L("download_failed", error.localizedDescription)
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
        Task { await runner.queue.add(j); runner.drain() }
    }
}
