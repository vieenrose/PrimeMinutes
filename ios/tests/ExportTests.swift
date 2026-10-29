import Foundation

// The export helpers the share-v2 PDF uses (notes / actions sections, clause-end cites, turns, LRC) and the PDF's
// internal links. Built with the app sources (needs UIKit); the Foundation-only parts run in ShareCoreTests.
@main struct ExportTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }

    static func main() {
        let s = Session(title: "T", summary: "", seconds: 5,
                        lines: [Utterance(speaker: 0, start: 1, end: 2, text: "hello")],
                        notes: [Note(id: 1, window: 0, ts: "0:01", tag: nil, text: "a note")])
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
        check(Export.line(sec.groups[0].items[0]) == "討論新平站開業細節。\(wj)[0:15]", "note line cite")
        let en = Export.notesSection([Note(id: 1, window: 0, ts: "1:02", tag: "DEC", text: "Ship on Monday")], heading: "AI notes", label: { $0 == "DECISION" ? "Decision" : nil })!
        check(en.groups.map(\.label) == ["Decision"] && Export.line(en.groups[0].items[0]) == "Ship on Monday\u{00A0}[1:02]", "en notes")
        let sm = Export.summary("涵蓋多個方面 [0:15]。在營銷上 [1:16]，推出套餐 [1:49]。")
        check(sm == "涵蓋多個方面。\(wj)[0:15] 在營銷上，推出套餐。\(wj)[1:16]" && !sm.contains("][") && !sm.contains(" ["), "summary cites: \(sm)")
        check(Export.summary("Ship it [0:05]. Then test [0:09].") == "Ship it.\u{00A0}[0:05] Then test.\u{00A0}[0:09]", "latin cite spacing: \(Export.summary("Ship it [0:05]. Then test [0:09]."))")
        // A long Han paragraph is cut at cite boundaries near every 250 characters, never inside a sentence.
        let sen = String(repeating: "會議討論了搬遷的細節與預算安排", count: 4)   // 60 Han characters per sentence
        let long = (0..<12).map { "\(sen) [\($0):00]。" }.joined()
        let ps = Export.paragraphs(long)
        check(ps.count >= 2 && ps.allSatisfy { $0.count <= 350 } && ps.allSatisfy { $0.hasSuffix("]") && !$0.hasPrefix(" ") }, "long paragraph cut: \(ps.map(\.count))")
        check(ps.joined(separator: " ") == Export.paragraphs(long.replacingOccurrences(of: "X", with: "X")).joined(separator: " ") && ps.joined(separator: " ") == NoteCites.clauseEndCites(long), "cut keeps every character")
        check(Export.summary("a [0:01]。\n\nb [0:02]。") == "a。\(wj)[0:01]\n\nb。\(wj)[0:02]", "paragraph breaks kept")
        check(Export.notesSection([Note(id: 1, window: 0, ts: "0:05", tag: "NUMBER", text: "3"), Note(id: 2, window: 0, ts: "0:06", tag: nil, text: "。")], heading: "AI 筆記") == nil, "trivial notes dropped")
        let acts = Export.actionsSection("- 林雅婷寄出通知信 [4:11]\n- 3\n-\n- 確認合約。 [1:12]\n自由文字", heading: "行動項目")!
        check(acts.groups[0].items.map(Export.line) == ["林雅婷寄出通知信。\(wj)[4:11]", "確認合約。\(wj)[1:12]", "自由文字"], "actions items")
        // Design A: speaker turns with a fixed-width clock gutter (Android TranscriptExportTest goldens).
        let turns = Session(title: "", summary: "", seconds: 90, lines: [
            Utterance(speaker: 0, start: 1, end: 2.5, text: "Hello there"),
            Utterance(speaker: 1, start: 2.5, end: 3, text: "   "),
            Utterance(speaker: 1, start: 65.2, end: 70, text: "我們開始吧"),
            Utterance(speaker: 1, start: 67, end: 80, text: "接著討論預算，包括搬家公司報價"),
            Utterance(speaker: 0, start: 81, end: 83, text: "OK, let's go with the middle quote."),
        ], notes: [])
        let ts = Export.turns(turns.lines)
        check(ts.map(\.text) == ["Hello there", "我們開始吧", "接著討論預算，包括搬家公司報價", "OK, let's go with the middle quote."] && ts.map(\.cont) == [false, false, true, false], "turns")
        check(Export.gutterClock(65.2, Export.gutterWidth(turns.lines)) == "01:05" && Export.gutterClock(3723, 8) == "01:02:03", "gutter clock")
        check(Export.lrc(turns).contains("[01:07.00]\(turns.name(1)): 接著討論預算"), "lrc unchanged")
        check(!Export.pdf(turns).isEmpty, "pdf renders")
        // Cites in the summary become links to named transcript destinations.
        let linked = Session(title: "T", summary: "開始 [0:01]。後來 [1:05, 1:21]。", seconds: 90, lines: turns.lines, notes: [])
        let pdfData = Export.pdf(linked)
        if let p = ProcessInfo.processInfo.environment["EXPORT_PDF_OUT"] { try? pdfData.write(to: URL(fileURLWithPath: p)) }
        let pdf = String(decoding: pdfData, as: UTF8.self)
        // One link per cite time (0:01, 1:05, 1:21) plus one "↑" back link per turn (3 turns).
        check(pdf.components(separatedBy: "/Subtype /Link").count - 1 == 6, "pdf: 6 links")
        let ys = Set(try! NSRegularExpression(pattern: #"/XYZ 0 ([\d.]+) 0"#).matches(in: pdf, range: NSRange(location: 0, length: (pdf as NSString).length)).map { (pdf as NSString).substring(with: $0.range(at: 1)) })
        check(ys.count >= 3, "pdf: distinct destinations \(ys)")
        print("export tests: OK")
    }
}
