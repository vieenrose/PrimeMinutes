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
        check(UserEdits.summaryStale(reader: "e2b", current: "e4b", received: nil, hasSummary: true, hasAudio: true), "own summary, reader changed in Settings: offer re-summarize")
        check(!UserEdits.summaryStale(reader: "e4b", current: "e4b", received: nil, hasSummary: true, hasAudio: true), "own summary, same reader: no offer")
        check(!UserEdits.summaryStale(reader: "e2b", current: "e4b", received: "e2b", hasSummary: true, hasAudio: true), "received meeting, sender's reader differs: no offer")
        check(UserEdits.summaryStale(reader: "e4b", current: "e2b", received: "e2b", hasSummary: true, hasAudio: true), "received, re-summarized here, then Settings changed: offer again")
        check(!UserEdits.summaryStale(reader: "e2b", current: "e4b", received: nil, hasSummary: false, hasAudio: true), "no summary: no offer")
        check(!UserEdits.summaryStale(reader: "e2b", current: "e4b", received: nil, hasSummary: true, hasAudio: false), "no audio: no offer")
        check(!UserEdits.summaryStale(reader: nil, current: "e4b", received: nil, hasSummary: true, hasAudio: true), "unknown reader: no offer")
        print(fails == 0 ? "UserEditsTests PASS" : "UserEditsTests FAIL (\(fails))"); exit(fails == 0 ? 0 : 1)
    }
}
