import Foundation

/// Which phones may offer a reader model, and whether it may load now (pure; the thresholds live in the
/// model's registry entry, `ReaderModel.minPhysicalBytes` / `minAvailableBytes`).
enum ReaderGate {
    /// The phone's RAM class allows the model to be offered (iOS reports a little less than the marketed size).
    static func offered(physical: UInt64, minPhysical: UInt64) -> Bool { physical >= minPhysical }

    /// The model to load for this run: `chosen` when the memory the app may still use (`os_proc_available_memory`)
    /// is enough for it, else `fallback` when it is installed; with no fallback installed the chosen model still
    /// loads (its measured peak is well under the threshold, which is only a margin).
    static func forLoad(chosen: String, available: UInt64, minAvailable: UInt64, fallback: String?, fallbackReady: Bool) -> String {
        guard minAvailable > 0, available < minAvailable, let fallback, fallback != chosen, fallbackReady else { return chosen }
        return fallback
    }
}
