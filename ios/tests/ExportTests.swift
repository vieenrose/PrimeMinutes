import Foundation

// Export P1 fixes: the MD export heads the journal with the localized
// agent_title, and the TXT export carries a notes heading too.
@main struct ExportTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }

    static func main() {
        let s = Session(title: "T", summary: "", seconds: 5,
                        lines: [Utterance(speaker: 0, start: 1, end: 2, text: "hello")],
                        notes: [Note(id: 1, window: 0, ts: "0:01", tag: nil, text: "a note")])
        let md = Export.text(s, .md)
        check(md.contains("## " + L("agent_title")), "md notes heading: \(md)")
        check(md.contains("- #1 [0:01] a note"), "md note bullet: \(md)")
        let txt = Export.text(s, .txt)
        check(txt.contains(L("agent_title")), "txt notes heading: \(txt)")
        check(txt.contains("- #1 [0:01] a note"), "txt note bullet: \(txt)")
        // Design A: speaker turns with a fixed-width clock gutter (Android TranscriptExportTest goldens).
        let turns = Session(title: "", summary: "", seconds: 90, lines: [
            Utterance(speaker: 0, start: 1, end: 2.5, text: "Hello there"),
            Utterance(speaker: 1, start: 2.5, end: 3, text: "   "),
            Utterance(speaker: 1, start: 65.2, end: 70, text: "我們開始吧"),
            Utterance(speaker: 1, start: 67, end: 80, text: "接著討論預算，包括搬家公司報價"),
            Utterance(speaker: 0, start: 81, end: 83, text: "OK, let's go with the middle quote."),
        ], notes: [])
        let t2 = Export.text(turns, .txt)
        check(t2.hasSuffix("[00:01] \(turns.name(0)): Hello there\n\n[01:05] \(turns.name(1)): 我們開始吧\n        接著討論預算，包括搬家公司報價\n\n[01:21] \(turns.name(0)): OK, let's go with the middle quote.\n"), "txt turns: \(t2)")
        let m2 = Export.text(turns, .md)
        check(m2.hasSuffix("- `00:01` **\(turns.name(0))** Hello there\n\n- `01:05` **\(turns.name(1))** 我們開始吧\n  接著討論預算，包括搬家公司報價\n\n- `01:21` **\(turns.name(0))** OK, let's go with the middle quote.\n"), "md turns: \(m2)")
        let hour = Session(title: "", summary: "", seconds: 3725, lines: [Utterance(speaker: 0, start: 5, end: 6, text: "start"), Utterance(speaker: 1, start: 3723, end: 3725, text: "一小時後")], notes: [])
        check(Export.text(hour, .txt).hasSuffix("[00:00:05] \(hour.name(0)): start\n\n[01:02:03] \(hour.name(1)): 一小時後\n"), "hour clock zero-padded")
        // Every stamped line still parses as a ReaderProtocol LINE; subtitles keep one cue per utterance.
        let line = try! NSRegularExpression(pattern: #"^\[(\d+:\d{2}(?::\d{2})?)\]\s*(.*)$"#)
        for l in t2.components(separatedBy: "\n") where l.hasPrefix("[") { check(line.firstMatch(in: l, range: NSRange(location: 0, length: (l as NSString).length)) != nil, "LINE parses \(l)") }
        let srt = Export.text(turns, .srt)
        check(srt.contains("2\n00:01:05,200 --> 00:01:10,000\n\(turns.name(1)): 我們開始吧\n"), "srt unchanged: \(srt)")
        check(Export.text(turns, .lrc).contains("[01:07.00]\(turns.name(1)): 接著討論預算"), "lrc unchanged")
        check(!Export.pdf(t2, title: "").isEmpty, "pdf renders")
        print("export tests: OK")
    }
}
