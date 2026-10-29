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
    /// The download waits for disk space (not a network pause, not a failure): retried every minute and on Resume.
    @Published var dlNoSpace = false
    private var worker: Task<Void, Never>?
    private var itemTask: Task<Void, Error>?

    /// The channel an episode belongs to, snapshotted at import (LibrarySources): the library groups and draws it
    /// offline, and it survives an unsubscribe.
    struct Channel { var title = "", artist = "", artworkUrl = "", feedUrl = "" }
    @discardableResult func addEpisode(_ ep: Episode, seriesId: String? = nil, guid: String? = nil, channel: Channel? = nil, published: Int64? = nil, durationSec: Int? = nil) -> UUID {
        var j = Job(audio: UUID().uuidString + "." + Podcast.ext(ep.audioUrl), title: ep.title)
        j.captureStart = -1   // a podcast episode: no meeting time to show
        j.download = JobDownload(url: ep.audioUrl)
        StatusLog.add("podcast item \(j.id): \(ep.title)")
        // Provenance (outlives the job: the session keeps the job's id), so a subscribed episode is known as imported.
        let pub = published ?? { let p = FeedParser.parseDate(ep.published); return p > 0 ? p : nil }()
        let dur = durationSec ?? { let d = FeedParser.parseDuration(ep.duration); return d > 0 ? d : nil }()
        EpisodeSources.add(.init(jobId: j.id.uuidString, sourceUrl: ep.audioUrl, guid: guid, seriesId: seriesId, kind: SourceKind.podcast.rawValue,
                                 channelTitle: channel?.title, channelArtist: channel?.artist, artworkUrl: channel?.artworkUrl.isEmpty == false ? channel?.artworkUrl : nil,
                                 episodeTitle: ep.title, published: pub, durationSec: dur, feedUrl: channel?.feedUrl.isEmpty == false ? channel?.feedUrl : nil))
        if let a = channel?.artworkUrl, !a.isEmpty { ArtworkCache.shared.prefetch(seriesId: seriesId, url: a) }
        Task { await runner.queue.add(j); await runner.syncQueue(); kick() }
        return j.id
    }
    /// At launch: items whose download a kill cut carry on (the pre-item UserDefaults record is dropped).
    func restorePending() {
        UserDefaults.standard.removeObject(forKey: "pendingEpisode"); kick()
        Task { let live = Set((await runner.queue.all).compactMap { $0.download.map { Podcast.partURL($0.url).lastPathComponent } }); Self.sweepStaleParts(keep: live) }
    }
    /// Podcast `.part` files older than 24 h that no queued item downloads (Android sweepStaleParts). Only the
    /// audio directory's `podcast_*.part`: model parts belong to the background downloader.
    nonisolated static func sweepStaleParts(keep: Set<String>, maxAge: TimeInterval = 24 * 3600) {
        let fm = FileManager.default
        for f in (try? fm.contentsOfDirectory(at: JobQueue.audioDir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        where f.lastPathComponent.hasPrefix("podcast_") && f.pathExtension == "part" && !keep.contains(f.lastPathComponent) {
            let m = (try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
            if Date().timeIntervalSince(m) > maxAge { try? fm.removeItem(at: f); StatusLog.add("swept stale part \(f.lastPathComponent)") }
        }
    }
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
            // A PeerTube video queued by an older version: PeerTube import was removed (0.55). It fails for good with
            // the reason on its row (Delete removes it); nothing is fetched.
            if VideoLinks.isPeerTubeFile(d.url) {
                var now = j; now.download?.error = L("import_video_unsupported")
                StatusLog.add("podcast item \(j.id) failed: PeerTube import removed")
                await runner.queue.update(now); await runner.syncQueue(); continue
            }
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
                // DEV (library check on a shared phone): keep the downloaded episode unprocessed, no model run.
                if Dev.env["VOX_UNQUEUE_DL"] != nil { runner.unqueue(now); continue }
                await runner.queue.update(now); await runner.syncQueue(); runner.drain()
            case .failure(let e):
                // A cancel without a delete (cannot happen today) leaves it to resume at the next launch.
                if e is CancellationError { await runner.syncQueue(); return }
                if SubscriptionLogic.isNoSpace(e) {
                    // Disk full: wait for space (the .part stays), then try again; never "failed".
                    StatusLog.add("podcast item \(j.id) waits for space: \(e)")
                    dlId = j.id; dlNoSpace = true; dlPaused = true
                    await DownloadResumer.shared.waitKick(seconds: 60)
                    dlNoSpace = false; dlPaused = false; dlId = nil
                    continue
                }
                now.download?.error = (e as NSError).localizedDescription
                StatusLog.add("podcast item \(j.id) failed: \(e)")
                await runner.queue.update(now); await runner.syncQueue()
            }
        }
    }

    // MARK: received files (AirDrop, Files, Open in…; docs: SessionFileCore "sharing")
    /// A received meeting already in the library (same session id): the user picks Open existing / Keep a copy.
    struct Duplicate: Identifiable { let id = UUID(); let incoming: Session; let existing: Session }
    @Published var duplicate: Duplicate?
    /// A one-off message for an alert (a file from a newer app).
    @Published var notice: String?
    /// A meeting to show now (a restored one, or the existing one of a duplicate).
    @Published var openRequest: Session?

    /// A file handed to the app. A Prime Minutes meeting restores at once, models or not; plain audio (or a
    /// meeting from a newer app, imported as audio after a notice) goes through `gate`, the model download consent.
    /// Files opens a document in place (Info.plist LSSupportsOpeningDocumentsInPlace): the URL is the user's own file,
    /// security-scoped, maybe not yet downloaded (iCloud Drive), so it is read off the main thread under a coordinated read.
    func open(_ url: URL, gate: @escaping (@escaping () -> Void) -> Void) {
        StatusLog.add("import: open \(Self.isInbox(url) ? "inbox" : "in place") \(url.path)")
        Task {
            let man = await Task.detached { Self.reading(url) { SessionFile.read($0) } ?? nil }.value
            if let man, SessionFile.supported(man) { importAudio(url); return }
            if man != nil { notice = L("share_too_new"); StatusLog.add("import: newer manifest \(man?.primeminutes_version ?? 0), as audio") }
            gate { self.importAudio(url, plain: true) }
        }
    }

    /// `body` on a readable `url`: inside its security scope (a file opened in place, a picked file) and a coordinated
    /// read (a cloud file is downloaded first). nil when the file cannot be read.
    nonisolated static func reading<T>(_ url: URL, _ body: (URL) -> T) -> T? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var out: T?, err: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &err) { out = body($0) }
        return out
    }

    /// AirDrop and Open in… hand over a copy in Documents/Inbox: ours is in the audio directory once copied.
    nonisolated static func isInbox(_ url: URL) -> Bool {
        let inbox = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Inbox").standardizedFileURL.path
        return url.standardizedFileURL.path.hasPrefix(inbox + "/")
    }
    /// Inbox copies left behind (an import declined at the download prompt, a kill): removed after an hour.
    nonisolated static func sweepInbox(maxAge: TimeInterval = 3600) {
        let fm = FileManager.default
        let inbox = fm.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Inbox")
        for f in (try? fm.contentsOfDirectory(at: inbox, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            let m = (try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
            if Date().timeIntervalSince(m) > maxAge { try? fm.removeItem(at: f) }
        }
    }

    /// Duplicate answered: open the meeting already here (the received copy goes), or keep the received one too.
    func resolveDuplicate(keepCopy: Bool) {
        guard let d = duplicate else { return }
        duplicate = nil
        if keepCopy { var s = d.incoming; s.id = UUID(); library.update(s); openRequest = s; return }
        if let a = d.incoming.audio { try? FileManager.default.removeItem(at: JobQueue.audioDir.appendingPathComponent(a)) }
        openRequest = d.existing
    }

    func importAudio(_ url: URL, plain: Bool = false) {
        let j = Job(audio: UUID().uuidString + "." + SessionFile.storedExtension(url.pathExtension))
        let before = screen.status; screen.status = L("status_importing")   // Android: a long share (big file, cloud provider) shows progress
        Task {
            let ok = await Task.detached {
                let ok = Self.reading(url) { (try? FileManager.default.copyItem(at: $0, to: JobQueue.url(j))) != nil } ?? false
                if ok, Self.isInbox(url) { try? FileManager.default.removeItem(at: url) }
                return ok
            }.value
            if screen.status == L("status_importing") { screen.status = before }
            guard ok else { screen.status = L("import_failed"); return }
            imported(url, j, plain: plain)
        }
    }
    private func imported(_ url: URL, _ j: Job, plain: Bool) {
        if !plain, let man0 = SessionFile.read(JobQueue.url(j)), SessionFile.supported(man0) {   // a Prime Minutes meeting: restore it, no model needed
            // The receiver's script (span-gated OpenCC: text already in it is left alone).
            let sc = AppLanguage.current.script, gen = OpenCC.generated(sc), tr = OpenCC.transcript(sc)
            let man = SessionFile.converted(man0, generated: gen.convert, transcript: tr.convert)
            var s = SessionFile.session(man, audio: j.audio)
            if s.title.isEmpty { s.title = url.deletingPathExtension().lastPathComponent }
            if let t = AVURLAsset(url: JobQueue.url(j)).duration.seconds as Double?, t.isFinite, t > s.seconds { s.seconds = t }
            StatusLog.add("import: meeting \(s.id) v\(man0.primeminutes_version ?? 1), \(s.lines.count) lines")
            if man0.session_id != nil, let old = library.sessions.first(where: { $0.id == s.id }) { duplicate = Duplicate(incoming: s, existing: old); if Dev.env["VOX_DUPCOPY"] != nil { resolveDuplicate(keepCopy: true) }; return }
            library.update(s); openRequest = s; return
        }
        // When the meeting took place: the file's own creation date, never the import time (none: the time is left out).
        var j = j; j.captureStart = MediaDate.captureStart(JobQueue.url(j))?.timeIntervalSince1970 ?? -1
        Task { await runner.queue.add(j); runner.drain() }
    }
}
