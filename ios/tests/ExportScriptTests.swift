import Foundation

enum AppLanguage { case test; static let current = AppLanguage.test; var script: ChineseScript { .traditional } }   // OpenCC.swift's TextConv default
// A zh-Hant meeting shared with the 简体 interface: Simplified .docx text, links intact; 繁體 leaves it as is.
@main struct ExportScriptTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }
    static func all(_ re: String, _ s: String) -> [String] {
        let r = try! NSRegularExpression(pattern: re)
        return r.matches(in: s, range: NSRange(s.startIndex..., in: s)).map { String(s[Range($0.range(at: 1), in: s)!]) }
    }

    static func main() {
        let wj = "\u{2060}"
        let doc = ShareDocx.Doc(
            title: "週會紀錄", meta: ["說話者：陳經理、劉專員"], summaryHeading: "摘要",
            summary: ["會議討論了資料傳送 [0:10]。後續見 [1:30]。"],
            sections: [ShareDocx.Section(heading: "待辦事項", groups: [ShareDocx.Group(label: nil, items: ["劉專員寄出報告\(wj)[0:10]"])])],
            transcriptHeading: "逐字稿",
            turns: [ShareDocx.Turn(clock: "00:00", speaker: "陳經理", text: "我們開會討論這個問題。", sec: 0, cont: false),
                    ShareDocx.Turn(clock: "00:10", speaker: "劉專員", text: "請發送資料給團隊。", sec: 10, cont: false),
                    ShareDocx.Turn(clock: "01:30", speaker: "陳經理", text: "Let's ship it.", sec: 90, cont: false)],
            backLabel: "摘要", zh: true, simplified: false)

        var simp = ExportScript.doc(doc, generated: OpenCC.generated(.simplified).convert, transcript: OpenCC.transcript(.simplified).convert)
        simp.simplified = true
        check(simp.title == "周会纪录", "title: \(simp.title)")
        check(simp.summary == ["会议讨论了资料传送 [0:10]。后续见 [1:30]。"], "summary: \(simp.summary)")
        check(simp.meta == ["说话者：陈经理、刘专员"], "meta: \(simp.meta)")
        check(simp.sections[0].heading == "待办事项" && simp.sections[0].groups[0].items == ["刘专员寄出报告\(wj)[0:10]"], "actions: \(simp.sections)")
        check(simp.turns.map(\.speaker) == ["陈经理", "刘专员", "陈经理"], "speakers")
        check(simp.turns.map(\.text) == ["我们开会讨论这个问题。", "请发送资料给团队。", "Let's ship it."], "turns: \(simp.turns.map(\.text))")
        check(ExportScript.names(["0": "陳經理"], generated: OpenCC.generated(.simplified).convert) == ["0": "陈经理"], "names")

        let before = ShareDocx.documentXML(doc), after = ShareDocx.documentXML(simp)
        check(!after.contains("會議") && after.contains("会议"), "docx body Simplified")
        let la = all(#"w:anchor="([^"]+)""#, after), ba = all(#"w:bookmarkStart[^>]*w:name="([^"]+)""#, after)
        check(!la.isEmpty && la == all(#"w:anchor="([^"]+)""#, before), "same links: \(la)")
        check(Set(la).isSubset(of: Set(ba)), "every link lands on a bookmark: \(la) vs \(ba)")

        let trad = ExportScript.doc(doc, generated: OpenCC.generated(.traditional).convert, transcript: OpenCC.transcript(.traditional).convert)
        check(ShareDocx.documentXML(trad) == before, "繁體 leaves a Traditional meeting as is")

        if let dir = ProcessInfo.processInfo.environment["EXPORT_SCRIPT_OUT"] {
            try? ShareDocx.data(simp).write(to: URL(fileURLWithPath: dir + "/ios-export-zh-CN.docx"))
            try? ShareDocx.data(trad).write(to: URL(fileURLWithPath: dir + "/ios-export-zh-TW.docx"))
        }
        print("export-script: OK")
    }
}
