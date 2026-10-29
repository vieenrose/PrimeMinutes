import Foundation

/// What the user typed into a meeting (Android UserEdits): a re-summarize asks before replacing an edited summary or
/// action items, a re-transcribe before dropping edited line text. Speaker names, merges and boundaries are carried
/// by both re-runs, so they never ask. Pure: Session holds the two flags (summaryUserSet, linesUserEdited).
enum UserEdits {
    static func needsConfirm(summaryEdited: Bool?, linesEdited: Bool?, transcribe: Bool) -> Bool {
        transcribe ? linesEdited == true : summaryEdited == true
    }
    /// A save of an edit sheet's draft: whether it changed the text (an unchanged Done is no edit).
    static func changed(_ draft: String, from old: String?) -> Bool {
        draft.trimmingCharacters(in: .whitespacesAndNewlines) != (old ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
