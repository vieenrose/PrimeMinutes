import Foundation

// Share v2 decisions (ShareCore) and the cite placement the PDF uses (NoteCites, moved from MeetingMail).
@main struct ShareCoreTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }

    static func main() {
        // What a meeting can give.
        check(ShareCore.available(hasLines: true, hasAudio: true) == [.session, .pdf, .audio], "all three")
        check(ShareCore.available(hasLines: false, hasAudio: true) == [.audio], "not transcribed: audio only")
        check(ShareCore.available(hasLines: true, hasAudio: false) == [.pdf], "no audio: PDF only")

        // Defaults: a recording → the whole session; a podcast → the PDF without the audio.
        check(ShareCore.defaultWhat(online: false, hasLines: true, hasAudio: true) == .session, "recording default")
        check(ShareCore.defaultWhat(online: true, hasLines: true, hasAudio: true) == .pdf, "online default")
        check(ShareCore.defaultWhat(online: true, hasLines: false, hasAudio: true) == .audio, "online, not transcribed")
        check(ShareCore.defaultWhat(online: false, hasLines: false, hasAudio: false) == nil, "nothing to share")
        check(ShareCore.defaultAttachAudio(online: false, audioBytes: 5_000_000), "small recording: audio on")
        check(!ShareCore.defaultAttachAudio(online: false, audioBytes: 60_000_000), "large recording: audio off")
        check(!ShareCore.defaultAttachAudio(online: true, audioBytes: 1_000_000), "online: audio off")

        // Hints follow what carries the audio.
        check(ShareCore.hint(.session, attachAudio: false, online: false) == .voices, "session voices")
        check(ShareCore.hint(.pdf, attachAudio: false, online: false) == .none, "pdf alone: no hint")
        check(ShareCore.hint(.pdf, attachAudio: true, online: false) == .voices, "pdf + audio: voices")
        check(ShareCore.hint(.audio, attachAudio: false, online: true) == .copyright, "podcast audio: copyright")
        check(ShareCore.hint(.session, attachAudio: false, online: true) == .copyright, "podcast session: copyright")

        // Sizes.
        check(ShareCore.audioEstimate(sourceBytes: 1_234, sourceExtension: "M4A", seconds: 600) == 1_234, "AAC copied as is")
        check(ShareCore.audioEstimate(sourceBytes: 19_200_000, sourceExtension: "wav", seconds: 600) == 4_800_000, "WAV re-encoded")
        check(ShareCore.audioEstimate(sourceBytes: 100, sourceExtension: "wav", seconds: 600) == 100, "never above the source")
        check(ShareCore.total(.pdf, attachAudio: true, session: 9, pdf: 2, audio: 5) == 7 && ShareCore.total(.pdf, attachAudio: false, session: 9, pdf: 2, audio: 5) == 2, "pdf total")
        check(ShareCore.sessionEstimate(audio: 1_000, textChars: 100) == 1_000 + 300 + 4_096, "session estimate")

        // File names.
        let d = ISO8601DateFormatter().date(from: "2026-10-29T06:30:00Z")!, tz = TimeZone(identifier: "Asia/Taipei")!
        check(ShareCore.fileBase(title: "週會: 預算/搬遷?", date: d, timeZone: tz) == "2026-10-29 週會 預算 搬遷", "sanitized: \(ShareCore.fileBase(title: "週會: 預算/搬遷?", date: d, timeZone: tz))")
        check(ShareCore.fileBase(title: "  ", date: nil) == "Prime Minutes", "untitled, no date")
        check(ShareCore.fileBase(title: String(repeating: "a", count: 90), date: nil).count == 60, "60 characters")

        // PDF links: a cite goes to the last turn starting at or before it; a turn goes back to its first cite.
        check(ShareCore.target(75, [1, 65, 81]) == 1 && ShareCore.target(0, [5]) == 0 && ShareCore.target(3, []) == nil, "cite target")
        check(ShareCore.seconds("1:02:03") == 3723 && ShareCore.seconds("0:15") == 15, "seconds")
        check(ShareCore.citeTimes("0:18、1:10, 1:02:03") == ["0:18", "1:10", "1:02:03"], "cite times")
        let ct = [(cite: 1, turnSec: 65), (cite: 2, turnSec: 1), (cite: 3, turnSec: 65)]
        check(ShareCore.backDest(turnSec: 65, citeTargets: ct) == "c-1" && ShareCore.backDest(turnSec: 1, citeTargets: ct) == "c-2", "back to first cite")
        check(ShareCore.backDest(turnSec: 81, citeTargets: ct) == ShareCore.summaryDest, "uncited turn: back to the summary")
        check(ShareCore.turnDest(65) == "t-65", "turn dest")

        // Cite placement (was MeetingMailTests): trivial items, item lines, clause-end cites.
        check(NoteCites.trivial("3") && NoteCites.trivial("無") && NoteCites.trivial("。") && !NoteCites.trivial("預算 3 萬"), "trivial")
        check(NoteCites.itemLine("- 確認合約 [1:12]") == NoteCites.Item(text: "確認合約", ts: "1:12") && NoteCites.itemLine("- free") == NoteCites.Item(text: "free", ts: nil) && NoteCites.itemLine("free") == nil, "item lines")
        check(NoteCites.clauseEndCites("新辦公室預計於三月十號正式上班 [0:00]。會議決定採用中間報價 [0:18, 0:35]，前提是打包自行完成 [0:40]。")
              == "新辦公室預計於三月十號正式上班。\u{2060}[0:00] 會議決定採用中間報價，前提是打包自行完成。\u{2060}[0:18, 0:35]", "zh clause-end cites")
        check(NoteCites.clauseEndCites("We move on March 7 [0:12]. The budget is 3.5 percent over [0:12]. No cite here.")
              == "We move on March 7.\u{00A0}[0:12] The budget is 3.5 percent over. No cite here.", "en clause-end cites")
        let han = String(repeating: "搬", count: 60), long = NoteCites.clauseEndCites(han + " [1:12]。")
        check(long == han + "。\u{2060}[1:12]" && !long.contains(" ["), "long Han sentence keeps its cite glued")
        check(NoteCites.itemCite("確保包含損壞賠償條款", "1:12") == "。\u{2060}[1:12]" && NoteCites.itemCite("確保包含損壞賠償條款。", "1:12") == "\u{2060}[1:12]"
              && NoteCites.itemCite("Alice sends the deck", "0:05") == "\u{00A0}[0:05]" && NoteCites.itemCite("x", nil) == "", "item cites")
        // Word (.docx): every link anchor has its bookmark, cited turns link back to their first cite, no AI notes.
        let doc = ShareDocx.Doc(title: "搬遷 <會議> & 預算", meta: ["2026年10月29日 · 7 分鐘", "說話者：甲、乙"], summaryHeading: "摘要",
            summary: ["開始搬遷。\u{2060}[0:01] 決定報價，前提是自行打包。\u{2060}[1:05, 1:21]"],
            sections: [ShareDocx.Section(heading: "待辦事項", groups: [ShareDocx.Group(label: nil, items: ["乙寄出通知。\u{2060}[1:21]"])])],
            transcriptHeading: "逐字稿",
            turns: [ShareDocx.Turn(clock: "00:01", speaker: "甲", text: "大家好", sec: 1, cont: false),
                    ShareDocx.Turn(clock: "01:05", speaker: "乙", text: "我們開始吧", sec: 65, cont: false),
                    ShareDocx.Turn(clock: "", speaker: "乙", text: "接著討論", sec: 67, cont: true),
                    ShareDocx.Turn(clock: "01:21", speaker: "甲", text: "OK", sec: 81, cont: false),
                    ShareDocx.Turn(clock: "01:30", speaker: "乙", text: "沒有被引用", sec: 90, cont: false)],
            backLabel: "回摘要", zh: true, simplified: false)
        let x = ShareDocx.documentXML(doc)
        func all(_ p: String) -> [String] { let re = try! NSRegularExpression(pattern: p), ns = x as NSString
            return re.matches(in: x, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range(at: 1)) } }
        let anchors = all(#"w:anchor="([^"]+)""#), marks = Set(all(#"w:bookmarkStart w:id="\d+" w:name="([^"]+)""#))
        check(anchors.filter { $0.hasPrefix("t_") } == ["t_1", "t_65", "t_81", "t_81"], "cite anchors \(anchors)")
        check(anchors.allSatisfy(marks.contains), "every anchor has a bookmark: \(anchors) vs \(marks)")
        check(anchors.filter { $0.hasPrefix("c_") } == ["c_1", "c_2", "c_2"], "back links to the first cite: \(anchors)")
        check(marks.contains("summary") && marks.contains("t_90") && x.components(separatedBy: "↑ 回摘要").count - 1 == 3, "uncited turn: bookmark, no back link")
        check(Set(all(#"w:id="(\d+)""#)).count == marks.count, "bookmark ids unique")
        check(x.contains("搬遷 &lt;會議&gt; &amp; 預算"), "escaped title")
        check(x.contains("<w:pStyle w:val=\"Title\"/>") && x.contains("<w:rStyle w:val=\"Speaker\"/>") && x.contains("<w:rStyle w:val=\"Clock\"/>"), "styles used")
        check(!x.contains("AI 筆記"), "no AI notes")
        let st = ShareDocx.stylesXML(zh: true, simplified: false)
        check(st.contains("w:eastAsia=\"Microsoft JhengHei\"") && st.contains("w:eastAsia=\"zh-TW\"") && ShareDocx.stylesXML(zh: true, simplified: true).contains("w:eastAsia=\"zh-CN\""), "east Asian font + lang")
        for xml in [x, st, ShareDocx.contentTypes, ShareDocx.rels, ShareDocx.docRels] {
            let p = XMLParser(data: Data(xml.utf8)); check(p.parse(), "well-formed XML: \(p.parserError.map { "\($0)" } ?? "")")
        }
        let z = ShareDocx.data(doc)
        check(z.prefix(4) == Data([0x50, 0x4B, 0x03, 0x04]) && z.suffix(22).prefix(4) == Data([0x50, 0x4B, 0x05, 0x06]), "zip framing")
        check(Zip.crc32(Data("123456789".utf8)) == 0xCBF43926, "crc32")
        if let o = ProcessInfo.processInfo.environment["SHARE_DOCX_OUT"] { try? z.write(to: URL(fileURLWithPath: o)) }
        print("share core tests: OK")
    }
}
