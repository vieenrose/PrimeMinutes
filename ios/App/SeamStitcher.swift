import Foundation

enum SeamStitcher {
    static let preroll = 30.0
    static func stitch(_ prior: [Utterance], seam: Double, _ fresh: [Utterance], stable: Int) -> ([Utterance], Int) {
        if prior.isEmpty { return (fresh, stable) }
        var overlap: [[Int]: Double] = [:]
        for f in fresh where f.start < seam { for p in prior { let o = min(f.end, p.end) - max(f.start, p.start); if o > 0 { overlap[[f.speaker, p.speaker], default: 0] += o } } }
        var map: [Int: Int] = [:], taken = Set<Int>()
        for (k, _) in overlap.sorted(by: { $0.value > $1.value }) where map[k[0]] == nil && !taken.contains(k[1]) { map[k[0]] = k[1]; taken.insert(k[1]) }
        var next = (prior.map(\.speaker).max() ?? -1) + 1
        var kept: [Utterance] = [], keptStable = 0
        for (i, f) in fresh.enumerated() where (f.start + f.end) / 2 >= seam {
            // A line straddling the seam re-recognises the words that close the prior transcript
            // ("…生效。很好，客戶" + "很好，客戶通知信…"): drop that repeated head (Android 5ef0592c).
            var u = f.start < seam && kept.isEmpty ? trimRepeatedHead(prior[prior.count - 1].text, f) : f
            if u.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            if let m = map[f.speaker] { u.speaker = m } else { map[f.speaker] = next; u.speaker = next; next += 1 }
            kept.append(u); if i < stable { keptStable += 1 }
        }
        return (prior + kept, prior.count + keptStable)
    }

    /// `f` without its longest head (at least 2 characters) that repeats the end of `priorText`.
    static func trimRepeatedHead(_ priorText: String, _ f: Utterance) -> Utterance {
        let full = Array(f.text), lead = full.prefix { $0.isWhitespace }.count
        let text = Array(full.dropFirst(lead))
        var tail = Array(priorText); while let l = tail.last, l.isWhitespace { tail.removeLast() }
        // Latin words must match whole ("upon" does not end with the word "on").
        func latin(_ c: Character?) -> Bool { guard let c, let s = c.unicodeScalars.first else { return false }; return s.value < 0x2E80 && (c.isLetter || c.isNumber) }
        func ok(_ k: Int) -> Bool {
            Array(tail.suffix(k)) == Array(text.prefix(k))
                && !(latin(tail.count - k - 1 >= 0 ? tail[tail.count - k - 1] : nil) && latin(text[0]))
                && !(latin(k < text.count ? text[k] : nil) && latin(text[k - 1]))
        }
        var k = min(text.count, tail.count)
        while k >= 2 && !ok(k) { k -= 1 }
        if k < 2 { return f }
        var u = f
        u.text = String(text.dropFirst(k).drop { $0.isWhitespace })
        u.tokenTimes = nil
        // Keep the token times (one per TokenTimes.tokenize token, trailing spaces attached) when the head ends on a token boundary.
        if let times = f.tokenTimes {
            let toks = TokenTimes.tokenize(f.text)
            if toks.count == times.count {
                var len = 0, n = 0
                while n < toks.count && len < lead + k { len += toks[n].count; n += 1 }
                if len >= lead + k, full[(lead + k)..<len].allSatisfy(\.isWhitespace), n < toks.count, TokenTimes.tokenize(u.text).count == toks.count - n {
                    let shift = times[n]; u.start = f.start + shift; u.tokenTimes = times.dropFirst(n).map { $0 - shift }
                }
            }
        }
        return u
    }
}
