// macOS host test: the in-meeting search and Ask history (same cases as Android MeetingSearchTest / AskHistoryTest).
import Foundation

@main struct MeetingSearchTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }
        let us = [MeetingSearch.Line(start: 0, text: "Welcome everyone", speaker: 0), MeetingSearch.Line(start: 245, text: "The Budget for the movers", speaker: 1),
                  MeetingSearch.Line(start: 580, text: "we cannot spend more than that", speaker: 0), MeetingSearch.Line(start: 700, text: "budget again, and the truck", speaker: 1)]
        let notes = [MeetingSearch.NoteIn(ts: "4:12", text: "Movers budget capped at 80k"), MeetingSearch.NoteIn(ts: "11:50", text: "Anna books the truck")]
        let rs = MeetingSearch.results("budget", lines: us, notes: notes)
        check(rs.map(\.kind), [.note, .line, .line], "notes first, then lines")
        check(rs.map(\.line), [1, 1, 3], "the 4:12 note falls in the 4:05 line")
        check(rs[0].timeSec, 252, "note time"); check(rs[1].speaker, 1, "line speaker")
        check(MeetingSearch.results("  ", lines: us, notes: notes).isEmpty, true, "blank query")
        let dense = [MeetingSearch.Dense(note: false, uttIndex: 2, uttEnd: 2, timeSec: 580, snippet: "we cannot spend"),
                     MeetingSearch.Dense(note: false, uttIndex: 1, uttEnd: 1, timeSec: 245, snippet: "The Budget"),
                     MeetingSearch.Dense(note: true, uttIndex: 1, uttEnd: 1, timeSec: 710, snippet: "Anna books the truck")]
        let rd = MeetingSearch.results("budget", lines: us, notes: notes, dense: dense)
        check(rd.map(\.semanticOnly), [false, true, false, true, false], "dense hits join once, marked")
        check(rd.map(\.line), [1, 3, 1, 2, 3], "dense lines")
        check(MeetingSearch.stepLines(rd, semantic: false), [1, 3], "stepper, keyword")
        check(MeetingSearch.stepLines(rd, semantic: true), [1, 2, 3], "stepper, with meaning")
        check(MeetingSearch.lineAt(us, 3), 0, "lineAt"); check(MeetingSearch.lineAt(us, 600), 2, "lineAt mid"); check(MeetingSearch.lineAt(us, -5), 0, "lineAt before")
        check(MeetingSearch.lineAt([], 5), -1, "lineAt empty")
        // Note10+ 2026-10-18: the 3:22 note opened the 3:16 line (the 3:22 line starts at 202.4 s).
        let ls = [196.2, 202.4, 210.3, 210.8].map { MeetingSearch.Line(start: $0, text: "x", speaker: 0) }
        check(MeetingSearch.lineAt(ls, 202), 1, "whole second takes the line shown with it")
        check(MeetingSearch.lineAt(ls, 201), 0, "earlier second")
        check(MeetingSearch.lineAt(ls, 210.3), 2, "exact line start keeps that line")
        check(MeetingSearch.lineAt(ls, 205.5), 1, "mid line")
        check(MeetingSearch.results("x", lines: [], notes: [MeetingSearch.NoteIn(ts: "3:22", text: "x")]).first?.line, -1, "note, no lines")
        check(MeetingSearch.results("note", lines: ls, notes: [MeetingSearch.NoteIn(ts: "3:22", text: "a note")]).first?.line, 1, "note 3:22 -> line at 202.4")
        let many = (0..<80).map { MeetingSearch.Line(start: Double($0) * 10, text: "budget \($0)", speaker: 0) }
        check(MeetingSearch.results("budget", lines: many, notes: []).count, MeetingSearch.maxResults, "capped")
        check(MeetingSearch.approxTokens("搬遷預算"), 4, "approxTokens Han")
        check(MeetingSearch.approxTokens("the movers budget"), 4, "approxTokens words")
        check(MeetingSearch.approxTokens("預算 80k 上限是多少"), 9, "approxTokens mixed")
        check(MeetingSearch.snippet("a very long line that keeps going until the word budget shows", "budget"), "…ps going until the word budget shows", "snippet")
        // History: round trip, broken file, cap, clear.
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("askhist-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let id = UUID(), item = AskHistory.Item(q: "誰訂卡車？", a: "Anna 訂", t: 1_760_000_000, x: [[192, 192.4], [250, 250]])
        check(AskHistory.decode(AskHistory.encode([item])), [item], "history round trip")
        check(AskHistory.decode(Data("{not json".utf8)).isEmpty, true, "broken history")
        for i in 0..<(AskHistory.max + 5) { AskHistory.append(id, AskHistory.Item(q: "q\(i)", a: "a", t: 0, x: []), root: dir) }
        let h = AskHistory.load(id, root: dir)
        check(h.count, AskHistory.max, "history cap"); check(h.first?.q, "q5", "oldest kept"); check(h.last?.q, "q\(AskHistory.max + 4)", "newest")
        check(AskHistory.url(id, root: dir).pathExtension, "askhistory", "not a .json the library would read")
        AskHistory.clear(id, root: dir); check(AskHistory.load(id, root: dir).isEmpty, true, "history cleared")
        try? FileManager.default.removeItem(at: dir)
        print(failures == 0 ? "meeting-search PASS" : "meeting-search: \(failures) failure(s)"); exit(failures == 0 ? 0 : 1)
    }
}
