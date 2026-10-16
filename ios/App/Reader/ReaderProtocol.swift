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

    /// How far (seconds) a cited time may be from a citable one to be moved onto it (`repairTimestamps`).
    static let snapS = 30
    /// A bracket holding only digits and separators: a timestamp tag, well-formed or not.
    private static let tsTag = rx(#"(?<!\[)\[([0-9:：\s,，、;；\-–~～]*\d[0-9:：\s,，、;；\-–~～]*)\](?!\])"#)
    private static let tsSep = rx(#"[\s,，、;；\-–~～]+"#)

    /// The timestamp tags of model-written text checked against the times it may cite (`known`, as the notes
    /// write them): a tag naming a known time stays; one within `snapS` seconds of a known time is moved onto
    /// it; anything else is dropped. Covers `[5:6]`, lists and ranges (`[8:03, 11:04, 7]`, each item on its
    /// own) and bare numbers (`[21]`, dropped). Other brackets are left alone. Android `ReaderProtocol.repairTimestamps`.
    static func repairTimestamps(_ text: String, known: [String], snapS: Int = ReaderProtocol.snapS) -> String {
        var bySec: [Int: String] = [:]
        for k in known { if let t = parseTs(k), bySec[t] == nil { bySec[t] = k } }
        let keys = bySec.keys.sorted()
        let ns = text as NSString
        var out = "", last = 0
        for m in tsTag.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let inner = replace(tsSep, ns.substring(with: m.range(at: 1)).replacingOccurrences(of: "：", with: ":"), " ")
            var tags: [String] = []
            for item in inner.split(separator: " ").map(String.init) {
                guard let spans = tsSpans(item) else { continue }
                // The known time closest to any span (the earliest on a tie), if within snapS.
                var best: String?, bestD = Int.max
                for k in keys {
                    let d = spans.map { r in r.contains(k) ? 0 : min(abs(k - r.lowerBound), abs(k - r.upperBound)) }.min()!
                    if d < bestD { bestD = d; best = bySec[k] }
                }
                if let b = best, bestD <= snapS, !tags.contains(b) { tags.append(b) }
            }
            out += tags.map { "[\($0)]" }.joined(separator: " ")
            last = m.range.location + m.range.length
        }
        return out + ns.substring(from: last)
    }

    /// The second ranges a cited item can mean: `m:ss`/`h:mm:ss` exactly; `m:s` m:0s, or a cut-off m:s0–m:s9
    /// when that is a time. Nil for a bare number or nonsense.
    private static func tsSpans(_ item: String) -> [ClosedRange<Int>]? {
        let p = item.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard p.allSatisfy({ !$0.isEmpty && $0.count <= 3 && $0.unicodeScalars.allSatisfy { $0.value >= 48 && $0.value <= 57 } }) else { return nil }
        let n = p.map { Int($0)! }
        if p.count == 2 && p[1].count == 2 && n[1] < 60 { let t = n[0] * 60 + n[1]; return [t...t] }
        if p.count == 2 && p[1].count == 1 {
            let t = n[0] * 60 + n[1], c = n[0] * 60 + n[1] * 10
            return n[1] <= 5 ? [t...t, c...(c + 9)] : [t...t]
        }
        if p.count == 3 && p[1].count == 2 && p[2].count == 2 && n[1] < 60 && n[2] < 60 { let t = n[0] * 3600 + n[1] * 60 + n[2]; return [t...t] }
        return nil
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

/// The language a meeting is held in, from its transcript's script (Android `MeetingLanguage`): Han/kana
/// characters against Latin words, a word weighing one character, so a Chinese meeting with English terms
/// stays `zh`. The model card says what changes for a language (`ModelCard.forLanguage`).
struct MeetingLanguage {
    private var cjk = 0, words = 0
    static let minUnits = 20

    static func isCjk(_ c: UInt32) -> Bool {
        (0x3400...0x4DBF).contains(c) || (0x4E00...0x9FFF).contains(c) || (0xF900...0xFAFF).contains(c) || (0x20000...0x2FFFF).contains(c) ||
            (0x3040...0x30FF).contains(c) || (0x31F0...0x31FF).contains(c) || (0xFF66...0xFF9D).contains(c)
    }

    /// Count one more piece of transcript.
    mutating func add(_ text: String) {
        var inWord = 0
        for u in text.unicodeScalars {
            let c = u.value
            if Self.isCjk(c) { cjk += 1 }
            if (65...90).contains(c) || (97...122).contains(c) { inWord += 1 } else { if inWord >= 2 { words += 1 }; inWord = 0 }
        }
        if inWord >= 2 { words += 1 }
    }

    /// `zh`, `en`, or nil while there is too little text to tell.
    var code: String? { cjk + words < Self.minUnits ? nil : (words > cjk ? "en" : "zh") }

    static func detect(_ texts: [String]) -> String? { var m = MeetingLanguage(); texts.forEach { m.add($0) }; return m.code }
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
