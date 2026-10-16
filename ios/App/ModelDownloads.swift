import Foundation

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

    func downloadSpeech() async -> Bool {
        if Dev.env["VOX_BASE"] != nil { return true }
        if await store.isComplete(.speech) { return true }
        do {
            try await store.download(.speech) { d, t in Task { @MainActor in self.screen.status = L("download_speech", Int(d / 1_000_000), Int(t / 1_000_000)) } }
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
            do {
                try await store.download(Prefs.reader) { d, t in Task { @MainActor in self.screen.status = L("download_reader_p", Int(d / 1_000_000), Int(t / 1_000_000)) } }
                await MainActor.run { self.screen.status = L("reader_ready") }
            } catch { await MainActor.run { self.screen.status = L("download_failed", "\(error)") } }
        }
    }
}
