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
        print("export tests: OK")
    }
}
