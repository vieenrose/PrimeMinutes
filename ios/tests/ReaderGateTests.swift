import Foundation

/// ReaderGate: which phones offer E4B, and the per-run fallback when memory is short.
@main struct ReaderGateTests {
    static var fails = 0
    static func check(_ c: Bool, _ what: String) { if !c { fails += 1; print("FAIL \(what)") } }
    static func main() {
        let minPhys: UInt64 = 5 << 30, minAvail: UInt64 = 2_500_000_000, gib: UInt64 = 1 << 30
        check(!ReaderGate.offered(physical: 3_900_000_000, minPhysical: minPhys), "4 GB phone (3.6 GiB) not offered")
        check(ReaderGate.offered(physical: 5_910_814_720, minPhysical: minPhys), "6 GB 14 Pro Max (5637 MB) offered")
        check(ReaderGate.offered(physical: 8 * gib, minPhysical: minPhys), "8 GB phone offered")
        check(ReaderGate.offered(physical: 1, minPhysical: 0), "no threshold: every phone")
        check(ReaderGate.forLoad(chosen: "E4B-v2", available: 3_300_000_000, minAvailable: minAvail, fallback: "E2B", fallbackReady: true) == "E4B-v2", "enough memory: E4B")
        check(ReaderGate.forLoad(chosen: "E4B-v2", available: minAvail, minAvailable: minAvail, fallback: "E2B", fallbackReady: true) == "E4B-v2", "exactly the threshold: E4B")
        check(ReaderGate.forLoad(chosen: "E4B-v2", available: 2_300_000_000, minAvailable: minAvail, fallback: "E2B", fallbackReady: true) == "E2B", "short: E2B this run")
        check(ReaderGate.forLoad(chosen: "E4B-v2", available: 2_300_000_000, minAvailable: minAvail, fallback: "E2B", fallbackReady: false) == "E4B-v2", "short, E2B not installed: E4B still")
        check(ReaderGate.forLoad(chosen: "E4B-v2", available: 0, minAvailable: minAvail, fallback: nil, fallbackReady: true) == "E4B-v2", "no fallback")
        check(ReaderGate.forLoad(chosen: "E2B", available: 0, minAvailable: 0, fallback: nil, fallbackReady: false) == "E2B", "E2B: no check")
        print(fails == 0 ? "reader gate: all passed" : "reader gate: \(fails) failed"); exit(fails == 0 ? 0 : 1)
    }
}
