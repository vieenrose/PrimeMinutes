// macOS host test: MfaCompute serializes engines across threads, and ResumePlan prefers the complete transcript.
import Foundation

final class Counter: @unchecked Sendable {
    private let l = NSLock(); private var inside = 0, calls = 0
    private(set) var maxInside = 0
    func enter() { l.lock(); inside += 1; maxInside = max(maxInside, inside); l.unlock() }
    func leave() { l.lock(); inside -= 1; calls += 1; l.unlock() }
    var total: Int { l.lock(); defer { l.unlock() }; return calls }
}

@main struct EngineAndResumeTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }

        // MfaCompute: two threads (two engines) never compute together
        let c = Counter(), group = DispatchGroup()
        for _ in 0..<2 {
            group.enter()
            Thread { for _ in 0..<200 { MfaCompute.run { c.enter(); usleep(200); c.leave() } }; group.leave() }.start()
        }
        group.wait()
        check(c.total, 400, "every compute ran"); check(c.maxInside, 1, "never two at once")
        check(MfaCompute.run { MfaCompute.run { 42 } }, 42, "value returned, reentrant")

        // ResumePlan
        func kind(_ p: ResumePlan<[Int], String>) -> String {
            switch p { case .readerOnly(let t): return "reader \(t)"; case .afterSeam(let s): return "seam \(s)"; case .fromStart: return "start" }
        }
        check(kind(.decide(full: [1, 2], partial: "p")), "reader [1, 2]", "complete transcript wins over the partial")
        check(kind(.decide(full: [1], partial: nil)), "reader [1]", "complete transcript alone")
        check(kind(.decide(full: [], partial: "p")), "reader []", "an empty complete transcript (no speech) still skips ASR")
        check(kind(.decide(full: nil, partial: "p")), "seam p", "partial only: after the seam")
        check(kind(.decide(full: nil, partial: nil)), "start", "nothing saved")
        check(ResumePlan<[Int], String>.decide(full: [1], partial: "p").partial, nil, "no partial handed on when complete")
        check(ResumePlan<[Int], String>.decide(full: nil, partial: "p").partial, "p", "partial handed on")

        print(failures == 0 ? "engine+resume tests: all passed" : "engine+resume tests: \(failures) FAILED")
        exit(failures == 0 ? 0 : 1)
    }
}
