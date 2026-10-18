import Foundation

/// Android SpeakerRefs: the reader writes speakers as S1, S2 (speaker id n is "S{n+1}") and those stay in what is saved;
/// what is shown names them as the transcript does, so a later rename reaches the summary too.
enum SpeakerRefs {
    private static let ref = try! NSRegularExpression(pattern: #"(?<![A-Za-z0-9_])S(\d{1,2})(?![A-Za-z0-9_])"#)
    private static func han(_ c: Character) -> Bool { c.unicodeScalars.first.map { (0x3400...0x9FFF).contains($0.value) || (0xF900...0xFAFF).contains($0.value) } ?? false }

    /// `known`: speaker ids in the transcript; an S-number with no such speaker (a model slip) is left as written.
    static func resolve(_ text: String, label: (Int) -> String, known: Set<Int>?, wrap: (String) -> String = { $0 }) -> String {
        var out = "", last = text.startIndex
        for m in ref.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let r = Range(m.range, in: text), let g = Range(m.range(at: 1), in: text), let n = Int(text[g]) else { continue }
            let id = n - 1
            out += text[last..<r.lowerBound]; last = r.upperBound
            if id < 0 || (known.map { !$0.contains(id) } ?? false) { out += text[r]; continue }
            let name = label(id)
            // Chinese takes no space before a Chinese name: "而 S1 則" → "而語者 1 則".
            if out.hasSuffix(" "), out.count >= 2, han(out[out.index(out.endIndex, offsetBy: -2)]), let f = name.first, han(f) { out.removeLast() }
            out += wrap(name)
            // A name ending in a digit or Latin letter runs into the Chinese after it: "語者 1將" → "語者 1 將".
            if let l = name.last, l.isLetter || l.isNumber, !han(l), r.upperBound < text.endIndex, han(text[r.upperBound]) { out += " " }
        }
        return out + text[last...]
    }
    /// A name as one unit on screen: it never wraps between "語者" and its number.
    static func unbreakable(_ s: String) -> String { s.replacingOccurrences(of: " ", with: "\u{00A0}") }

    /// A meeting's speakers as `resolve` takes them: the label of an id, and the ids present (nil: any).
    struct Labels { let label: (Int) -> String; let known: Set<Int>? }

    /// Android SpeakerRefs.resolveCited: `resolve` for text citing several meetings (an Ask answer). Each S-number belongs
    /// to the meeting of the citation that follows it (the last one for a tail after the last citation); `labels` maps a
    /// citation match to its meeting's speakers, or nil to leave that part as written.
    static func resolveCited(_ text: String, cite: NSRegularExpression, labels: (NSTextCheckingResult) -> Labels?) -> String {
        let ns = text as NSString
        let cites = cite.matches(in: text, range: NSRange(location: 0, length: ns.length))
        if cites.isEmpty || ref.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) == nil { return text }
        func segment(_ t: String, _ l: Labels?) -> String { l.map { resolve(t, label: $0.label, known: $0.known) } ?? t }
        var out = "", last = 0
        for (i, m) in cites.enumerated() {
            let l = labels(m)
            out += segment(ns.substring(with: NSRange(location: last, length: m.range.location - last)), l)
            out += ns.substring(with: m.range)
            last = m.range.location + m.range.length
            if i == cites.count - 1 { out += segment(ns.substring(from: last), l) }
        }
        return out
    }
}
