import Foundation

/// Ask's choice of excerpts (Android AskEngine.select/routeMeetings/dedupeTags), free of app types so ios/tests can
/// run it. Meeting routing: the `routeMeetings` meetings ranked first by their passages give `routeDepth` candidates
/// per list instead of `perMeeting`, and their passages lead (near-duplicate meetings stole the slots of the one
/// asked about). Then `seeds` best passages (overlapping transcript passages of a meeting merge into one), each
/// widened `widen` times by the transcript chunk after it and the one before it while `budgetTokens` lasts.
enum AskSelect {
    /// Retrieval knobs, model-agnostic (Android AskEngine.Retrieval; same defaults).
    struct Retrieval: Sendable {
        /// budgetTokens: what 8 fused passages used to fill on the median (quality eval 2026-10, "Ask after fixes").
        var maxExcerpts = 8, budgetTokens = 800, perMeeting = 4, seeds = 8, widen = 0
        var routeMeetings = 2, routeDepth = 16
        /// The reciprocal-rank constant of the passage fusion.
        var rrfK: Float = 60
    }
    /// The tokens of an excerpt's header line (`[[k]] title · m:ss`) and spacing.
    static let headerTokens = 24

    /// One candidate excerpt: `meeting` identifies the meeting, `a`...`b` the utterances of a transcript passage (-1: a note).
    struct Item: Equatable, Sendable {
        var meeting: String, sec: Int, at: Double, text: String, score: Float, a = -1, b = -1
    }

    /// What select needs from the library.
    protocol Transcripts {
        /// A meeting's transcript chunks as indexed, in order.
        func chunks(_ meeting: String) -> [ClosedRange<Int>]
        /// The utterances a...b as one item (score 0), or nil.
        func span(_ meeting: String, _ a: Int, _ b: Int) -> Item?
    }

    static func select(_ ranked: [Item], _ p: Retrieval, tokens: (String) -> Int, src: Transcripts?) -> [Item] {
        var out: [Item] = [], cost: [Int] = [], used = 0
        func price(_ x: Item) -> Int { tokens(x.text) + headerTokens }
        /// Puts `x` in place of the items at `olds` (indices into out), if the budget allows.
        func replace(_ olds: [Int], _ x: Item) -> Bool {
            let n = price(x), freed = olds.reduce(0) { $0 + cost[$1] }
            if used - freed + n > p.budgetTokens { return false }
            let at = olds.min()!
            for i in olds.sorted(by: >) { out.remove(at: i); cost.remove(at: i) }
            out.insert(x, at: min(at, out.count)); cost.insert(n, at: min(at, cost.count)); used += n - freed
            return true
        }
        func widened(_ x: Item, _ a: Int, _ b: Int) -> Item? { src?.span(x.meeting, a, b).map { var y = $0; y.score = x.score; return y } }
        /// Indices of the excerpts of `m` overlapping or touching the utterances a...b.
        func touching(_ m: String, _ a: Int, _ b: Int) -> [Int] { out.indices.filter { out[$0].meeting == m && out[$0].a >= 0 && out[$0].a <= b + 1 && out[$0].b >= a - 1 } }
        for c in ranked {
            if out.count >= min(p.seeds, p.maxExcerpts) { break }
            // A note and its transcript chunk, or a meeting imported twice, can carry the same words: one excerpt is enough.
            if out.contains(where: { $0.text == c.text }) { continue }
            let near = c.a >= 0 ? touching(c.meeting, c.a, c.b) : []
            if !near.isEmpty {
                if near.count == 1, out[near[0]].a <= c.a, out[near[0]].b >= c.b { continue }   // already inside
                let a = min(c.a, near.map { out[$0].a }.min()!), b = max(c.b, near.map { out[$0].b }.max()!)
                if var w = widened(c, a, b) { w.score = near.map { out[$0].score }.max()!; _ = replace(near, w) }
                continue
            }
            let n = price(c)
            if used + n > p.budgetTokens { continue }
            out.append(c); cost.append(n); used += n
        }
        guard let src else { return out }
        for _ in 0..<p.widen {
            for dir in [1, -1] {
                for x in out {
                    guard let i = out.firstIndex(of: x), x.a >= 0 else { continue }
                    let cs = src.chunks(x.meeting)
                    let r = dir > 0 ? cs.first(where: { $0.upperBound > x.b && $0.lowerBound <= x.b + 1 })
                                    : cs.last(where: { $0.lowerBound < x.a && $0.upperBound >= x.a - 1 })
                    guard let r else { continue }
                    let a = min(x.a, r.lowerBound), b = max(x.b, r.upperBound)
                    let olds = [i] + touching(x.meeting, a, b).filter { $0 != i }
                    let a2 = min(a, olds.map { out[$0].a }.min()!), b2 = max(b, olds.map { out[$0].b }.max()!)
                    if let w = widened(x, a2, b2) { _ = replace(olds, w) }
                }
            }
        }
        return out
    }

    /// Meeting routing: the `n` meetings ranked first by reciprocal-rank fusion (k 60) of two meeting scores — the mean
    /// cosine of its 3 best passages (`dense`, summaries left out) and the sum of its 3 best BM25 scores (`keyword`), each
    /// list given in its meetings' order with their passage scores. Empty when n ≤ 0.
    static func routeMeetings(dense: [(String, [Float])], keyword: [(String, [Float])], _ n: Int) -> Set<String> {
        guard n > 0 else { return [] }
        var score: [String: Float] = [:], seen: [String: Int] = [:]
        func add(_ per: [(String, Float)]) {
            // Stable: ties keep the list order (Kotlin's sortedByDescending).
            for (r, e) in per.enumerated().sorted(by: { $0.element.1 != $1.element.1 ? $0.element.1 > $1.element.1 : $0.offset < $1.offset }).enumerated() {
                if seen[e.element.0] == nil { seen[e.element.0] = seen.count }
                score[e.element.0, default: 0] += 1 / (60 + Float(r + 1))
            }
        }
        add(dense.compactMap { m, ss in ss.isEmpty ? nil : (m, ss.sorted(by: >).prefix(3).reduce(0, +) / 3) })
        add(keyword.map { m, ss in (m, ss.sorted(by: >).prefix(3).reduce(0, +)) })
        return Set(score.sorted { $0.value != $1.value ? $0.value > $1.value : seen[$0.key]! < seen[$1.key]! }.prefix(n).map(\.key))
    }

    /// A run of citations naming the same passage twice (`[[x@879]], [[x@879]]`) keeps it once.
    static func dedupeTags(_ text: String) -> String {
        let tag = #"\[\[[^\[\]@]+@\d+\]\]"#
        let run = try! NSRegularExpression(pattern: tag + #"(?:[ \t]*[,，、;；]?[ \t]*"# + tag + ")+")
        let one = try! NSRegularExpression(pattern: tag)
        let ns = text as NSString
        var out = "", last = 0
        for m in run.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let s = ns.substring(with: m.range), sn = s as NSString
            let ts = one.matches(in: s, range: NSRange(location: 0, length: sn.length))
            let tags = ts.map { sn.substring(with: $0.range) }
            var seen = Set<String>(), distinct: [String] = []
            for t in tags where seen.insert(t).inserted { distinct.append(t) }
            if distinct.count == tags.count { out += s } else {
                let sepStart = ts[0].range.location + ts[0].range.length
                out += distinct.joined(separator: sn.substring(with: NSRange(location: sepStart, length: ts[1].range.location - sepStart)))
            }
            last = m.range.location + m.range.length
        }
        return out + ns.substring(from: last)
    }
}
