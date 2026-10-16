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
    /// The speech engine (ASR + diarizer) on the downloaded models; nil when they cannot be loaded.
    nonisolated static func nemoEngine() -> NemoEngine? {
        NemoEngine(xasr: nemoPath("xasr_host.bin"), diar: nemoPath("diar_host.bin"), xasrTflite: nemoPath("xasr_stream_q8_octav.tflite"), diarTflite: nemoPath("sortformer_int8_f190.tflite"), threads: Prefs.effectiveThreads, settle: Double(Prefs.speakerDelay))
    }

    // MARK: first-use downloads with the user's say (Android ModelPrefetch / DownloadConsentDialog)

    /// A size prompt waiting for the user: what is missing, whether the phone is on Wi-Fi, and the run
    /// (a recording, an import) to start once they agree — nil at first launch.
    struct ConsentRequest: Identifiable { let id = UUID(); let pending: DownloadPlan.Pending; let unmetered: Bool; let action: (() -> Void)? }
    @Published var consentRequest: ConsentRequest?
    /// Fraction of the background prefetch while it runs.
    @Published var prefetchProgress: Double?
    /// "Later" at first launch: not asked again until the app restarts (a run that needs the models still asks).
    private var laterThisRun = false
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
        return DownloadPlan.pending(speechReady: Storage.ready(.speech), speechBytes: ReaderModel.speech.totalBytes,
                                    readerReady: Storage.ready(Prefs.reader), readerBytes: Prefs.reader.totalBytes)
    }

    /// Network watch + the first-launch prompt (or the prefetch the user already agreed to).
    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            let un = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            Task { @MainActor in guard let self else { return }; self.unmetered = un; self.maybePrefetch() }
        }
        monitor.start(queue: DispatchQueue(label: "net"))
        // DEV: VOX_DLGATE=metered|wifi shows the prompt with the full sizes, models present or not.
        if let g = Dev.env["VOX_DLGATE"] {
            consentRequest = ConsentRequest(pending: DownloadPlan.pending(speechReady: false, speechBytes: ReaderModel.speech.totalBytes, readerReady: false, readerBytes: Prefs.reader.totalBytes), unmetered: g == "wifi", action: nil)
            return
        }
        let p = pending
        if p.any && consent == .unset && !laterThisRun { consentRequest = ConsentRequest(pending: p, unmetered: unmetered, action: nil) }
        else { maybePrefetch() }
    }

    /// Runs `action` (a recording, an import) at once when nothing needs downloading or the user agreed for
    /// this network; else asks first with the size.
    func withModels(_ action: @escaping () -> Void) {
        let p = pending
        if DownloadPlan.decide(consent, unmetered: unmetered, p) == .proceed { action(); return }
        consentRequest = ConsentRequest(pending: p, unmetered: unmetered, action: action)
    }

    func answer(_ r: ConsentRequest, _ c: DownloadPlan.Consent?) {
        consentRequest = nil
        guard let c else { laterThisRun = true; return }   // Later
        consent = c
        if c == .wifiOnly && !r.unmetered { screen.status = L("dl_consent_waiting_wifi"); return }
        // With a run waiting, the run fetches what it needs (its own progress); else prefetch now.
        if let a = r.action { a() } else { maybePrefetch() }
    }

    private func maybePrefetch() {
        let p = pending
        guard prefetchTask == nil, DownloadPlan.mayPrefetch(consent, unmetered: unmetered, p) else { return }
        prefetchProgress = 0
        let reader = Prefs.reader
        StatusLog.add("prefetch \(SizeFormat.bytes(p.total))")
        // Speech first, on its own task: a recording started meanwhile waits for these files only, never
        // for the notes model (two downloads of one file would race on its .part).
        let speech: Task<Bool, Never> = Task.detached { [store] in
            guard p.speechBytes > 0 else { return true }
            do { try await store.download(.speech) { d, _ in Task { @MainActor in self.prefetchProgress = Double(d) / Double(max(1, p.total)) } }; return true }
            catch { StatusLog.add("prefetch stopped: \(error)"); return false }   // a lost network: the next run (or prefetch) resumes from the .part files
        }
        prefetchSpeech = speech
        prefetchTask = Task.detached { [store] in
            if await speech.value, p.readerBytes > 0 {
                do { try await store.download(reader) { d, _ in Task { @MainActor in self.prefetchProgress = Double(p.speechBytes + d) / Double(max(1, p.total)) } }; StatusLog.add("prefetch done") }
                catch { StatusLog.add("prefetch stopped: \(error)") }
            }
            await MainActor.run { self.prefetchProgress = nil; self.prefetchTask = nil; self.prefetchSpeech = nil }
        }
    }
    /// Lets a run's own download start only once the prefetch left those files (speech: just its part).
    func awaitPrefetch(speechOnly: Bool) async {
        if speechOnly { _ = await prefetchSpeech?.value } else { await prefetchTask?.value }
    }

    func downloadSpeech() async -> Bool {
        if Dev.env["VOX_BASE"] != nil { return true }
        await awaitPrefetch(speechOnly: true)
        if await store.isComplete(.speech) { return true }
        do {
            try await store.download(.speech) { d, t in Task { @MainActor in self.screen.status = L("download_speech", SizeFormat.bytes(d), SizeFormat.bytes(t)) } }
            return true
        } catch { screen.status = L("download_failed", "\(error)"); return false }
    }
    /// Search model (semantic search + Ask): percent while it downloads, nil otherwise.
    @Published var embedProgress: Int?
    func downloadEmbed() {
        guard embedProgress == nil else { return }
        embedProgress = 0
        Task.detached { [store] in
            do {
                try await store.download(.embed) { d, t in Task { @MainActor in self.embedProgress = Int(d * 100 / max(1, t)) } }
                await MainActor.run { StatusLog.add("search model downloaded"); self.embedProgress = nil; LibraryIndexer.shared.catchUp(self.library.sessions) }
            } catch { await MainActor.run { StatusLog.add("search model download failed: \(error)"); self.embedProgress = nil; self.screen.status = L("search_model_download_failed") + " (\(error))" } }
        }
    }
    func downloadReader() {
        screen.status = L("download_reader")
        Task.detached { [store] in
            await self.awaitPrefetch(speechOnly: false)
            do {
                try await store.download(Prefs.reader) { d, t in Task { @MainActor in self.screen.status = L("download_reader_p", SizeFormat.bytes(d), SizeFormat.bytes(t)) } }
                await MainActor.run { self.screen.status = L("reader_ready") }
            } catch { await MainActor.run { self.screen.status = L("download_failed", "\(error)") } }
        }
    }
}
