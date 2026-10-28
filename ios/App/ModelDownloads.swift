import Foundation
import Network

/// Model files on the device: the speech models (first recording), the notes model and the search model, with the
/// download progress the UI shows.
@MainActor final class ModelDownloads: ObservableObject {
    let store = ModelStore()
    private let screen: MeetingScreen
    private let library: SessionLibrary
    init(screen: MeetingScreen, library: SessionLibrary) { self.screen = screen; self.library = library }

    /// Dev override (VOX_BASE, simulator only) else the downloaded copy in Application Support.
    nonisolated static func nemoPath(_ n: String) -> String {
        if let b = Dev.env["VOX_BASE"] { return "\(b)/models/\(n)" }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("models/nemo/\(n)").path
    }
    /// The speech engine (ASR + diarizer) on the downloaded models; nil when they cannot be loaded. The recognizer is the
    /// chosen latency once installed, else the closest installed variant, else the pre-variant 480 ms pair.
    nonisolated static func nemoEngine() -> NemoEngine? {
        let diar = nemoPath("diar_host.bin"), diarT = diarGraph ?? nemoPath(ReaderModel.standInDiarTflite), th = Prefs.effectiveThreads, settle = Double(Prefs.speakerDelay)
        let e: NemoEngine?
        if Dev.env["VOX_BASE"] == nil, let v = Prefs.asrRunnable {
            let d = Storage.modelsRoot.appendingPathComponent(v.id)
            e = NemoEngine(xasr: d.appendingPathComponent(AsrRegistry.hostFile).path, diar: diar, xasrTflite: d.appendingPathComponent(v.tfliteFile).path,
                           diarTflite: diarT, threads: th, settle: settle, tokenOffsetMs: v.tokenOffsetMs)
            if AsrRegistry.reclaimsOthers(loaded: v, ok: e != nil, target: Prefs.asrLatency) { Storage.reclaimOtherAsr(v) }   // the chosen one loads: the others go
        } else {
            e = NemoEngine(xasr: nemoPath(ReaderModel.legacyXasrHost), diar: diar, xasrTflite: nemoPath(ReaderModel.legacyXasrTflite), diarTflite: diarT,
                           threads: th, settle: settle, tokenOffsetMs: 300)
        }
        if e != nil, Dev.env["VOX_BASE"] == nil, diarT == nemoPath(ReaderModel.diarTflite) { Storage.reclaimStandInDiar() }   // the f380 diarizer loads: f190 goes
        return e
    }
    /// The diarizer graph the engine loads: the f380 one once installed, else the f190 stand-in of an earlier install; nil
    /// when the diarizer cannot run yet.
    nonisolated static var diarGraph: String? {
        if Storage.ready(.speech) { return nemoPath(ReaderModel.diarTflite) }
        func size(_ n: String) -> Int64? { try? FileManager.default.attributesOfItem(atPath: nemoPath(n))[.size] as? Int64 }
        let host = ReaderModel.speech.files[0]
        return size(host.name) == host.size && size(ReaderModel.standInDiarTflite) == ReaderModel.standInDiarBytes ? nemoPath(ReaderModel.standInDiarTflite) : nil
    }
    /// Right after the chosen recognizer's download (a latency switch): load it once, so a successful load deletes the
    /// other variants now rather than at the next recording or import (Android ModelManager.warmLitertCache). A stand-in
    /// still running keeps its open files: unlinking them does not disturb it.
    nonisolated static func warmChosenAsr() {
        guard Dev.env["VOX_BASE"] == nil, speechTargetReady else { return }
        StatusLog.add("warm load of the chosen recognizer")
        _ = nemoEngine()
    }
    /// The speech engine can run now (diarizer + some recognizer), and the chosen recognizer itself is installed.
    nonisolated static var speechRunnable: Bool { diarGraph != nil && (Prefs.asrRunnable != nil || Prefs.legacyAsrReady) }
    nonisolated static var speechTargetReady: Bool { Storage.ready(.speech) && Storage.ready(Prefs.asrTarget) }
    nonisolated static var speechBytes: Int64 { Storage.downloadBytes(.speech) + Storage.downloadBytes(Prefs.asrTarget) }

    // MARK: first-use downloads with the user's say (Android ModelPrefetch / DownloadConsentDialog)

    /// A size prompt waiting for the user: what is missing, whether the phone is on Wi-Fi, and the run
    /// (a recording, an import) to start once they agree — nil at first launch.
    struct ConsentRequest: Identifiable { let id = UUID(); let pending: DownloadPlan.Pending; let unmetered: Bool; let action: (() -> Void)? }
    @Published var consentRequest: ConsentRequest?
    /// Fraction of the background prefetch while it runs.
    @Published var prefetchProgress: Double?
    /// Bytes the running prefetch fetches in all (the card shows "so far / total").
    @Published private(set) var prefetchTotal: Int64 = 0
    /// The prefetch is waiting to resume (suspended app, lost network): the card says Paused, with Resume.
    @Published var prefetchPaused = false
    /// Cancel on the progress card: no automatic restart (network change) until the app restarts or the user
    /// agrees again; a recording or an import still fetches what it needs (Android ModelPrefetch.cancel).
    private var cancelledThisRun = false
    /// "Later" at first launch: not asked again until the app restarts (a run that needs the models still asks).
    private var gate = DownloadPlan.PromptGate()
    private var prefetchTask: Task<Void, Never>?
    private var prefetchSpeech: Task<Bool, Never>?
    private let monitor = NWPathMonitor()
    /// On Wi-Fi (or wired): a satisfied path that is neither expensive (cellular, hotspot) nor in Low Data Mode.
    @Published private(set) var unmetered = true

    var consent: DownloadPlan.Consent {
        get { DownloadPlan.Consent(rawValue: UserDefaults.standard.string(forKey: "dlConsent") ?? "") ?? .unset }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "dlConsent") }
    }
    /// What the speech engine and the chosen AI notes model still need (sizes from the registry).
    var pending: DownloadPlan.Pending {
        if Dev.env["VOX_BASE"] != nil { return DownloadPlan.Pending(speechBytes: 0, readerBytes: 0) }
        let target = Prefs.readerTarget, ready = Storage.ready(target)
        // A replaced model still installed (E4B mobile-v1 while mobile-v2 is fetched) keeps the reader working.
        return DownloadPlan.pending(speechReady: Self.speechTargetReady, speechBytes: Self.speechBytes,
                                    readerReady: ready, readerBytes: Storage.downloadBytes(target), replacedReady: !ready && Prefs.reader.id != target.id,
                                    speechStandIn: Self.speechRunnable)
    }

    /// Network watch + the first-launch prompt (or the prefetch the user already agreed to).
    func start() {
        Storage.reclaimReplaced()   // a migration that finished (the new model already loads): the old files go
        monitor.pathUpdateHandler = { [weak self] path in
            let un = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            Task { @MainActor in guard let self else { return }; self.unmetered = un; self.maybePrefetch() }
        }
        monitor.start(queue: DispatchQueue(label: "net"))
        // DEV: VOX_DLGATE=metered|wifi shows the prompt with the full sizes, models present or not.
        if let g = Dev.env["VOX_DLGATE"] {
            consentRequest = ConsentRequest(pending: DownloadPlan.pending(speechReady: false, speechBytes: ReaderModel.speech.totalBytes + Prefs.asrTarget.totalBytes, readerReady: false, readerBytes: Prefs.reader.totalBytes), unmetered: g == "wifi", action: nil)
            return
        }
        let p = pending
        if gate.mayAutoPrompt(consent, p) { consentRequest = ConsentRequest(pending: p, unmetered: unmetered, action: nil) }
        else { maybePrefetch() }
    }

    /// Runs `action` (a recording, an import) at once when nothing needs downloading or the user agreed for
    /// this network; else asks first with the size.
    func withModels(_ action: @escaping () -> Void) {
        let p = pending
        if DownloadPlan.decide(consent, unmetered: unmetered, p) == .proceed { action(); return }
        gate.explicitPrompt()
        consentRequest = ConsentRequest(pending: p, unmetered: unmetered, action: action)
    }

    func answer(_ r: ConsentRequest, _ c: DownloadPlan.Consent?) {
        if consentRequest?.id == r.id { consentRequest = nil }
        guard let c else { gate.dismissed(); StatusLog.add("download prompt: later"); return }   // Later
        gate.answered()
        consent = c
        cancelledThisRun = false
        if c == .wifiOnly && !r.unmetered { screen.status = L("dl_consent_waiting_wifi"); return }
        // With a run waiting, the run fetches what it needs (its own progress); else prefetch now.
        if let a = r.action { a() } else { maybePrefetch() }
    }

    /// The prompt went away without a button (an alert torn down by a re-render, a sheet swiped down): the
    /// same as Later, so a network change or the next re-render never brings it back by itself.
    func dismissPrompt() {
        guard let r = consentRequest else { return }
        answer(r, nil)
    }

    /// A latency switch: fetch the new recognizer in the background (Android SessionViewModel SaveConfig → prefetch).
    func maybePrefetchSpeech() { maybePrefetch() }
    private func maybePrefetch() {
        let p = pending
        guard prefetchTask == nil, !cancelledThisRun, DownloadPlan.mayPrefetch(consent, unmetered: unmetered, p) else { return }
        prefetchProgress = 0; prefetchTotal = p.total
        let reader = Prefs.readerTarget
        StatusLog.add("prefetch \(SizeFormat.bytes(p.total))")
        // Speech first, on its own task: a recording started meanwhile waits for these files only, never
        // for the notes model (two downloads of one file would race on its .part).
        let speech: Task<Bool, Never> = Task.detached { [store] in
            guard p.speechBytes > 0 else { return true }
            do {
                let base = Storage.downloadBytes(.speech)
                try await self.fetch(.speech, "prefetch speech", onPause: { self.prefetchPaused = $0 }) { d, _ in Task { @MainActor in self.prefetchProgress = Double(d) / Double(max(1, p.total)) } }
                try await self.fetch(Prefs.asrTarget, "prefetch recognizer", onPause: { self.prefetchPaused = $0 }) { d, _ in Task { @MainActor in self.prefetchProgress = Double(base + d) / Double(max(1, p.total)) } }
                Self.warmChosenAsr()
                return true
            }
            catch { StatusLog.add("prefetch stopped: \(error)"); return false }   // Cancel or a permanent error: a run fetches what it needs itself
        }
        prefetchSpeech = speech
        prefetchTask = Task.detached { [store] in
            if await speech.value, p.readerBytes > 0 {
                do { try await self.fetch(reader, "prefetch reader", onPause: { self.prefetchPaused = $0 }) { d, _ in Task { @MainActor in self.prefetchProgress = Double(p.speechBytes + d) / Double(max(1, p.total)) } }; StatusLog.add("prefetch done") }
                catch { StatusLog.add("prefetch stopped: \(error)") }
            }
            await MainActor.run { self.prefetchProgress = nil; self.prefetchPaused = false; self.prefetchTask = nil; self.prefetchSpeech = nil }
        }
    }
    /// Cancel button on the progress card: stops both parts of the prefetch (the partial file stays for a resume).
    func cancelPrefetch() {
        guard prefetchTask != nil else { return }
        cancelledThisRun = true
        StatusLog.add("prefetch cancelled")
        prefetchSpeech?.cancel(); prefetchTask?.cancel()
        DownloadResumer.shared.kick()   // a paused prefetch wakes up to see the cancel
    }
    /// One model download that pauses through a suspension or a lost network and resumes from its `.part`
    /// (DownloadResumer); throws only for a cancel or a permanent error.
    nonisolated func fetch(_ m: ReaderModel, _ name: String, onPause: @escaping @MainActor (Bool) -> Void, progress: @escaping @Sendable (Int64, Int64) -> Void) async throws {
        let store = self.store
        try await DownloadResumer.shared.run(name, onPause: onPause) { try await store.download(m, progress: progress) }
    }
    /// Run downloads (a recording, an import, the notes model) say "Paused" in the status line while they wait.
    func pausedStatus(_ on: Bool) { if on { screen.status = L("download_paused") } }
    /// Lets a run's own download start only once the prefetch left those files (speech: just its part).
    func awaitPrefetch(speechOnly: Bool) async {
        if speechOnly { _ = await prefetchSpeech?.value } else { await prefetchTask?.value }
    }

    func downloadSpeech() async -> Bool {
        if Dev.env["VOX_BASE"] != nil { return true }
        // A stand-in (another latency, the pre-variant recognizer, the f190 diarizer) runs while the chosen files are
        // fetched in the background: a recording never waits for a latency switch or a model update.
        if Self.speechRunnable { if !Self.speechTargetReady { maybePrefetch() }; return true }
        await awaitPrefetch(speechOnly: true)
        if Self.speechRunnable { return true }
        do {
            try await fetch(.speech, "speech", onPause: { self.pausedStatus($0) }) { d, t in Task { @MainActor in self.screen.status = L("download_speech", SizeFormat.bytes(d), SizeFormat.bytes(t)) } }
            try await fetch(Prefs.asrTarget, "recognizer", onPause: { self.pausedStatus($0) }) { d, t in Task { @MainActor in self.screen.status = L("download_speech", SizeFormat.bytes(d), SizeFormat.bytes(t)) } }
            return true
        } catch { screen.status = L("download_failed", "\(error)"); return false }
    }
    /// Search model (semantic search + Ask): percent while it downloads, nil otherwise.
    @Published var embedProgress: Int?
    func downloadEmbed() {
        guard embedProgress == nil else { return }
        embedProgress = 0
        Task.detached {
            do {
                try await self.fetch(.embed, "search model", onPause: { _ in }) { d, t in Task { @MainActor in self.embedProgress = Int(d * 100 / max(1, t)) } }
                await MainActor.run { StatusLog.add("search model downloaded"); self.embedProgress = nil; LibraryIndexer.shared.catchUp(self.library.sessions) }
            } catch { await MainActor.run { StatusLog.add("search model download failed: \(error)"); self.embedProgress = nil; self.screen.status = L("search_model_download_failed") + " (\(error))" } }
        }
    }
    func downloadReader() {
        screen.status = L("download_reader")
        Task.detached {
            await self.awaitPrefetch(speechOnly: false)
            do {
                try await self.fetch(Prefs.readerTarget, "reader", onPause: { self.pausedStatus($0) }) { d, t in Task { @MainActor in self.screen.status = L("download_reader_p", SizeFormat.bytes(d), SizeFormat.bytes(t)) } }
                await MainActor.run { self.screen.status = L("reader_ready") }
            } catch { await MainActor.run { self.screen.status = L("download_failed", "\(error)") } }
        }
    }
}
