import Foundation

/// Android MeetingNotes: the v2 structured NOTES format of the older summarizer (`TITLE:` inline, then
/// `SUMMARY:` / `DECISIONS:` / `ACTIONS:` / `OPEN:` / `TOPICS:` with `- ` bullets, an empty section being one `-`).
/// Sessions written by it carry this text as their notes; the summary tab shows its decisions, open questions,
/// topics and unknown sections as cards. The keys are wire format: the app shows its own headings.
struct MeetingNotes {
    var title = ""
    var decisions: [String] = [], open: [String] = [], topics: [String] = []
    struct Section: Identifiable { let key: String; let lines: [String]; var id: String { key } }
    /// Sections with keys we do not know, in order (a later model's RISKS: is shown, not dropped).
    var extra: [Section] = []

    private static let key = try! NSRegularExpression(pattern: #"^([A-Z][A-Z_]*):[ \t]*(.*)$"#)
    private static let bullet = try! NSRegularExpression(pattern: #"^\s*(?:[-*•·]|\d+[.)])\s+(.*)$"#)
    private static let provenance = try! NSRegularExpression(pattern: #"\s*\[c\d+\]"#)
    private static let range = try! NSRegularExpression(pattern: #"\s*\[\d+:\d{2}(?::\d{2})?\s*[-–—]\s*\d+:\d{2}(?::\d{2})?\]"#)

    private static func groups(_ re: NSRegularExpression, _ s: String) -> [String]? {
        guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (0..<m.numberOfRanges).map { Range(m.range(at: $0), in: s).map { String(s[$0]) } ?? "" }
    }
    private static func strip(_ re: NSRegularExpression, _ s: String) -> String {
        re.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")
    }

    /// Nil when `text` is not NOTES (an agent journal, or prose) or has nothing under its title.
    static func parse(_ text: String) -> MeetingNotes? {
        var sawKey = false, current: String?, title = ""
        var order: [String] = [], sections: [String: [String]] = [:]
        for raw in text.components(separatedBy: "\n") {
            let line = raw.replacingOccurrences(of: #"\s+$"#, with: "", options: .regularExpression)
            // A key counts only at the true start of a line; an indented "NOTE:" in a bullet is content.
            if line.first?.isWhitespace != true, let g = groups(key, line) {
                sawKey = true
                let k = g[1], inline = g[2].trimmingCharacters(in: .whitespaces)
                if k == "TITLE" { title = inline; current = nil; continue }
                current = k
                if sections[k] == nil { sections[k] = []; order.append(k) }
                if !inline.isEmpty && inline != "-" { sections[k]!.append(inline) }   // first item on the key's line
                continue
            }
            guard let cur = current else { continue }
            let bare = line.trimmingCharacters(in: .whitespaces)
            if bare.isEmpty { continue }
            // Empty-section markers: "-", and the "- -" / en-dash variants small models emit.
            if bare.count == 1, "-*•·–—".contains(bare) { continue }
            if ["- -", "- –", "- —", "-  -"].contains(bare) { continue }
            var item = groups(bullet, line)?[1] ?? line
            // Keep a [m:ss] anchor; drop provenance tags [cN] and range anchors, which are not seekable positions.
            item = strip(range, strip(provenance, item)).trimmingCharacters(in: .whitespaces)
            if !item.isEmpty { sections[cur]!.append(item) }
        }
        guard sawKey else { return nil }
        let known: Set<String> = ["SUMMARY", "DECISIONS", "ACTIONS", "OPEN", "TOPICS"]
        let n = MeetingNotes(title: title, decisions: sections["DECISIONS"] ?? [], open: sections["OPEN"] ?? [], topics: sections["TOPICS"] ?? [],
                             extra: order.filter { !known.contains($0) }.map { Section(key: $0, lines: sections[$0] ?? []) })
        let body = ["SUMMARY", "DECISIONS", "ACTIONS", "OPEN", "TOPICS"].contains { !(sections[$0] ?? []).isEmpty } || !n.extra.isEmpty
        return body ? n : nil
    }
}
