import Foundation

/// One speaker talking without a pause comes out of the engine as a single utterance, minutes long: an unreadable
/// wall of text whose every summary link seeks to the same second. Cuts such an utterance at sentence ends; with the
/// engine's token times each piece starts at its first token, else cut times are estimated from character counts.
/// Port of the Android LongUtteranceSplitter.
enum LongUtteranceSplitter {
    private static let minSec = 20.0, minChars = 100, chunkChars = 90   // a piece closes at the first sentence end past chunkChars
    private static let sentenceEnd: Set<Character> = ["。", "？", "！", "?", "!", ".", "；", ";"]

    static func split(_ list: [Utterance]) -> [Utterance] {
        func long(_ u: Utterance) -> Bool { u.end - u.start > minSec && u.text.count > minChars }
        guard list.contains(where: long) else { return list }
        var out: [Utterance] = []
        for u in list {
            let parts = long(u) ? pieces(u.text) : [u.text]
            if parts.count <= 1 { out.append(u); continue }
            if let timed = timedPieces(u, parts) {   // the engine's token times: each piece starts at its first token
                for (i, tp) in timed.enumerated() {
                    let start = tp.1[0], end = i + 1 < timed.count ? timed[i + 1].1[0] : u.end
                    var p = Utterance(speaker: u.speaker, start: start, end: end, text: tp.0)
                    p.tokenTimes = TokenTimes.relative(tp.1, start)
                    out.append(p)
                }
                continue
            }
            let total = Double(parts.reduce(0) { $0 + $1.count })
            var at = u.start, used = 0
            for (i, p) in parts.enumerated() {
                used += p.count
                let end = i == parts.count - 1 ? u.end : u.start + (u.end - u.start) * Double(used) / total
                out.append(Utterance(speaker: u.speaker, start: at, end: end, text: p.trimmingCharacters(in: .whitespaces)))
                at = end
            }
        }
        return out
    }

    /// (trimmed text, absolute token starts) per piece when `u` carries token times that match its text, else nil.
    private static func timedPieces(_ u: Utterance, _ parts: [String]) -> [(String, [Double])]? {
        guard let tt = u.tokenTimes, parts.joined() == u.text else { return nil }
        let toks = TokenTimes.tokenize(u.text)
        guard tt.count == toks.count else { return nil }
        var scalarTime: [Double] = []   // time of the token each unicode scalar of the text belongs to
        for (k, t) in toks.enumerated() { for _ in t.unicodeScalars { scalarTime.append(u.start + tt[k]) } }
        guard scalarTime.count == u.text.unicodeScalars.count else { return nil }
        var res: [(String, [Double])] = [], pos = 0
        for p in parts {
            let lead = p.unicodeScalars.prefix { CharacterSet.whitespaces.contains($0) }.count
            let text = p.trimmingCharacters(in: .whitespaces)
            var off = pos + lead
            let times = TokenTimes.tokenize(text).map { t -> Double in defer { off += t.unicodeScalars.count }; return scalarTime[min(off, scalarTime.count - 1)] }
            if !text.isEmpty, !times.isEmpty { res.append((text, times)) }
            pos += p.unicodeScalars.count
        }
        return res.count >= 2 ? res : nil
    }

    private static func pieces(_ text: String) -> [String] {
        let cs = Array(text)
        var res: [String] = [], cur = ""
        for (i, c) in cs.enumerated() {
            cur.append(c)
            // ASCII '.' only ends a sentence when followed by a space (not 3.14)
            let ends = sentenceEnd.contains(c) && (c != "." || i + 1 >= cs.count || cs[i + 1] == " ")
            if ends && cur.count >= chunkChars { res.append(cur); cur = "" }
        }
        if !cur.trimmingCharacters(in: .whitespaces).isEmpty {
            if !res.isEmpty && cur.count < chunkChars / 3 { res[res.count - 1] += cur } else { res.append(cur) }
        }
        return res.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }
}
