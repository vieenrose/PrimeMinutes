import Foundation

/// AskSelect (Android AskSelectTest): seeds, overlap merging, widening by neighbour chunks within the budget,
/// routing, citation dedupe. ios/tests/run.sh
@main struct AskSelectTests {
    nonisolated(unsafe) static var failures = 0
    static func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL \(what)") } }

    /// Utterance i of every meeting: "m<i>" said at 10·i s; chunks of 3 utterances overlapping by one (0...2, 2...4, ...).
    struct Src: AskSelect.Transcripts {
        func chunks(_ meeting: String) -> [ClosedRange<Int>] { (0..<10).map { $0 * 2...$0 * 2 + 2 } }
        func span(_ meeting: String, _ a: Int, _ b: Int) -> AskSelect.Item? {
            AskSelect.Item(meeting: meeting, sec: a * 10, at: Double(a * 10), text: (a...b).map { "m\($0)" }.joined(separator: " "), score: 0, a: a, b: b)
        }
    }
    static let src = Src()
    static func chunk(_ m: String, _ i: Int, _ score: Float) -> AskSelect.Item { var x = src.span(m, i * 2, i * 2 + 2)!; x.score = score; return x }
    static func note(_ m: String, _ sec: Int, _ text: String, _ score: Float) -> AskSelect.Item { AskSelect.Item(meeting: m, sec: sec, at: Double(sec), text: text, score: score) }
    static let words: (String) -> Int = { $0.split(separator: " ").count }
    static let h = AskSelect.headerTokens

    static func main() {
        let a = "A", b = "B"
        do {   // defaults keep the ranking within the budget; the same text twice is kept once
            let ranked = [chunk(a, 3, 0.9), chunk(b, 1, 0.8), note(a, 50, "n1", 0.7), note(b, 50, "n1", 0.6)]
            var p = AskSelect.Retrieval(); p.budgetTokens = 2 * (3 + h) + 1 + h
            let xs = AskSelect.select(ranked, p, tokens: words, src: src)
            check(xs.map { "\($0.meeting)@\($0.sec)" } == ["A@60", "B@20", "A@50"], "defaults \(xs.map { "\($0.meeting)@\($0.sec)" })")
        }
        do {   // overlapping chunks of a meeting merge
            let xs = AskSelect.select([chunk(a, 3, 0.9), chunk(a, 4, 0.8), chunk(a, 3, 0.1)], AskSelect.Retrieval(), tokens: words, src: src)
            check(xs.count == 1 && xs[0].a == 6 && xs[0].b == 10 && xs[0].sec == 60 && xs[0].text == "m6 m7 m8 m9 m10", "merge \(xs)")
        }
        do {   // widen: next chunk, then previous, while the budget lasts
            var p = AskSelect.Retrieval(); p.seeds = 2; p.widen = 1; p.budgetTokens = 2 * h + 5 + 7
            let xs = AskSelect.select([chunk(a, 3, 0.9), chunk(b, 6, 0.8)], p, tokens: words, src: src)
            check(xs.map { [$0.a, $0.b] } == [[4, 10], [12, 16]] && xs.map(\.sec) == [40, 120], "widen \(xs.map { [$0.a, $0.b] })")
        }
        do {   // widening merges excerpts that meet
            var p = AskSelect.Retrieval(); p.seeds = 2; p.widen = 1
            let xs = AskSelect.select([chunk(a, 1, 0.9), chunk(a, 3, 0.8)], p, tokens: words, src: src)
            check(xs.count == 1 && xs[0].a == 0 && xs[0].b == 8, "widen merge \(xs.map { [$0.a, $0.b] })")
        }
        do {   // no widening without transcripts
            var p = AskSelect.Retrieval(); p.widen = 2
            let xs = AskSelect.select([chunk(a, 3, 0.9)], p, tokens: words, src: nil)
            check(xs.map { [$0.a, $0.b] } == [[6, 8]], "no src")
        }
        do {   // meetings ranked by their best passages in both lists
            let dense: [(String, [Float])] = [("A", [0.9, 0.5, 0.5]), ("B", [0.8, 0.8, 0.8]), ("C", [0.6])]
            let kw: [(String, [Float])] = [("C", [9, 8]), ("B", [6, 5, 4]), ("A", [3])]
            check(AskSelect.routeMeetings(dense: dense, keyword: kw, 1) == ["B"], "route 1")
            check(AskSelect.routeMeetings(dense: dense, keyword: kw, 2) == ["B", "C"], "route 2")
            check(AskSelect.routeMeetings(dense: dense, keyword: kw, 0).isEmpty, "route 0")
            check(AskSelect.routeMeetings(dense: [("A", [])], keyword: kw, 1) == ["C"], "summary only")
        }
        do {   // repeated citations collapse
            let x = "[[0A1B@879]]", y = "[[0A1C@12]]"
            check(AskSelect.dedupeTags("罰款兩百元\(x), \(x)。") == "罰款兩百元\(x)。", "dedupe 1")
            check(AskSelect.dedupeTags("A \(x), \(y), \(x).") == "A \(x), \(y).", "dedupe 2")
            check(AskSelect.dedupeTags("A \(x). B \(x).") == "A \(x). B \(x).", "dedupe 3")
            check(AskSelect.dedupeTags("A \(x)\(x)") == "A \(x)", "dedupe 4")
        }
        print(failures == 0 ? "ask-select: PASS" : "ask-select: \(failures) FAILED")
        exit(failures == 0 ? 0 : 1)
    }
}
