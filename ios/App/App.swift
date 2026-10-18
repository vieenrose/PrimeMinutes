import SwiftUI
import AVFoundation
import UserNotifications
import os
import Combine

@main struct PrimeMinutesApp: App {
    @AppStorage("textSize") private var textSize = 0
    @Environment(\.scenePhase) private var phase
    init() { BackgroundWork.register() }
    var body: some Scene {
        WindowGroup { ContentView().modifier(TextSize(steps: Prefs.typeSizeSteps(textSize))) }
            .onChange(of: phase) { if phase == .background { BackgroundWork.schedule() } }
    }
}

/// Dynamic Type steps from Settings, on top of the system size; nil follows the system.
struct TextSize: ViewModifier {
    let steps: Int?
    @Environment(\.dynamicTypeSize) private var system
    @ViewBuilder func body(content: Content) -> some View {
        if let steps, steps != 0 {
            let all = DynamicTypeSize.allCases
            let i = (all.firstIndex(of: system) ?? 3) + steps
            content.dynamicTypeSize(all[min(max(i, 0), all.count - 1)])
        } else { content }
    }
}

/// The app's state, one per process (the UI and the background task share it). It only composes the parts, each
/// owning its own state, and relays their changes so a view observing the model redraws as before.
@MainActor
final class Model: ObservableObject {
    let screen = MeetingScreen()
    let queue = JobQueue()
    let library: SessionLibrary
    let downloads: ModelDownloads
    let runner: JobRunner
    let capture: CaptureController
    let imports: Imports
    /// A citation or a search passage opens this meeting at this time (consumed by the next SessionView).
    var openAt: (UUID, Double)?
    private var relays: [AnyCancellable] = []

    /// Application Support holds the meetings (audio, transcripts, search index, checkpoints) and GBs of
    /// models: none of it may go to iCloud backup ("nothing leaves the device", and App Review rejects
    /// re-downloadable data in backups). The flag on the folder covers everything below it.
    nonisolated static func excludeFromBackup() {
        var u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        var v = URLResourceValues(); v.isExcludedFromBackup = true
        do { try u.setResourceValues(v) } catch { StatusLog.add("backup exclusion failed: \(error)") }
        StatusLog.add("app data excluded from backup: \((try? u.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false)")
    }
    /// One model for the UI and the background task (a background launch builds no view).
    static let shared = Model()
    init() {
        Self.excludeFromBackup()
        library = SessionLibrary(queue: queue)
        downloads = ModelDownloads(screen: screen, library: library)
        runner = JobRunner(queue: queue, screen: screen, library: library, downloads: downloads)
        capture = CaptureController(screen: screen, runner: runner, downloads: downloads)
        imports = Imports(screen: screen, library: library, runner: runner)
        PeopleStore.shared.library = library
        runner.capture = capture
        relays = [screen.objectWillChange, library.objectWillChange, downloads.objectWillChange, runner.objectWillChange, capture.objectWillChange, imports.objectWillChange]
            .map { $0.sink { [weak self] _ in self?.objectWillChange.send() } }
        LibraryIndexer.shared.busy = { [weak self] in self.map { $0.runner.activeJob != nil || $0.capture.recording } ?? true }
        LibraryIndexer.shared.recording = { [weak self] in self?.capture.recording ?? true }
        AskEngine.shared.busy = LibraryIndexer.shared.busy; AskEngine.shared.recording = LibraryIndexer.shared.recording
    }
    func reload() { Task {
        await library.refresh(); await runner.syncQueue()
        // Names given before the people directory existed become its people (once).
        await PeopleStore.shared.migrateIfNeeded()
        if let ops = Dev.env["VOX_PEOPLE_DO"], !Dev.ranPeopleOps { Dev.ranPeopleOps = true; await PeopleStore.shared.devRun(ops) }
        VoiceStore.shared.attach()   // voice hooks + the backfill of meetings without vectors (only when recognition is on)
        if let ops = Dev.env["VOX_VOICE_DO"], !Dev.ranVoiceOps { Dev.ranVoiceOps = true; await VoiceStore.shared.devRun(ops) }
        if Dev.env["VOX_NOINDEX"] == nil { LibraryIndexer.shared.catchUp(library.sessions) }   // DEV: keep a meeting unindexed (keyword fallback checks)
    } }
}

struct ContentView: View {
    @StateObject var m = Model.shared
    @State private var picking = false
    @State private var showSettings = false
    @State private var showPodcast = false
    @State private var showAdd = false
    @State private var path: [Route] = []
    @AppStorage("language") private var language = "system"
    @AppStorage("theme") private var theme = "auto"
    var body: some View {
        NavigationStack(path: $path) {
            LibraryView(m: m, path: $path, onAdd: { showAdd = true }, onSettings: { showSettings = true })
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Route.self) { r in
                switch r {
                case .session(let x): SessionView(session: m.library.sessions.first { $0.id == x.id } ?? x, save: { m.library.update($0) }, rerun: { m.runner.rerun($0, transcribe: $1) }, openAt: m.openAt?.0 == x.id ? m.openAt?.1 : nil)
                case .job(let id): LiveJobView(m: m, id: id, path: $path)
                }
            }
            .onAppear { let first = !Dev.ranOnce; Dev.ranOnce = true; if first, Dev.env["VOX_WIPE"] != nil { for d in ["sessions", "audio", "checkpoints", "jobs.json"] { try? FileManager.default.removeItem(at: JobQueue.support.appendingPathComponent(d)) }; for d in ["sessions", "audio", "checkpoints"] { try? FileManager.default.createDirectory(at: JobQueue.support.appendingPathComponent(d), withIntermediateDirectories: true) } }; BackgroundWork.model = m; m.reload(); if first { m.downloads.start(); m.imports.restorePending() }; if Dev.env["VOX_MFABENCH"] != nil { Task { let rm: ReaderModel = Dev.env["VOX_MFABENCH"] == "E4B" ? .e4b : Dev.env["VOX_MFABENCH"] == "E4B-v2" ? .e4bV2 : Dev.env["VOX_MFABENCH"] == "E2B" ? .e2b : Prefs.readerForLoad(); let d = await m.downloads.store.isComplete(rm) ? await m.downloads.store.dir(rm).path : nil; let r = await Task.detached { MfaBench.run(dir: d) }.value; try? r.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/mfabench-\(Dev.env["VOX_MFABENCH"] ?? "")-\(Dev.env["VOX_MFA_BACKEND"] ?? "0")-\(Dev.env["VOX_TAG"] ?? "").txt"), atomically: true, encoding: .utf8) } }; if let b = Dev.env["VOX_SET_BACKEND"] { BackendBench.chosen = b; try? "chosen=\(BackendBench.chosen) gpuGraph=\(BackendBench.gpuGraph(Prefs.reader) ?? "nil")".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/setbackend-\(Dev.env["VOX_TAG"] ?? "").txt"), atomically: true, encoding: .utf8) }; if Dev.env["VOX_BACKENDTEST"] != nil { Task.detached { let rm = ReaderModel.e2b, st = ModelStore(); let dir = await st.dir(rm).path; let g = dir + "/prefill_decode_fused.gpu.tflite"; if !FileManager.default.fileExists(atPath: g) { try? FileManager.default.copyItem(atPath: NSHomeDirectory() + "/Documents/mfa-gpu.tflite", toPath: g) }; let gp = await st.gpuGraph(rm); let t0 = Date(); let r = BackendBench.run(rm, dir: dir, gpu: gp, threads: Prefs.effectiveThreads) { _ in }; BackendBench.chosen = "GPU"; let used = BackendBench.gpuGraph(rm); let t1 = Date(); let f = ReaderFactory.make(dir: dir); let out = "gpu=\(gp ?? "nil") test \(Int(t1.timeIntervalSince(t0)))s " + r.map { "\($0.key) ok=\($0.value.passed) p=\(Int($0.value.prefill)) d=\(String(format: "%.1f", $0.value.decode)) note=\($0.value.note)" }.joined(separator: "; ") + " chosen=\(BackendBench.chosen) used=\(used != nil) real=\(f.real) load \(Int(Date().timeIntervalSince(t1)))s\n" + ((try? String(contentsOf: StatusLog.url, encoding: .utf8)) ?? "").split(separator: "\n").suffix(4).joined(separator: "\n"); try? out.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/backendtest-\(Dev.env["VOX_TAG"] ?? "").txt"), atomically: true, encoding: .utf8) } }; if Dev.env["VOX_BENCH"] != nil { Task { let t0 = Date(); let n = await Prefs.runBench(); try? "bench pick=\(n ?? -1) eff=\(Prefs.effectiveThreads) cores=\(Prefs.cores) \(Int(Date().timeIntervalSince(t0) * 1000))ms".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/bench.txt"), atomically: true, encoding: .utf8) } }; if let th = Dev.env["VOX_THEME"] { theme = th }; if Dev.env["VOX_CANCEL"] != nil { Task { try? await Task.sleep(nanoseconds: 4_000_000_000); let was = m.imports.dlId != nil; if let id = m.imports.dlId, let j = m.runner.waiting.first(where: { $0.id == id }) { m.imports.remove(j) }; try? await Task.sleep(nanoseconds: 2_000_000_000); try? "cancel was=\(was) now=\(m.imports.dlId != nil) queued=\(m.runner.waiting.count) status=\(m.screen.status)".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/cancel.txt"), atomically: true, encoding: .utf8) } }; if Dev.env["VOX_ACTIONS"] != nil { UserDefaults.standard.set(true, forKey: "showActions") }; if Dev.env["VOX_OPEN"] != nil { Task { let all = await m.library.all(); let p = Dev.env["VOX_OPEN"] == "short" ? all.filter { $0.audio != nil && $0.seconds > 30 }.min { $0.seconds < $1.seconds }.map { [$0] } ?? [] : Dev.env["VOX_OPEN"]!.hasPrefix("id:") ? all.filter { $0.id.uuidString.hasPrefix(Dev.env["VOX_OPEN"]!.dropFirst(3).uppercased()) }.prefix(1).map { $0 } : Array(all.prefix(1)); if let t = Double(Dev.env["VOX_OPEN_AT"] ?? ""), let s = p.first { m.openAt = (s.id, t) }; path = p.map { .session($0) } } }; if let d = Dev.env["VOX_DOWNLOAD"] { if d == "speech" { Task { _ = await m.downloads.downloadSpeech() } } else { m.downloads.downloadReader() } }; if Dev.env["VOX_AUTORUN"] != nil { m.runner.runSample() }; if first, let r = Dev.env["VOX_RECORD"] { Task { try? await Task.sleep(nanoseconds: UInt64((Double(r) ?? 0) * 1e9)); m.capture.toggleRecord() } }; if first, let fr = Dev.env["VOX_FRAMES"] { Frames.start(Double(fr) ?? 0.5) }; if first, Dev.env["VOX_TOUR"] != nil { Task {
                // Once both meetings are fully processed (idle for 10 s), open each session in turn, then stop the frames.
                var idle = 0
                while idle < 10 { try? await Task.sleep(nanoseconds: 1_000_000_000); let busy = m.capture.recording || m.runner.activeJob != nil || !m.runner.waiting.isEmpty || m.runner.pending > 0 || m.library.sessions.count < 2 || m.library.sessions.contains { $0.summary.isEmpty || $0.seconds < 60 }; idle = busy ? 0 : idle + 1 }
                let all = await m.library.all()
                for s in all.prefix(2) { path = [.session(s)]; try? await Task.sleep(nanoseconds: 8_000_000_000); path = []; try? await Task.sleep(nanoseconds: 3_000_000_000) }
                FileManager.default.createFile(atPath: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("frames/STOP").path, contents: nil)
            } }; if Dev.env["VOX_SETTINGS"] != nil { showSettings = true }; if Dev.env["VOX_ASRCHECK"] != nil { Task.detached {
                // DEV: which recognizer runs (target / stand-in / pre-variant), fetch the target if VOX_ASRCHECK=fetch,
                // then the engine on the bundled sample, written to Documents/asrcheck.txt; no session is saved.
                func state() -> String { "target=\(Prefs.asrLatency) runnable=\(Prefs.asrRunnable?.latencyMs ?? -1) legacy=\(Prefs.legacyAsrReady) targetReady=\(ModelDownloads.speechTargetReady)" }
                var o = "before " + state() + "\n"
                if Dev.env["VOX_ASRCHECK"] == "fetch", !ModelDownloads.speechTargetReady {
                    let t0 = Date(); do { try await ModelStore().download(Prefs.asrTarget) { _, _ in }; o += "fetched \(Int(Date().timeIntervalSince(t0))) s\n" } catch { o += "fetch error \(error)\n" }
                    o += "after fetch " + state() + "\n"
                }
                if let pcm = loadWav(Bundle.main.path(forResource: "sample", ofType: "wav") ?? ""), let e = ModelDownloads.nemoEngine() {
                    let t0 = Date(); for i in stride(from: 0, to: pcm.count, by: 16000) { _ = e.push(Array(pcm[i..<min(pcm.count, i + 16000)])) }
                    let u = e.finish() ?? []; e.close()
                    o += String(format: "engine %.1f s audio in %.1f s, %d lines\n", Double(pcm.count) / 16000, Date().timeIntervalSince(t0), u.count) + u.prefix(4).map { $0.text }.joined(separator: " | ") + "\n"
                } else { o += "engine nil\n" }
                o += "end " + state() + "\n" + (try? FileManager.default.contentsOfDirectory(atPath: Storage.modelsRoot.path))!.sorted().joined(separator: ",") + " nemo: " + ((try? FileManager.default.contentsOfDirectory(atPath: Storage.modelsRoot.appendingPathComponent("nemo").path)) ?? []).sorted().joined(separator: ",")
                try? o.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/asrcheck.txt"), atomically: true, encoding: .utf8)
            } }; if Dev.env["VOX_EMBED_DL"] != nil, !EmbedHost.ready { m.downloads.downloadEmbed() }; if let sn = Dev.env["VOX_SNAP"] { Snap.schedule(sn) }; if Dev.env["VOX_EMBEDDIAG"] != nil { Task.detached { var o = ""; if let d = try? Data(contentsOf: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/embed_ref.json")), let refs = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] { for r in refs { let text = r["text"] as? String ?? "", ref = (r["vec"] as? [Double] ?? []).map(Float.init), nt = r["tokens"] as? Int ?? 0; let t0 = Date(); let v = EmbedHost.use(threads: 2) { $0.embed(text) } ?? nil; let ms = Int(Date().timeIntervalSince(t0) * 1000); let tk = EmbedHost.use(threads: 2) { $0.tokens(text) } ?? -1; if let v = v, v.count == ref.count { var dd: Float = 0, na: Float = 0, nb: Float = 0; for i in v.indices { dd += v[i] * ref[i]; na += v[i] * v[i]; nb += ref[i] * ref[i] }; o += String(format: "cos %.4f |v| %.3f tokens %d/%d %d ms  %@\n", dd / (na.squareRoot() * nb.squareRoot()), na.squareRoot(), tk + 2, nt, ms, String(text.prefix(40))) } else { o += "embed nil for \(text.prefix(30))\n" } } } else { o += "no embed_ref.json\n" }; let ss = await MainActor.run { m.library.sessions }; for q in ["gold", "opening hours", "颱風季節的浪", "抓交替"] { if let qv = EmbedHost.use(threads: 2, { $0.embed(EmbedPrompts.query(q)) }) ?? nil { o += "Q \(q):"; for h in LibrarySearch.dense(Array(ss.prefix(1)), qv: qv, maxPassages: 6) { for p in h.passages { o += String(format: " [%@ %.3f @%.0f]", "\(p.kind)", p.score, p.timeSec) } }; o += "\n" } }; try? o.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/embeddiag.txt"), atomically: true, encoding: .utf8) } }; if let y = Dev.env["VOX_YIELD"] { Task { try? await Task.sleep(nanoseconds: UInt64(Int(y) ?? 30) * 1_000_000_000); let before = m.runner.activeJob != nil; m.runner.yieldToSystem(); try? await Task.sleep(nanoseconds: 20_000_000_000); try? "active before=\(before) queued=\(await m.queue.count) parked=\(m.runner.parked.count) active now=\(m.runner.activeJob != nil) partial=\((await m.queue.all).first.map { Checkpoint.loadPartial($0.id) != nil } ?? false)".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/yield.txt"), atomically: true, encoding: .utf8) } }; if let st = Dev.env["VOX_STOP"] { Task { try? await Task.sleep(nanoseconds: UInt64(Int(st) ?? 30) * 1_000_000_000); let before = m.runner.activeJob != nil; m.runner.stopProcessing(); try? await Task.sleep(nanoseconds: 20_000_000_000); try? "active before=\(before) parked=\(m.runner.parked.count) active now=\(m.runner.activeJob != nil) status=\(m.screen.status)".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/stop.txt"), atomically: true, encoding: .utf8) } }; if Dev.env["VOX_STATUSLOG"] != nil { Task { var lastN = -1; var peakMB = 0.0; while true { NSLog("VOXSTATUS %@", m.screen.status); do { var i = task_vm_info_data_t(); var c = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size); _ = withUnsafeMutablePointer(to: &i) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(c)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &c) } }; peakMB = max(peakMB, Double(i.phys_footprint) / 1048576); NSLog("VOXRSS %.0f peak %.0f MB kpeak %.0f resident %.0f rpeak %.0f MB", Double(i.phys_footprint) / 1048576, peakMB, Double(i.ledger_phys_footprint_peak) / 1048576, Double(i.resident_size) / 1048576, Double(i.resident_size_peak) / 1048576) }; if m.screen.lines.count != lastN { lastN = m.screen.lines.count; for l in m.screen.lines { NSLog("VOXLINE %d %.1f-%.1f %@", l.speaker, l.start, l.end, l.text) } }; try? m.screen.status.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/status.txt"), atomically: true, encoding: .utf8); try? await Task.sleep(nanoseconds: 2_000_000_000) } } }; if Dev.env["VOX_ROUNDTRIP"] != nil { Task { if let s = await m.library.all().first(where: { $0.audio != nil }), let u = await SessionFile.export(s) { let r = SessionFile.read(u); m.screen.status = "rt \((try? FileManager.default.attributesOfItem(atPath: u.path)[.size]) ?? 0)B lines \(r?.utterances?.count ?? -1)/\(s.lines.count) title \(r?.title == s.title)"; m.imports.importAudio(u) } else { m.screen.status = "rt: export failed" }; try? m.screen.status.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/rt.txt"), atomically: true, encoding: .utf8) } }; if Dev.env["VOX_ADD"] != nil { showAdd = true }; if Dev.env["VOX_DIALOG"] == "podcast" { showPodcast = true }; if first { DevJobs.run(m) }; m.runner.drain(); if let fs = Dev.env["VOX_IMPORT"] { for f in fs.split(separator: ",").map(String.init) { m.imports.importAudio(URL(fileURLWithPath: f.hasPrefix("/") ? f : NSHomeDirectory() + "/" + f)) } }; if let q = Dev.env["VOX_PODCAST"] { Task { if let sr = try? await Podcast.search(q).first, let ep = try? await Podcast.episodes(sr.feedUrl, limit: 3).last { m.imports.addEpisode(ep) } else { m.screen.status = "podcast: no result" } } } }
            .sheet(isPresented: $showAdd) { AddSourceSheet(onFile: { picking = true }, onPodcast: { showPodcast = true }, onSession: { picking = true }) }
            .fullScreenCover(isPresented: Binding(get: { m.capture.recording }, set: { _ in })) { CaptureView(m: m) }
            .sheet(isPresented: $showPodcast) { PodcastView { ep in m.downloads.withModels { m.imports.addEpisode(ep) } } }
            .sheet(isPresented: $showSettings) { SettingsView(m: m, language: $language, theme: $theme) { s, t in
                // The People screen opens a meeting at the person's first line: Settings closes, the meeting opens.
                showSettings = false; LibraryView.markSeen(s.id); m.openAt = (s.id, t); path = [.session(s)]
            } }
            .onOpenURL { u in m.downloads.withModels { m.imports.importAudio(u) } }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.audio, .mpeg4Audio]) { if case .success(let u) = $0 { m.downloads.withModels { m.imports.importAudio(u) } } }
            .modifier(DownloadConsent(downloads: m.downloads))
        }
        .preferredColorScheme((Theme(rawValue: theme) ?? .auto).scheme)
        .modifier(ThemeStyle(theme: Theme(rawValue: theme) ?? .auto))
    }
}

/// First-use download prompt (Android DownloadConsentDialog): the total and each model's size, a mobile-data
/// warning off Wi-Fi, and Download now / Wi-Fi only / Later.
struct DownloadConsent: ViewModifier {
    @ObservedObject var downloads: ModelDownloads
    func body(content: Content) -> some View {
        let r = downloads.consentRequest
        content.alert(L("dl_consent_title"), isPresented: Binding(get: { downloads.consentRequest != nil }, set: { _ in }), presenting: r) { r in
            Button(L("dl_consent_now")) { downloads.answer(r, .anyNetwork) }
            Button(L("dl_consent_wifi_only")) { downloads.answer(r, .wifiOnly) }
            Button(L("dl_consent_later"), role: .cancel) { downloads.answer(r, nil) }
        } message: { r in Text(Self.message(r)) }
    }
    static func message(_ r: ModelDownloads.ConsentRequest) -> String {
        var lines = [L("dl_consent_total", SizeFormat.bytes(r.pending.total))]
        if r.pending.speechBytes > 0 { lines.append(L("dl_consent_speech", SizeFormat.bytes(r.pending.speechBytes))) }
        if r.pending.readerBytes > 0 { lines.append(L("dl_consent_reader", SizeFormat.bytes(r.pending.readerBytes))) }
        lines.append(""); lines.append(L("dl_consent_body"))
        if !r.unmetered { lines.append(""); lines.append(L("dl_consent_metered")) }
        return lines.joined(separator: "\n")
    }
}

/// What the library pushes: a saved meeting, or a queued / processing job's live view (Android: the meeting screen
/// watching the run, transcript and notes as they come, the audio playable meanwhile).
enum Route: Hashable { case session(Session), job(UUID) }

extension Notification.Name { static let voxSessionSaved = Notification.Name("voxSessionSaved") }
