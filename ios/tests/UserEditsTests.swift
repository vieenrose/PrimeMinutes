import Foundation

@main struct UserEditsTests {
    static var fails = 0
    static func check(_ ok: Bool, _ what: String) { if !ok { fails += 1; print("FAIL " + what) } }
    static func main() {
        check(!UserEdits.needsConfirm(summaryEdited: nil, linesEdited: nil, transcribe: false), "fresh meeting: re-summarize does not ask")
        check(!UserEdits.needsConfirm(summaryEdited: nil, linesEdited: nil, transcribe: true), "fresh meeting: re-transcribe does not ask")
        check(UserEdits.needsConfirm(summaryEdited: true, linesEdited: nil, transcribe: false), "edited summary: re-summarize asks")
        check(!UserEdits.needsConfirm(summaryEdited: true, linesEdited: nil, transcribe: true), "edited summary only: re-transcribe does not ask about lines")
        check(UserEdits.needsConfirm(summaryEdited: nil, linesEdited: true, transcribe: true), "edited lines: re-transcribe asks")
        check(!UserEdits.needsConfirm(summaryEdited: nil, linesEdited: true, transcribe: false), "edited lines only: re-summarize does not ask")
        check(!UserEdits.changed(" a ", from: "a"), "whitespace-only Done is no edit")
        check(UserEdits.changed("b", from: "a"), "new text is an edit")
        check(UserEdits.changed("b", from: nil), "text on an empty card is an edit")
        print(fails == 0 ? "UserEditsTests PASS" : "UserEditsTests FAIL (\(fails))"); exit(fails == 0 ? 0 : 1)
    }
}
