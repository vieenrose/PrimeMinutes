// macOS host test: the recognizer variants (same cases as Android AsrRegistryTest).
import Foundation

@main struct AsrRegistryTests {
    static var failures = 0
    static func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL \(what)") } }
    static func main() {
        check(AsrRegistry.latencies == [160, 480, 960, 1920], "four stops, shortest first")
        check(AsrRegistry.byLatency(1234).latencyMs == 1920, "unknown setting reads as the default")
        check(Set(AsrRegistry.all.map(\.id)).count == 4 && Set(AsrRegistry.all.map(\.tfliteFile)).count == 4, "own folder and file")
        check(AsrRegistry.all.allSatisfy { $0.tfliteBytes > 0 && $0.tfliteSha.count == 64 }, "every graph pinned")
        let only = { (s: Set<Int>) in { (v: AsrVariant) in s.contains(v.latencyMs) } }
        check(AsrRegistry.runnable(1920, installed: only([480, 1920]))?.latencyMs == 1920, "target when installed")
        check(AsrRegistry.runnable(1920, installed: only([480]))?.latencyMs == 480, "stand-in while the target downloads")
        check(AsrRegistry.runnable(480, installed: only([160, 960]))?.latencyMs == 960, "tie goes to the more accurate")
        check(AsrRegistry.runnable(960, installed: only([])) == nil, "nothing installed")
        print(failures == 0 ? "asr-registry: ok" : "asr-registry: \(failures) failed")
        exit(failures == 0 ? 0 : 1)
    }
}
