import Foundation

/// The meeting screens in the app's Han script (Android shows a session converted to SummaryScript.scriptFor; here
/// the script is AppLanguage.current.script, so an English interface keeps the stored script, like the exports).
/// Display only: the stored session, the .pmin and what the user edits keep their own text (see `typed`).
/// Conversions are memoized per script and text (`Memo`), so a long transcript converts each line once and a
/// redraw or an edit to one line costs a dictionary lookup per line.
final class ScreenScript: @unchecked Sendable {
    /// One converter with a text → converted memo. Bounded: cleared past `cap` entries (a long library and a few
    /// long meetings stay well under it).
    final class Memo: @unchecked Sendable {
        private let f: (String) -> String; private var m: [String: String] = [:]; private let lock = NSLock(); private let cap: Int
        private(set) var misses = 0
        init(_ f: @escaping (String) -> String, cap: Int = 200_000) { self.f = f; self.cap = cap }
        func callAsFunction(_ t: String) -> String {
            if t.isEmpty || t.allSatisfy(\.isASCII) { return t }
            lock.lock(); if let r = m[t] { lock.unlock(); return r }; lock.unlock()
            let r = f(t)
            lock.lock(); if m.count >= cap { m.removeAll(keepingCapacity: true) }; m[t] = r; misses += 1; lock.unlock()
            return r
        }
    }
    let script: ChineseScript
    /// Generated text (title, summary, notes, actions, speaker names) and what was said (transcript lines).
    let gen: Memo, tr: Memo
    init(_ s: ChineseScript, generated g: @escaping (String) -> String, transcript t: @escaping (String) -> String) { script = s; gen = Memo(g); tr = Memo(t) }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var byScript: [ChineseScript: ScreenScript] = [:]   // guarded by lock
    /// The converters of a script (built once per script, shared by every screen).
    static func of(_ s: ChineseScript) -> ScreenScript {
        lock.lock(); defer { lock.unlock() }
        if let c = byScript[s] { return c }
        let c = ScreenScript(s, generated: OpenCC.generated(s).convert, transcript: OpenCC.transcript(s).convert); byScript[s] = c; return c
    }

    /// An edit sheet opened on `shown` (the converted text): saving it unchanged keeps the stored text (nil = no
    /// change); anything else is what the user typed, saved as typed.
    static func typed(_ draft: String, shown: String) -> String? { draft == shown ? nil : draft }
    /// In-screen search over converted text: the query in the screen's script too, so a 繁體 or 简体 query finds
    /// the same lines (character level, like the transcript).
    func query(_ q: String) -> String { tr(q) }
}

extension ScreenScript {
    /// The current screen script's converters (AppLanguage.current.script; English keeps the stored script).
    static var now: ScreenScript { of(AppLanguage.current.script) }
    /// Generated text (titles, notes, names, answers) as shown.
    static func gen(_ t: String) -> String { now.gen(t) }
    /// Transcript text (quoted excerpts, episode text) as shown.
    static func said(_ t: String) -> String { now.tr(t) }
    /// A rename / note field opened on the shown `stored` text: what to save, or nil when nothing should change
    /// (left as shown, blank, or equal to the stored text). Never a converted copy of the stored text.
    static func renamed(_ draft: String, stored: String) -> String? {
        let n = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty, n != stored, typed(n, shown: gen(stored)) != nil else { return nil }
        return n
    }
}
