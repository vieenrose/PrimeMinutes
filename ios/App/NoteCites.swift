import Foundation

/// Cite placement shared by the exports (was part of MeetingMail, removed with the mail templates in share v2):
/// trivial-item filter, `- text [m:ss]` lines, and design B (the cite moved to the clause end, glued).
enum NoteCites {
    struct Item: Equatable { var text: String; var ts: String? }

    static let tsRe = try! NSRegularExpression(pattern: #"\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)\]"#)
    private static let itemRe = try! NSRegularExpression(pattern: #"^-\s*(.*?)\s*(?:\[(\d+:\d{2}(?::\d{2})?)\])?\s*$"#)
    private static let empty: Set<String> = ["無", "无", "None", "-", ""]
    /// An item with nothing to say: an empty marker, or no letter at all (only digits / punctuation, e.g. "3").
    static func trivial(_ t: String) -> Bool { empty.contains(t) || !t.contains(where: \.isLetter) }

    /// One raw `- text [m:ss]` line (the minutes / action-items format); nil when it does not match.
    static func itemLine(_ line: String) -> Item? {
        guard let g = groups(itemRe, line.trimmingCharacters(in: .whitespaces)) else { return nil }
        return Item(text: g[1] ?? "", ts: g[2].flatMap { $0.isEmpty ? nil : $0 })
    }

    private static func groups(_ re: NSRegularExpression, _ s: String) -> [String?]? {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)), m.range.location == 0, m.range.length == ns.length else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? nil : ns.substring(with: m.range(at: $0)) }
    }


    // MARK: design B: cites at the clause end

    private static let wj = "\u{2060}", term: Set<Character> = ["。", "！", "？", "!", "?"], closers: Set<Character> = ["」", "』", "）", ")", "\"", "'"]
    private static func isHan(_ c: Character?) -> Bool {
        guard let v = c?.unicodeScalars.first?.value else { return false }
        return (0x3400...0x9FFF).contains(v) || (0x3000...0x303F).contains(v) || (0xFF00...0xFFEF).contains(v)
    }
    /// The glue before a trailing cite: U+2060 after Han / fullwidth text (never a space, never a break), a no-break
    /// space after Latin text — the `[…]` never wraps alone to the next line.
    private static func glue(_ before: String) -> String { isHan(before.last) ? wj : "\u{00A0}" }

    /// A minutes item's trailing cite: `…條款。⁠[1:12]` (a Han item gets its full stop back).
    static func itemCite(_ text: String, _ ts: String?) -> String {
        guard let ts else { return "" }
        let stop = isHan(text.last) && !term.contains(text.last!) && !"」』）".contains(text.last!) ? "。" : ""
        return stop + glue(text + stop) + "[\(ts)]"
    }

    /// Sentences of `p`, each with its terminator and following whitespace. A `.` ends one only before whitespace /
    /// the end (not `3.5`); closing brackets/quotes stay with their sentence; nothing ends inside a `[…]`.
    private static func sentences(_ p: String) -> [String] {
        let c = Array(p)
        var out: [String] = [], start = 0, i = 0, open = false
        while i < c.count {
            if c[i] == "[" { open = true } else if c[i] == "]" { open = false }
            let ends = term.contains(c[i]) || (c[i] == "." && (i + 1 == c.count || c[i + 1].isWhitespace))
            if ends && !open {
                var j = i + 1
                while j < c.count && (term.contains(c[j]) || closers.contains(c[j])) { j += 1 }
                while j < c.count && c[j].isWhitespace { j += 1 }
                out.append(String(c[start..<j])); start = j; i = j; continue
            }
            i += 1
        }
        if start < c.count { out.append(String(c[start...])) }
        return out
    }

    private static let spacedTs = try! NSRegularExpression(pattern: "[ \\t]*" + tsRe.pattern)

    /// Design B for prose: keep the FIRST cite of each sentence (`[0:18, 0:35]` stays one cite), move it after the
    /// sentence's full stop, glued; drop an exact repeat of the previous sentence's cite. Bracket/separator shape
    /// untouched, so tsRe / the anchors / repairTimestamps still parse it. Same rule as Android.
    static func clauseEndCites(_ p: String) -> String {
        var out = "", last: String?
        let sens = sentences(p)
        for (si, sen) in sens.enumerated() {
            let ns = sen as NSString
            guard let m = tsRe.firstMatch(in: sen, range: NSRange(location: 0, length: ns.length)) else { out += sen; continue }
            let cite = ns.substring(with: m.range)
            let body = spacedTs.stringByReplacingMatches(in: sen, range: NSRange(location: 0, length: ns.length), withTemplate: "")
            var core = body; while core.last?.isWhitespace == true { core.removeLast() }
            let ws = String(body.dropFirst(core.count))
            var k = core.endIndex
            while k > core.startIndex, case let ch = core[core.index(before: k)], term.contains(ch) || ch == "." || closers.contains(ch) { k = core.index(before: k) }
            var head = String(core[..<k]); while head.last?.isWhitespace == true { head.removeLast() }
            let tail = String(core[k...])
            out += head + tail
            // A moved cite is followed by a space when its sentence had none and more text follows.
            if cite != last { out += glue(head + tail) + cite; if ws.isEmpty && si + 1 < sens.count { out += " " } }
            last = cite
            out += ws
        }
        return out
    }
}
