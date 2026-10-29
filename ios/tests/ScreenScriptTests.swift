import Foundation

enum AppLanguage { case test; static let current = AppLanguage.test; var script: ChineseScript { .traditional } }   // OpenCC.swift's TextConv default
// The meeting screens in the app's script: display-only conversion, memoized; unchanged edits keep the stored text;
// either script's query finds the shown lines.
@main struct ScreenScriptTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }
    static func cc(_ s: ChineseScript) -> ScreenScript { ScreenScript(s, generated: OpenCC.generated(s).convert, transcript: OpenCC.transcript(s).convert) }

    static func main() {
        let hans = cc(.simplified), hant = cc(.traditional)
        // zh-Hant meeting under 简体: converted; under 繁體: as stored.
        check(hans.gen("週會紀錄") == "周会纪录", "title: \(hans.gen("週會紀錄"))")
        check(hans.tr("我們開會討論這個問題。") == "我们开会讨论这个问题。", "line")
        check(hans.gen("陳經理") == "陈经理", "speaker name")
        check(hant.gen("週會紀錄") == "週會紀錄" && hant.tr("我們開會討論這個問題。") == "我們開會討論這個問題。", "繁體 keeps a Traditional meeting")
        check(hans.tr("Let's ship it.") == "Let's ship it.", "English untouched")

        // Memo: one conversion per text.
        let m = ScreenScript.Memo(OpenCC.transcript(.simplified).convert)
        _ = m("請發送資料給團隊。"); _ = m("請發送資料給團隊。"); _ = m("ascii only")
        check(m.misses == 1, "memo misses \(m.misses)")

        // Edits: the sheet opens on the shown (converted) text; Done unchanged saves nothing, a change saves what was typed.
        let stored = "請發送資料給團隊。", shown = hans.tr(stored)
        check(ScreenScript.typed(shown, shown: shown) == nil, "unchanged edit keeps the stored text")
        check(ScreenScript.typed("请发送报告给团队。", shown: shown) == "请发送报告给团队。", "typed text saved as typed")
        check(ScreenScript.typed("請發送報告", shown: shown) == "請發送報告", "a Traditional draft is not converted on save")

        // Search: a 繁體 or 简体 query finds the shown lines.
        let lines = ["我們開會討論這個問題。", "請發送資料給團隊。", "Let's ship it."].map { hans.tr($0) }
        for q in ["資料", "资料"] { check(lines.filter { $0.localizedCaseInsensitiveContains(hans.query(q)) }.count == 1, "query \(q)") }
        for q in ["資料", "资料"] { check(["請發送資料給團隊。"].map { hant.tr($0) }.filter { $0.localizedCaseInsensitiveContains(hant.query(q)) }.count == 1, "繁體 query \(q)") }
        check(lines.filter { $0.localizedCaseInsensitiveContains(hans.query("SHIP")) }.count == 1, "case-insensitive Latin")

        // Long transcript: first pass converts, a redraw is lookups only.
        let src = ["我們開會討論這個問題，請發送資料給團隊。", "後續會議時間還沒確定，週五之前回覆。", "這個專案的預算需要重新評估。"]
        let long = (0..<3000).map { "\(src[$0 % 3])第\($0)句" }
        let big = cc(.simplified)
        var t0 = Date(); let first = long.map { big.tr($0) }; let cold = Date().timeIntervalSince(t0)
        t0 = Date(); let again = long.map { big.tr($0) }; let warm = Date().timeIntervalSince(t0)
        check(first == again && first[0].hasPrefix("我们开会"), "stable")
        print(String(format: "3000 lines: cold %.0f ms, cached %.1f ms", cold * 1000, warm * 1000))
        check(warm < 0.05, "cached pass too slow: \(warm)")
        print("screen-script: OK")
    }
}
