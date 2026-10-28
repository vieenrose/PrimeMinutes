// macOS host test: PartPlan picks the request for a model's .part and folds a finished background transfer into it.
import Foundation

@main struct BackgroundDownloadTests {
    static func main() throws {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }

        check(PartPlan.of(have: 0, size: 10), .fresh, "no part")
        check(PartPlan.of(have: 4, size: 10), .range(4), "partial part resumes")
        check(PartPlan.of(have: 10, size: 10), .verify, "whole part (finished in background) is verified, not re-requested")
        check(PartPlan.of(have: 11, size: 10), .discard, "oversized part")

        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("bgdl-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let part = dir.appendingPathComponent("f.part"), tmp = dir.appendingPathComponent("t")
        try Data("abcd".utf8).write(to: part); try Data("efgh".utf8).write(to: tmp)
        try PartPlan.merge(tmp, into: part, status: 206)
        check(try String(contentsOf: part, encoding: .utf8), "abcdefgh", "206 appends")
        check(FileManager.default.fileExists(atPath: tmp.path), false, "temp consumed")
        try Data("full".utf8).write(to: tmp)
        try PartPlan.merge(tmp, into: part, status: 200)
        check(try String(contentsOf: part, encoding: .utf8), "full", "200 replaces")
        let fresh = dir.appendingPathComponent("g.part"); try Data("xy".utf8).write(to: tmp)
        try PartPlan.merge(tmp, into: fresh, status: 206)
        check(try String(contentsOf: fresh, encoding: .utf8), "xy", "206 with no part creates it")

        print(failures == 0 ? "background-download tests PASS" : "\(failures) FAILED"); exit(failures == 0 ? 0 : 1)
    }
}
