import Foundation

/// Transcript tokens and their start times — the unit the speaker-boundary editor moves (docs/design/speaker-boundary.md):
/// one Han character, or one Latin word, with the punctuation and spaces that follow it attached, so tokens concatenate
/// back to the exact text. The engine reports a start per codepoint of its raw segment text (nemo_c.cpp, 5th field);
/// `fromCodepoints` maps those onto the tokens the app keeps. Port of Android core/asr/TokenTimes.kt.
enum TokenTimes {
    static func isHan(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF, 0x20000...0x2FA1F, 0x3005, 0x3007: return true
        default: return false
        }
    }
    static func isWord(_ s: Unicode.Scalar) -> Bool {
        (CharacterSet.alphanumerics.contains(s) && !isHan(s)) || s == "'" || s == "\u{2019}" || s == "-"
    }

    /// Lossless tokenization: joined() == text.
    static func tokenize(_ text: String) -> [String] {
        var out: [String] = []
        let sc = Array(text.unicodeScalars)
        var i = 0
        while i < sc.count {
            let c = sc[i]
            if isHan(c) { out.append(String(c)); i += 1 }
            else if isWord(c) {
                var w = String.UnicodeScalarView()
                while i < sc.count, isWord(sc[i]) { w.append(sc[i]); i += 1 }
                out.append(String(w))
            } else {   // space / punctuation: attach to the previous token (or lead the first one)
                if out.isEmpty { out.append("") }
                out[out.count - 1].unicodeScalars.append(c); i += 1
            }
        }
        return out.filter { !$0.isEmpty }
    }

    /// Start (absolute s) of every token of `text`, from the engine's `raw` text and each raw codepoint's start.
    /// Aligned greedily on non-space codepoints with a short look-ahead; nil when they do not line up.
    static func fromCodepoints(raw: String, starts: [Double], text: String) -> [Double]? {
        let rc = Array(raw.unicodeScalars)
        guard rc.count == starts.count, !rc.isEmpty else { return nil }
        let ri = rc.indices.filter { !CharacterSet.whitespacesAndNewlines.contains(rc[$0]) }
        let toks = tokenize(text)
        guard !toks.isEmpty else { return nil }
        var firsts: [Double?] = []
        var j = 0, matched = 0, total = 0
        let look = 12
        for t in toks {
            var first: Double?
            for cp in t.unicodeScalars where !CharacterSet.whitespacesAndNewlines.contains(cp) {
                total += 1
                var k = j
                while k < ri.count, k < j + look, rc[ri[k]] != cp { k += 1 }
                if k < ri.count, k < j + look {
                    if first == nil { first = starts[ri[k]] }
                    j = k + 1; matched += 1
                }
            }
            firsts.append(first)
        }
        guard total > 0, matched * 2 >= total, let firstKnown = firsts.compactMap({ $0 }).first else { return nil }
        var res: [Double] = []
        var last: Double?
        for f in firsts { if let f { last = f }; res.append(last ?? firstKnown) }
        for i in res.indices.dropFirst() where res[i] < res[i - 1] { res[i] = res[i - 1] }
        return res
    }

    /// Relative token times (s after `start`) rounded to 10 ms: what is stored per line.
    static func relative(_ abs: [Double], _ start: Double) -> [Double] { abs.map { (($0 - start) * 100).rounded() / 100 } }
}

/// Pure ops for correcting where one speaker's turn ends and the next one's begins. Port of Android data/BoundaryEdits.kt.
enum BoundaryEdits {
    static func tokenize(_ text: String) -> [String] { TokenTimes.tokenize(text) }

    /// Absolute start of every token of `u`: the engine's times when they still describe this text, else spread by length.
    static func tokenStarts(_ u: Utterance, _ toks: [String]? = nil) -> [Double] {
        let toks = toks ?? tokenize(u.text)
        if let tt = u.tokenTimes, tt.count == toks.count { return tt.map { u.start + $0 } }
        let w = toks.map { Double(max(1, $0.unicodeScalars.filter { !CharacterSet.whitespacesAndNewlines.contains($0) }.count)) }
        let total = max(1, w.reduce(0, +)), span = max(0, u.end - u.start)
        var acc = 0.0
        return w.map { x in defer { acc += x }; return u.start + span * acc / total }
    }

    struct Seam { let tokens: [String]; let starts: [Double]; let split: Int; let end: Double }
    static func seam(_ a: Utterance, _ b: Utterance) -> Seam {
        let ta = tokenize(a.text), tb = tokenize(b.text)
        return Seam(tokens: ta + tb, starts: tokenStarts(a, ta) + tokenStarts(b, tb), split: ta.count, end: b.end)
    }

    /// True when `t` following text that ends in `l` needs a space: two Latin words meeting across the seam.
    private static func needsSpace(_ l: Unicode.Scalar, _ t: String) -> Bool {
        guard let f = t.unicodeScalars.first else { return false }
        return !CharacterSet.whitespacesAndNewlines.contains(l) && !TokenTimes.isHan(l) && !TokenTimes.isHan(f)
            && CharacterSet.alphanumerics.contains(f) && l != "-"
    }

    /// Concatenate tokens, adding the space two Latin words need when they meet across the seam.
    static func join(_ toks: ArraySlice<String>) -> String {
        var s = ""
        for t in toks {
            if let l = s.unicodeScalars.last, needsSpace(l, t) { s += " " }
            s += t
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The tokens as the boundary strip shows them: where `join` would add a space between two of them (A's "two,"
    /// meeting B's "right?" at the seam), the space ends the first, as a token's own trailing space does. The strip
    /// then reads like the text the move produces, not "two,right?". Android BoundaryEdits.displayTokens.
    static func displayTokens(_ toks: [String]) -> [String] {
        toks.indices.map { i in
            if i + 1 < toks.count, let l = toks[i].unicodeScalars.last, needsSpace(l, toks[i + 1]) { return toks[i] + " " }
            return toks[i]
        }
    }

    private static func withTimes(_ u: Utterance, _ starts: ArraySlice<Double>) -> Utterance {
        var u = u
        u.tokenTimes = tokenize(u.text).count == starts.count ? TokenTimes.relative(Array(starts), u.start) : nil
        return u
    }

    /// Move the boundary between lines seam-1 (A) and seam (B) to token `split` of their joined strip
    /// (0 < split < tokens). Speakers and the other lines are untouched; A.end = B.start = B's first token.
    static func move(_ lines: [Utterance], seam: Int, split: Int) -> [Utterance] {
        guard seam >= 1, seam < lines.count else { return lines }
        let a = lines[seam - 1], b = lines[seam], s = Self.seam(a, b)
        guard s.tokens.count >= 2 else { return lines }
        let k = min(max(split, 1), s.tokens.count - 1)
        if k == s.split { return lines }
        let t = s.starts[k]
        var na = a, nb = b
        na.text = join(s.tokens[0..<k]); na.end = t
        nb.text = join(s.tokens[k...]); nb.start = t
        var out = lines
        out[seam - 1] = withTimes(na, s.starts[0..<k]); out[seam] = withTimes(nb, s.starts[k...])
        return out
    }

    /// Who may say the rest of a split line, likeliest first: the neighbouring lines' speakers (next, then
    /// previous), then the other speakers, then a new speaker id. The line's own speaker is never offered.
    static func splitCandidates(_ lines: [Utterance], index: Int) -> [Int] {
        guard lines.indices.contains(index) else { return [] }
        let own = lines[index].speaker
        let ids = Array(Set(lines.map(\.speaker))).sorted()
        let near = [index + 1, index - 1].filter { lines.indices.contains($0) }.map { lines[$0].speaker }
        var out: [Int] = []
        for k in near + ids where k != own && !out.contains(k) { out.append(k) }
        return out + [(ids.max() ?? -1) + 1]
    }

    /// Split line `index` at token `at` (0 < at < tokens): the tail becomes a new line of `speaker` (a missed turn).
    static func split(_ lines: [Utterance], index: Int, at: Int, speaker: Int) -> [Utterance] {
        guard lines.indices.contains(index) else { return lines }
        let u = lines[index], toks = tokenize(u.text)
        guard at >= 1, at < toks.count else { return lines }
        let starts = tokenStarts(u, toks), t = starts[at]
        var head = u, tail = Utterance(speaker: speaker, start: t, end: u.end, text: join(toks[at...]))
        head.text = join(toks[0..<at]); head.end = t
        tail = withTimes(tail, starts[at...])
        var out = lines
        out[index] = withTimes(head, starts[0..<at]); out.insert(tail, at: index + 1)
        return out
    }
}
