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
        check(md.contains("- a note\u{00A0}[0:01](#t-1)") && md.contains("- <a id=\"t-1\"></a>`00:01`"), "md note bullet + anchor: \(md)")
        let txt = Export.text(s, .txt)
        check(txt.contains(Export.plainHeading(L("agent_title"))), "txt notes heading: \(txt)")
        check(txt.contains("a note\u{00A0}[0:01]") && !txt.contains("#1"), "txt note bullet: \(txt)")
        // Notes grouped by type (Android ExportNotesTest goldens), no #N, no raw (TAG).
        let wj = "\u{2060}", zh = ["DECISION": "決議", "ACTION": "待辦", "OPEN-ISSUE": "未決", "NUMBER": "數字", "PROPOSAL": "提議"]
        let sec = Export.notesSection([
            Note(id: 1, window: 0, ts: "0:15", tag: nil, text: "討論新平站開業細節"),
            Note(id: 2, window: 0, ts: "1:16", tag: "PROPOSAL", text: "建議初期推出低價套餐"),
            Note(id: 3, window: 0, ts: "23:15", tag: "NUMBER", text: "積分兌換比例建議為十元一分"),
            Note(id: 4, window: 0, ts: "28:42", tag: "OPEN", text: "化妝品進貨渠道需掌握。"),
            Note(id: 5, window: 0, ts: "29:00", tag: "-", text: "-"),
        ], heading: "AI 筆記", label: { zh[$0] })!
        check(sec.groups.map(\.label) == [nil, "未決", "數字", "提議"], "group order \(sec.groups.map(\.label))")
        let pt = Export.plain(sec)
        check(pt == "【AI 筆記】\n・討論新平站開業細節。\(wj)[0:15]\n\n未決：\n・化妝品進貨渠道需掌握。\(wj)[28:42]\n\n數字：\n・積分兌換比例建議為十元一分。\(wj)[23:15]\n\n提議：\n・建議初期推出低價套餐。\(wj)[1:16]", "plain notes: \(pt)")
        let pm = Export.markdown(sec)
        check(pm.hasPrefix("## AI 筆記\n\n- 討論新平站開業細節。\(wj)`0:15`\n\n### 未決\n\n") && pm.contains("### 提議\n\n- 建議初期推出低價套餐。\(wj)`1:16`"), "md notes: \(pm)")
        let en = Export.notesSection([Note(id: 1, window: 0, ts: "1:02", tag: "DEC", text: "Ship on Monday")], heading: "AI notes", label: { $0 == "DECISION" ? "Decision" : nil })!
        check(Export.plain(en) == "AI NOTES\nDecision:\n- Ship on Monday\u{00A0}[1:02]", "en notes: \(Export.plain(en))")
        let sm = Export.summary("涵蓋多個方面 [0:15]。在營銷上 [1:16]，推出套餐 [1:49]。")
        check(sm == "涵蓋多個方面。\(wj)[0:15] 在營銷上，推出套餐。\(wj)[1:16]" && !sm.contains("][") && !sm.contains(" ["), "summary cites: \(sm)")
        check(Export.summary("Ship it [0:05]. Then test [0:09].") == "Ship it.\u{00A0}[0:05] Then test.\u{00A0}[0:09]", "latin cite spacing: \(Export.summary("Ship it [0:05]. Then test [0:09]."))")
        // A long Han paragraph is cut at cite boundaries near every 250 characters, never inside a sentence.
        let sen = String(repeating: "會議討論了搬遷的細節與預算安排", count: 4)   // 60 Han characters per sentence
        let long = (0..<12).map { "\(sen) [\($0):00]。" }.joined()
        let ps = Export.paragraphs(long)
        check(ps.count >= 2 && ps.allSatisfy { $0.count <= 350 } && ps.allSatisfy { $0.hasSuffix("]") && !$0.hasPrefix(" ") }, "long paragraph cut: \(ps.map(\.count))")
        check(ps.joined(separator: " ") == Export.paragraphs(long.replacingOccurrences(of: "X", with: "X")).joined(separator: " ") && ps.joined(separator: " ") == MeetingMail.clauseEndCites(long), "cut keeps every character")
        check(Export.summary("a [0:01]。\n\nb [0:02]。") == "a。\(wj)[0:01]\n\nb。\(wj)[0:02]", "paragraph breaks kept")
        check(Export.mdCites("a [0:18, 0:35]") == "a `0:18, 0:35`", "md multi-cite, no turns")
        check(Export.mdCites("a。\u{2060}[0:18、1:10]", [0, 20, 65]) == "a。\u{2060}[0:18](#t-0)、[1:10](#t-65)", "md multi-cite links: \(Export.mdCites("a。\u{2060}[0:18、1:10]", [0, 20, 65]))")
        // Trivial notes (no letter) dropped; action items through the formatted path.
        check(Export.notesSection([Note(id: 1, window: 0, ts: "0:05", tag: "NUMBER", text: "3"), Note(id: 2, window: 0, ts: "0:06", tag: nil, text: "。")], heading: "AI 筆記") == nil, "trivial notes dropped")
        let acts = Export.actionsSection("- 林雅婷寄出通知信 [4:11]\n- 3\n-\n- 確認合約。 [1:12]\n自由文字", heading: "行動項目")!
        check(Export.plain(acts) == "【行動項目】\n・林雅婷寄出通知信。\(wj)[4:11]\n・確認合約。\(wj)[1:12]\n・自由文字", "actions txt: \(Export.plain(acts))")
        check(Export.markdown(acts, [0, 70, 240]) == "## 行動項目\n\n- 林雅婷寄出通知信。\(wj)[4:11](#t-240)\n- 確認合約。\(wj)[1:12](#t-70)\n- 自由文字", "actions md: \(Export.markdown(acts, [0, 70, 240]))")
        check(Export.plain(Export.actionsSection("- Send the notice [0:09]", heading: "Action items")!) == "ACTION ITEMS\n- Send the notice\u{00A0}[0:09]", "actions en")
        check(Export.target(75, [1, 65, 81]) == 1 && Export.target(0, [5]) == 0, "cite target")
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
        check(m2.hasSuffix("- <a id=\"t-1\"></a>`00:01` **\(turns.name(0))** Hello there\n\n- <a id=\"t-65\"></a>`01:05` **\(turns.name(1))** 我們開始吧\n  接著討論預算，包括搬家公司報價\n\n- <a id=\"t-81\"></a>`01:21` **\(turns.name(0))** OK, let's go with the middle quote.\n"), "md turns: \(m2)")
        // Every md href has a matching anchor id.
        let lm = Export.text(Session(title: "T", summary: "開始 [0:01]。後來 [1:05, 1:30]。", seconds: 90, lines: turns.lines, notes: []), .md) as NSString
        let ids = Set(try! NSRegularExpression(pattern: #"<a id="(t-\d+)"></a>"#).matches(in: lm as String, range: NSRange(location: 0, length: lm.length)).map { lm.substring(with: $0.range(at: 1)) })
        let hrefs = try! NSRegularExpression(pattern: #"\]\(#(t-\d+)\)"#).matches(in: lm as String, range: NSRange(location: 0, length: lm.length)).map { lm.substring(with: $0.range(at: 1)) }
        check(hrefs == ["t-1", "t-65", "t-81"] && hrefs.allSatisfy(ids.contains), "md hrefs \(hrefs) ids \(ids)")
        let hour = Session(title: "", summary: "", seconds: 3725, lines: [Utterance(speaker: 0, start: 5, end: 6, text: "start"), Utterance(speaker: 1, start: 3723, end: 3725, text: "一小時後")], notes: [])
        check(Export.text(hour, .txt).hasSuffix("[00:00:05] \(hour.name(0)): start\n\n[01:02:03] \(hour.name(1)): 一小時後\n"), "hour clock zero-padded")
        // Every stamped line still parses as a ReaderProtocol LINE; subtitles keep one cue per utterance.
        let line = try! NSRegularExpression(pattern: #"^\[(\d+:\d{2}(?::\d{2})?)\]\s*(.*)$"#)
        for l in t2.components(separatedBy: "\n") where l.hasPrefix("[") { check(line.firstMatch(in: l, range: NSRange(location: 0, length: (l as NSString).length)) != nil, "LINE parses \(l)") }
        let srt = Export.text(turns, .srt)
        check(srt.contains("2\n00:01:05,200 --> 00:01:10,000\n\(turns.name(1)): 我們開始吧\n"), "srt unchanged: \(srt)")
        check(Export.text(turns, .lrc).contains("[01:07.00]\(turns.name(1)): 接著討論預算"), "lrc unchanged")
        check(!Export.pdf(t2, title: "").isEmpty, "pdf renders")
        // Cites in the summary become links to named transcript destinations.
        let linked = Session(title: "T", summary: "開始 [0:01]。後來 [1:05, 1:21]。", seconds: 90, lines: turns.lines, notes: [])
        let pdfData = Export.pdf(Export.text(linked, .txt), title: "T")
        if let p = ProcessInfo.processInfo.environment["EXPORT_PDF_OUT"] { try? pdfData.write(to: URL(fileURLWithPath: p)) }
        let pdf = String(decoding: pdfData, as: UTF8.self)
        // One link per cite time (0:01, 1:05, 1:21), each resolved to its own turn's destination.
        check(pdf.components(separatedBy: "/Subtype /Link").count - 1 == 3, "pdf: 3 links")
        let ys = Set(try! NSRegularExpression(pattern: #"/XYZ 0 ([\d.]+) 0"#).matches(in: pdf, range: NSRange(location: 0, length: (pdf as NSString).length)).map { (pdf as NSString).substring(with: $0.range(at: 1)) })
        check(ys.count == 3, "pdf: 3 distinct destinations \(ys)")
        print("export tests: OK")
    }
}
