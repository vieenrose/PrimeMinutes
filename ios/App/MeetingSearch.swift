import Foundation

/// The in-meeting search (docs/design/in-session-search-ask.md, Android MeetingSearch): the open meeting's
/// AI notes and transcript lines matching the query, notes first, then lines, each group in meeting order.
/// Keyword matches are found in memory; passages found by meaning (LibrarySearch.dense on this one meeting)
/// are merged in, marked `semanticOnly`. Plain inputs, so the host tests compile it alone.
enum MeetingSearch {
    static let maxResults = 50
    /// Passages asked from the dense pass over one meeting.
    static let densePassages = 20

    enum Kind: Equatable { case note, line }
    struct Line { let start: Double, text: String, speaker: Int }
    struct NoteIn { let ts: String, text: String }
    /// A dense passage of this meeting: `note` (uttIndex = note index) or a chunk (uttIndex...uttEnd).
    struct Dense { let note: Bool, uttIndex: Int, uttEnd: Int, timeSec: Double, snippet: String }
    /// `line`: the transcript line a tap jumps to (for a note, the line at its time; -1 when none).
    struct Result: Equatable { let kind: Kind, line: Int, timeSec: Double, text: String; var speaker: Int? = nil; var semanticOnly = false }

    static func seconds(_ ts: String) -> Double {
        let p = ts.split(separator: ":").compactMap { Double($0) }
        return p.count == 3 ? p[0] * 3600 + p[1] * 60 + p[2] : p.count == 2 ? p[0] * 60 + p[1] : 0
    }

    /// The index of the last line starting at or before `t`, -1 for none.
    static func lineAt(_ lines: [Line], _ t: Double) -> Int {
        guard !lines.isEmpty else { return -1 }
        return max(0, lines.lastIndex { $0.start <= t + 0.01 } ?? 0)
    }

    static func results(_ query: String, lines: [Line], notes: [NoteIn], dense: [Dense] = []) -> [Result] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        var noteHits = notes.filter { $0.text.localizedCaseInsensitiveContains(q) }.map { n -> Result in
            let t = seconds(n.ts); return Result(kind: .note, line: lineAt(lines, t), timeSec: t, text: n.text)
        }
        var lineHits = lines.indices.filter { lines[$0].text.localizedCaseInsensitiveContains(q) }.map {
            Result(kind: .line, line: $0, timeSec: lines[$0].start, text: lines[$0].text, speaker: lines[$0].speaker)
        }
        for p in dense {
            if p.note {
                if !p.snippet.isEmpty, !noteHits.contains(where: { $0.text == p.snippet }) {
                    noteHits.append(Result(kind: .note, line: lineAt(lines, p.timeSec), timeSec: p.timeSec, text: p.snippet, semanticOnly: true))
                }
            } else if lines.indices.contains(p.uttIndex), !lineHits.contains(where: { (p.uttIndex...max(p.uttIndex, p.uttEnd)).contains($0.line) }) {
                let u = lines[p.uttIndex]
                lineHits.append(Result(kind: .line, line: p.uttIndex, timeSec: u.start, text: u.text, speaker: u.speaker, semanticOnly: true))
            }
        }
        let sortedNotes = noteHits.enumerated().sorted { $0.element.timeSec != $1.element.timeSec ? $0.element.timeSec < $1.element.timeSec : $0.offset < $1.offset }.map(\.element)
        let sortedLines = lineHits.sorted { $0.line < $1.line }
        return Array((sortedNotes + sortedLines).prefix(maxResults))
    }

    /// The lines the bar's stepper walks: the line results, in transcript order.
    static func stepLines(_ rs: [Result], semantic: Bool) -> [Int] {
        Array(Set(rs.filter { $0.kind == .line && $0.line >= 0 && (semantic || !$0.semanticOnly) }.map(\.line))).sorted()
    }

    /// A long line cut so the match shows in a two-line row: from a little before the first match.
    static func snippet(_ text: String, _ query: String, before: Int = 24) -> String {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, let r = text.range(of: q, options: .caseInsensitive) else { return text }
        let i = text.distance(from: text.startIndex, to: r.lowerBound)
        return i <= before ? text : "…" + String(text[text.index(r.lowerBound, offsetBy: -before)...])
    }

    /// A token count without a tokenizer (Android SessionIndex.approxTokens): one per Han/kana/hangul
    /// character, 4/3 per other word — to chunk a meeting that has no index yet.
    static func approxTokens(_ text: String) -> Int {
        var n = 0, words = 0, inWord = false
        for u in text.unicodeScalars {
            let v = u.value
            let cjk = (0x3040...0x30FF).contains(v) || (0x3400...0x4DBF).contains(v) || (0x4E00...0x9FFF).contains(v) ||
                (0xAC00...0xD7AF).contains(v) || (0xF900...0xFAFF).contains(v) || (0x20000...0x2FA1F).contains(v)
            if cjk { n += 1; inWord = false }
            else if u.properties.isAlphabetic || u.properties.numericType != nil { if !inWord { words += 1 }; inWord = true }
            else { inWord = false }
        }
        return n + (words * 4 + 2) / 3
    }
}

/// The questions answered by the in-meeting Ask (Android AskHistory), `sessions/<id>.askhistory` (JSON; not
/// `.json`, which the library reads as sessions): the last `max`, shown under "Earlier" when the sheet opens
/// again. Local only — not exported, deleted with the meeting.
enum AskHistory {
    static let max = 20
    struct Item: Codable, Equatable {
        let q: String, a: String, t: Double
        /// Each excerpt of the answer as [whole second of its tag, exact start].
        let x: [[Double]]
    }
    private struct File: Codable { var v = 1; var items: [Item] }

    static func url(_ id: UUID, root: URL? = nil) -> URL {
        (root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("sessions"))
            .appendingPathComponent(id.uuidString + ".askhistory")
    }
    static func encode(_ items: [Item]) -> Data { (try? JSONEncoder().encode(File(items: items))) ?? Data() }
    /// Tolerant: a broken file is an empty history.
    static func decode(_ d: Data) -> [Item] { ((try? JSONDecoder().decode(File.self, from: d))?.items ?? []).filter { !$0.q.isEmpty } }
    static func load(_ id: UUID, root: URL? = nil) -> [Item] { (try? Data(contentsOf: url(id, root: root))).map(decode) ?? [] }
    private static let lock = NSLock()
    static func append(_ id: UUID, _ item: Item, root: URL? = nil) {
        lock.lock(); defer { lock.unlock() }
        let items = Array((load(id, root: root) + [item]).suffix(max))
        try? encode(items).write(to: url(id, root: root), options: .atomic)
    }
    static func clear(_ id: UUID, root: URL? = nil) { lock.lock(); try? FileManager.default.removeItem(at: url(id, root: root)); lock.unlock() }
}
