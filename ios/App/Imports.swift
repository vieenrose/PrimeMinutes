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
    private var downloadTask: Task<Void, Never>?
    /// Cancel keeps the `.part`, so the same episode resumes where it stopped.
    func cancelDownload() { downloadTask?.cancel() }
    func addEpisode(_ ep: Episode) {
        guard downloading == nil else { screen.status = L("import_busy"); return }   // the sheet is already gone: say why nothing starts (as addEpisode does)
        let j = Job(audio: UUID().uuidString + "." + Podcast.ext(ep.audioUrl), title: ep.title)
        downloading = ep.title; downloadFraction = nil; screen.status = L("podcast_downloading", 0)
        downloadTask = Task {
            defer { downloading = nil; downloadFraction = nil; downloadTask = nil }
            do {
                _ = try await Podcast.download(ep, name: j.audio) { f in Task { @MainActor in self.downloadFraction = f; self.screen.status = L("podcast_downloading", Int(f * 100)) } }
                await runner.queue.add(j); runner.drain()
            } catch { screen.status = Task.isCancelled ? L("app_ready") : L("download_failed", error.localizedDescription) }
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
