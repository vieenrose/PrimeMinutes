import Foundation

/// The meeting reader's transcript format, ported from the Android `ReaderProtocol.kt`, itself a port of
/// vieenrose/meeting-summarizer (`eval/phone_live.py`). `ReaderParityTests` replays the same goldens
/// as Android's `ReaderParityTest` and must stay identical.
enum ReaderProtocol {
    // The model-specific part (headers, reply grammar, caps, minutes) is the model card's: ReaderCard.

    static func formatTs(_ seconds: Int) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    static func parseTs(_ ts: String) -> Int? {
        let parts = ts.split(separator: ":", omittingEmptySubsequences: false).map { Int($0) }
        guard !parts.contains(nil) else { return nil }
        let p = parts.map { $0! }
        switch p.count {
        case 2: return p[0] * 60 + p[1]
        case 3: return p[0] * 3600 + p[1] * 60 + p[2]
        default: return nil
        }
    }

    private static let lineRx = rx(#"^\[(\d+:\d{2}(?::\d{2})?)\]\s*(.*)$"#)
    private static let speakerRx = rx(#"^(?:S\d+|[^\s:]{1,20})$"#)

    /// ingest.parse_line: `[ts] speaker: text`, speaker optional; nil for a non-transcript line.
    static func parseLine(_ raw: String) -> Line? {
        guard let m = groups(lineRx, raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              let start = parseTs(m[1]) else { return nil }
        let rest = m[2]
        if let r = rest.range(of: ": "), r.lowerBound > rest.startIndex {
            let who = String(rest[rest.startIndex..<r.lowerBound])
            if groups(speakerRx, who) != nil { return Line(startS: start, speaker: who, text: String(rest[r.upperBound...])) }
        }
        return Line(startS: start, speaker: nil, text: rest)
    }

    private static let nonSpeechTag = rx(#"\[[A-Za-z][A-Za-z _-]*\]"#)
    private static let filler = rx(#"(?:^|(?<=[，。！？、\s]))[嗯啊呃]+[，。、]?"#)
    private static let ws = rx(#"\s+"#)

    /// ingest.clean_text.
    static func cleanText(_ text: String) -> String {
        replace(ws, replace(filler, replace(nonSpeechTag, text, ""), ""), " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// realtime_agent.similar: character-bigram Jaccard (UTF-16 units, like the Kotlin).
    static func similar(_ a: String, _ b: String) -> Double {
        func bg(_ t: String) -> Set<UInt32> {
            let u = Array(t.utf16)
            return Set((0..<max(0, u.count - 1)).map { UInt32(u[$0]) << 16 | UInt32(u[$0 + 1]) })
        }
        let x = bg(a), y = bg(b)
        return Double(x.intersection(y).count) / Double(max(1, x.union(y).count))
    }

    /// realtime_agent.render.
    static func render(_ n: Note) -> String { "#\(n.id) [\(n.ts)] " + (n.tag.map { "(\($0)) " } ?? "") + n.text }
}

/// One transcript line as the model reads it. `speaker` is "S1", "S2", … or nil.
struct Line: Equatable {
    var startS: Int
    var speaker: String?
    var text: String
    func render() -> String { "[\(ReaderProtocol.formatTs(startS))] " + (speaker.map { "\($0): " } ?? "") + text }
}

/// A journal entry. `tag` is DECISION / ACTION / NUMBER / OPEN-ISSUE / "-" or nil.
struct Note: Equatable, Identifiable, Codable {
    var id: Int
    var window: Int
    var ts: String
    var tag: String?
    var text: String
}

struct ReaderBudget {
    var windowTokens: Int, ctxBudget: Int, restartBudget: Int
    /// phone_live.py's 8k protocol (the parity golden); the shipped card holds the mobile 4k one.
    static let standard = ReaderBudget(windowTokens: 2000, ctxBudget: 8192, restartBudget: 2500)
}

// MARK: regex helpers (NSRegularExpression: ICU, close to java.util.regex)

func rx(_ p: String) -> NSRegularExpression { try! NSRegularExpression(pattern: p) }

/// Capture groups of the first match (index 0 = whole match; unmatched groups = ""), or nil.
func groups(_ re: NSRegularExpression, _ s: String) -> [String]? {
    let ns = s as NSString
    guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
    return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : ns.substring(with: m.range(at: $0)) }
}
func find(_ re: NSRegularExpression, _ s: String) -> Bool { re.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil }
func replace(_ re: NSRegularExpression, _ s: String, _ with: String) -> String {
    re.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: with)
}
