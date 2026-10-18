import Foundation

/// The one way the app writes a download or storage size (Android `SizeFormat`): decimal units, a dot
/// and the same precision whatever the locale ("2.2 GB", "270 MB", "4 KB"). Sizes come from the model
/// registry (`ReaderModel.totalBytes`), never from figures written into the strings.
enum SizeFormat {
    static func bytes(_ n: Int64) -> String {
        let kb: Int64 = 1_000, mb: Int64 = 1_000_000, gb: Int64 = 1_000_000_000
        if n >= gb { return String(format: "%.1f GB", locale: Locale(identifier: "en_US_POSIX"), Double(n) / Double(gb)) }
        if n >= mb { return "\((n + mb / 2) / mb) MB" }
        if n > 0 { return "\(max(1, (n + kb / 2) / kb)) KB" }
        return "0 MB"
    }
}

/// Whether the first-use model downloads (speech engine, AI notes model) may start now (Android
/// `DownloadPlan`). The user sees the total size before any byte is fetched and chooses: now (any
/// network), only on Wi-Fi (an unmetered network), or later (asked again at the next recording or import).
enum DownloadPlan {
    enum Consent: String { case unset, anyNetwork, wifiOnly }
    enum Action { case proceed, ask }
    struct Pending: Equatable {
        let speechBytes: Int64, readerBytes: Int64
        /// The missing notes model replaces one that is installed and keeps working meanwhile (a model migration).
        var readerUpgrade = false
        var total: Int64 { speechBytes + readerBytes }
        var any: Bool { total > 0 }
        /// What a recording or an import has to wait for.
        /// The chosen recognizer is missing but another one is installed and transcribes meanwhile (Android speechUpgrade).
        var speechUpgrade = false
        var blocking: Int64 { (speechUpgrade ? 0 : speechBytes) + (readerUpgrade ? 0 : readerBytes) }
    }
    static func pending(speechReady: Bool, speechBytes: Int64, readerReady: Bool, readerBytes: Int64, replacedReady: Bool = false,
                        speechStandIn: Bool = false) -> Pending {
        Pending(speechBytes: speechReady ? 0 : speechBytes, readerBytes: readerReady ? 0 : readerBytes, readerUpgrade: !readerReady && replacedReady,
                speechUpgrade: !speechReady && speechStandIn)
    }
    /// Before a recording or an import: ask only for what it cannot run without.
    static func decide(_ c: Consent, unmetered: Bool, _ p: Pending) -> Action {
        if p.blocking <= 0 || c == .anyNetwork || (c == .wifiOnly && unmetered) { return .proceed }
        return .ask
    }
    /// A background prefetch may run: consent given for the current network. A model upgrade alone (the old
    /// model still works) waits for Wi-Fi whatever the consent.
    static func mayPrefetch(_ c: Consent, unmetered: Bool, _ p: Pending) -> Bool {
        p.any && (c == .anyNetwork || (c == .wifiOnly && unmetered)) && (unmetered || p.blocking > 0)
    }
}

/// Which notes model runs for the user's choice (Android `LlmRegistry.current / runnable / reclaimable`), over
/// model ids and their `replacedBy` links: a replaced model (E4B mobile-v1) is not offered any more, keeps
/// reading on a phone that has it until its successor is installed, then its files go.
enum ReaderChoice {
    /// The model a choice stands for today: `id`, or what replaced it (followed to the end).
    static func current(_ id: String, replacedBy: [String: String]) -> String {
        var c = id, n = 0
        while let r = replacedBy[c], n < 8 { c = r; n += 1 }
        return c
    }
    /// The current model when installed; else an installed model it replaced; else the current one (to download).
    static func runnable(_ id: String, replacedBy: [String: String], ready: (String) -> Bool) -> String {
        let want = current(id, replacedBy: replacedBy)
        if ready(want) { return want }
        return replacedBy.keys.sorted().first { $0 != want && current($0, replacedBy: replacedBy) == want && ready($0) } ?? want
    }
    /// Replaced models whose successor passes `installed`: their files can go.
    static func reclaimable(replacedBy: [String: String], installed: (String) -> Bool) -> [String] {
        replacedBy.keys.sorted().filter { let c = current($0, replacedBy: replacedBy); return c != $0 && installed(c) }
    }
}

/// Whether a finished transcript holds any speech (Android `SpeechCheck`): a silent or near-silent
/// recording comes back with no lines, or only fillers and noise tags ("嗯", "[noise]"). Such a meeting
/// is not "Done": it gets its own status and no AI notes.
enum SpeechCheck {
    static func hasSpeech(_ texts: [String]) -> Bool {
        texts.contains { ReaderProtocol.cleanText($0).contains { $0.isLetter || $0.isNumber } }
    }
}
