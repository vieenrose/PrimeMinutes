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
        var total: Int64 { speechBytes + readerBytes }
        var any: Bool { total > 0 }
    }
    static func pending(speechReady: Bool, speechBytes: Int64, readerReady: Bool, readerBytes: Int64) -> Pending {
        Pending(speechBytes: speechReady ? 0 : speechBytes, readerBytes: readerReady ? 0 : readerBytes)
    }
    static func decide(_ c: Consent, unmetered: Bool, _ p: Pending) -> Action {
        if !p.any || c == .anyNetwork || (c == .wifiOnly && unmetered) { return .proceed }
        return .ask
    }
    /// A background prefetch may run: consent given for the current network.
    static func mayPrefetch(_ c: Consent, unmetered: Bool, _ p: Pending) -> Bool {
        p.any && (c == .anyNetwork || (c == .wifiOnly && unmetered))
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
