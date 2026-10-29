import UIKit

/// Session export (share v2): the summary + transcript PDF with internal links, and the helpers it shares with the
/// screen (clocks, speaker turns, the notes and action-items sections).
enum Export {
    /// Android TranscriptExport.clock: [h:]mm:ss.
    static func hms(_ t: Double) -> String {
        let x = max(0, Int(t)), h = x / 3600, m = x % 3600 / 60, s = x % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
    /// Zero-padded `mm:ss`, or `hh:mm:ss` when `width` is 8, so the transcript gutter aligns; still matches
    /// `\d+:\d{2}(?::\d{2})?` (ReaderProtocol / Markdown anchors). Android TranscriptExport.clock(sec, width).
    static func gutterClock(_ t: Double, _ width: Int) -> String {
        let x = max(0, Int(t)), h = x / 3600
        return h > 0 || width >= 8 ? String(format: "%02d:%02d:%02d", h, x % 3600 / 60, x % 60) : String(format: "%02d:%02d", x / 60, x % 60)
    }
    /// One transcript line of a document export: `cont` = same speaker as the previous (non-blank) line.
    struct Line { var u: Utterance; var text: String; var cont: Bool }
    /// Non-blank utterances (newlines flattened) grouped into speaker turns. Android TranscriptExport.turns.
    static func turns(_ ls: [Utterance]) -> [Line] {
        var out: [Line] = [], prev: Int?
        for u in ls {
            let x = u.text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
            if x.isEmpty { continue }
            out.append(Line(u: u, text: x, cont: u.speaker == prev)); prev = u.speaker
        }
        return out
    }
    /// Clock width for the whole document: 5 (`mm:ss`) or 8 (`hh:mm:ss`).
    static func gutterWidth(_ ls: [Utterance]) -> Int { ls.contains { $0.start >= 3600 } ? 8 : 5 }

    static func mmss(_ t: Double) -> String { let x = max(0, Int(t)); return x >= 3600 ? String(format: "%d:%02d:%02d", x / 3600, x % 3600 / 60, x % 60) : String(format: "%d:%02d", x / 60, x % 60) }

    /// Android: exported title, summary, notes and action items carry speaker names, not the raw S1/S2 refs the screen resolves.
    static func refs(_ t: String, _ s: Session) -> String { SpeakerRefs.resolve(t, label: { s.name($0) }, known: Set(s.lines.map(\.speaker))) }
    /// The LRC lyrics embedded in a shared .pmin's audio (players show the transcript with it); not a user-visible export.
    static func lrc(_ s: Session) -> String {
        let title = refs(s.title, s)
        let head = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : "[ti:\(title)]\n"
        return head + s.lines.compactMap { l in
            let x = l.text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces); guard !x.isEmpty else { return nil }
            let cs = Int(max(0, l.start) * 100)
            return String(format: "[%02d:%02d.%02d]", cs / 6000, cs / 100 % 60, cs % 100) + "\(s.name(l.speaker)): \(x)" }.joined(separator: "\n") + "\n"
    }

    // MARK: summary + AI notes (Android ExportNotes): the email's minutes layout, cites at the clause end.

    struct NoteItem: Equatable { var text: String; var ts: String? }
    struct NoteGroup: Equatable { var label: String?; var items: [NoteItem] }
    struct NoteSection: Equatable { var heading: String; var groups: [NoteGroup] }

    /// The reader's note types in display order (the email's minutes priority).
    static let tagOrder = ["DECISION", "ACTION", "OPEN-ISSUE", "NUMBER", "PROPOSAL"]

    /// A tag completed from its prefix ("ACT" → ACTION), as the Agent panel shows it; nil for `-` / none.
    static func fullTag(_ tag: String?) -> String? {
        guard let t = tag?.trimmingCharacters(in: .whitespaces).uppercased(), !t.isEmpty, t != "-" else { return nil }
        return tagOrder.first { $0.hasPrefix(t) } ?? t
    }

    /// The Agent panel's localized label of a full tag (nil = unknown, shown raw).
    static func tagLabel(_ t: String) -> String? {
        switch t {
        case "DECISION": return L("agent_tag_decision"); case "ACTION": return L("agent_tag_action"); case "OPEN-ISSUE": return L("agent_tag_open")
        case "NUMBER": return L("agent_tag_number"); case "PROPOSAL": return L("agent_tag_proposal"); default: return nil
        }
    }

    /// Notes grouped by type: untyped first (no label), then `tagOrder`, then any unknown tag; no `#N` index.
    static func notesSection(_ notes: [Note], heading: String = L("agent_title"), label: (String) -> String? = tagLabel) -> NoteSection? {
        var keys: [String?] = [nil] + tagOrder.map { Optional($0) }, by: [String?: [NoteItem]] = [:]
        for n in notes {
            var t = n.text.trimmingCharacters(in: .whitespacesAndNewlines)
            while t.hasSuffix("。") { t.removeLast() }
            t = t.trimmingCharacters(in: .whitespaces)
            if NoteCites.trivial(t) { continue }
            let k = fullTag(n.tag)
            if !keys.contains(k) { keys.append(k) }
            let ts = n.ts.trimmingCharacters(in: .whitespaces)
            by[k, default: []].append(NoteItem(text: t, ts: ts.isEmpty ? nil : ts))
        }
        let groups = keys.compactMap { k in by[k].map { NoteGroup(label: k.map { label($0) ?? $0 }, items: $0) } }
        return groups.isEmpty ? nil : NoteSection(heading: heading, groups: groups)
    }

    /// The action items (raw `- text [m:ss]` lines) as one unlabelled group under the actions heading; trivial and
    /// `-` lines dropped, a line that does not match kept as plain item text.
    static func actionsSection(_ raw: String, heading: String = L("export_heading_actions")) -> NoteSection? {
        let items = raw.components(separatedBy: "\n").compactMap { l -> NoteItem? in
            let line = l.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { return nil }
            var it = NoteCites.itemLine(line).map { NoteItem(text: $0.text, ts: $0.ts) } ?? NoteItem(text: line, ts: nil)
            while it.text.hasSuffix("。") { it.text.removeLast() }
            return NoteCites.trivial(it.text) ? nil : it
        }
        return items.isEmpty ? nil : NoteSection(heading: heading, groups: [NoteGroup(label: nil, items: items)])
    }

    /// A note's text with its cite at the end, glued (`…條款。⁠[1:12]`).
    static func line(_ i: NoteItem) -> String { i.text + NoteCites.itemCite(i.text, i.ts) }

    /// Summary prose: paragraphs (blank-line separated) with their cites moved to the clause end.
    static func summary(_ t: String) -> String { paragraphs(t).joined(separator: "\n\n") }

    /// The summary's paragraphs after `NoteCites.clauseEndCites`. A long one (over 350 characters when mostly Han,
    /// 600 otherwise) is cut at cite boundaries (right after a `[…]` cite and its space) nearest every 250 / 450
    /// characters; the space is dropped at the cut, so a cut never falls inside a sentence.
    static func paragraphs(_ t: String) -> [String] {
        t.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.flatMap { raw -> [String] in
            let p = NoteCites.clauseEndCites(raw)
            let c = Array(p), letters = c.filter { !$0.isWhitespace }
            let han = letters.filter { $0.unicodeScalars.first.map { (0x3400...0x9FFF).contains($0.value) } ?? false }.count
            let (limit, step) = han * 2 > letters.count ? (350, 250) : (600, 450)
            if c.count <= limit { return [p] }
            // Cut candidates: the index of the space right after a cite.
            var cuts: [Int] = []
            for i in 1..<c.count where c[i] == " " && c[i - 1] == "]" { cuts.append(i) }
            var out: [String] = [], start = 0
            while c.count - start > limit {
                let goal = start + step
                guard let best = cuts.filter({ $0 > start && c.count - $0 > 1 }).min(by: { abs($0 - goal) < abs($1 - goal) }) else { break }
                out.append(String(c[start..<best])); start = best + 1
            }
            out.append(String(c[start...]))
            return out
        }
    }

    static let citeRe = try! NSRegularExpression(pattern: #"\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)\]"#)
    static let timeRe = try! NSRegularExpression(pattern: #"\d+:\d{2}(?::\d{2})?"#)

    // MARK: PDF (share v2)

    /// Layout marks carried by the attributed text and turned into PDF links / destinations / rules when drawn.
    private static let kLink = NSAttributedString.Key("pmLink"), kDest = NSAttributedString.Key("pmDest")
    private static let kBack = NSAttributedString.Key("pmBack"), kRule = NSAttributedString.Key("pmRule"), kHead = NSAttributedString.Key("pmHead")

    /// The system font with PingFang in the script of the interface first in its cascade (TC glyphs for Traditional,
    /// SC for Simplified); CoreText embeds the subset of every font it draws with.
    private static func font(_ size: CGFloat, _ w: UIFont.Weight = .regular, mono: Bool = false) -> UIFont {
        let base = mono ? UIFont.monospacedDigitSystemFont(ofSize: size, weight: w) : UIFont.systemFont(ofSize: size, weight: w)
        let sc = AppLanguage.current.script == .simplified
        let cjk = (sc ? "PingFangSC-" : "PingFangTC-") + (w == .regular ? "Regular" : "Semibold")
        let d = base.fontDescriptor.addingAttributes([.cascadeList: [UIFontDescriptor(name: cjk, size: size)]])
        return UIFont(descriptor: d, size: size)
    }
    private static let ink = UIColor(red: 0x1C / 255, green: 0x24 / 255, blue: 0x30 / 255, alpha: 1)
    private static let grey = UIColor(red: 0x5A / 255, green: 0x69 / 255, blue: 0x82 / 255, alpha: 1)
    private static let accent = UIColor(red: 0x1F / 255, green: 0x5F / 255, blue: 0xAE / 255, alpha: 1)
    private static let ruleColor = UIColor(white: 0.82, alpha: 1)

    private static func ps(before: CGFloat = 0, after: CGFloat = 0, head: CGFloat = 0, first: CGFloat = 0, tab: CGFloat? = nil, line: CGFloat = 3) -> NSParagraphStyle {
        let p = NSMutableParagraphStyle(); p.lineSpacing = line; p.paragraphSpacingBefore = before; p.paragraphSpacing = after
        p.headIndent = head; p.firstLineHeadIndent = first
        if let tab { p.tabStops = [NSTextTab(textAlignment: .left, location: tab)]; p.defaultTabInterval = tab }
        return p
    }

    /// The meeting's duration in words ("42 分鐘", "1 hr 5 min") in the interface language.
    static func durationText(_ sec: Double) -> String {
        let f = DateComponentsFormatter(); var cal = Calendar.current; cal.locale = AppLanguage.current.locale; f.calendar = cal
        f.unitsStyle = .short; f.allowedUnits = sec >= 3600 ? [.hour, .minute] : sec >= 60 ? [.minute] : [.second]
        return f.string(from: max(1, sec.rounded())) ?? Export.mmss(sec)
    }

    /// The summary + transcript PDF: a title block (title, date, duration, speakers), the summary, the action items,
    /// then the transcript (the AI notes stay in the .pmin) as speaker turns (grey clock gutter, hanging indent). Every cite is a link to
    /// the turn it cites; every turn is a destination with a "↑" back to the first cite of it (else to the summary).
    /// A4, CJK in PingFang (embedded subsets), a running title and page numbers.
    static func pdf(_ s1: Session) -> Data {
        let s0 = inScript(s1)
        var s = s0
        s.title = refs(s0.title, s0); s.summary = refs(s0.summary, s0)
        let acts = s0.actionItems.map { refs($0, s0) }
        let ts = turns(s.lines), starts = ts.filter { !$0.cont }.map { Int(max(0, $0.u.start)) }
        let out = NSMutableAttributedString()
        var citeN = 0, citeTargets: [(cite: Int, turnSec: Int)] = []
        func add(_ t: String, _ a: [NSAttributedString.Key: Any]) { out.append(NSAttributedString(string: t, attributes: a)) }
        let body = font(11), bodyAttrs: [NSAttributedString.Key: Any] = [.font: body, .foregroundColor: ink]

        // Title block.
        let title = s.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? L("export_heading_transcript") : s.title
        add(title + "\n", [.font: font(21, .semibold), .foregroundColor: ink, .paragraphStyle: ps(after: 8, line: 2), kDest: "top"])
        var meta: [String] = []
        if let d = s0.meetingStart {
            let f = DateFormatter(); f.locale = AppLanguage.current.locale; f.dateStyle = .long; f.timeStyle = .short; meta.append(f.string(from: d))
        }
        meta.append(durationText(s0.seconds))
        var order: [Int] = []; for l in s.lines where !order.contains(l.speaker) { order.append(l.speaker) }
        let zh = AppLanguage.current.resolved != .en
        let metaAttrs: [NSAttributedString.Key: Any] = [.font: font(10.5), .foregroundColor: grey, .paragraphStyle: ps(after: 2)]
        add(meta.joined(separator: " · ") + "\n", metaAttrs)
        if !order.isEmpty { add(L("share_pdf_speakers", order.map { s.name($0) }.joined(separator: zh ? "、" : ", ")) + "\n", metaAttrs) }
        out.addAttribute(kRule, value: true, range: NSRange(location: out.length - 1, length: 1))

        func heading(_ h: String, dest: String) {
            let at = out.length
            add(h + "\n", [.font: font(13.5, .semibold), .foregroundColor: accent, .paragraphStyle: ps(before: 18, after: 8), kHead: true])
            out.addAttribute(kDest, value: dest, range: NSRange(location: at, length: 1))
            out.addAttribute(kRule, value: true, range: NSRange(location: out.length - 1, length: 1))
        }
        /// Text with its cites in the accent colour, each time a link to its turn and each cite a back-link target.
        func cited(_ t: String, _ a: [NSAttributedString.Key: Any]) {
            let at = out.length, ns = t as NSString
            add(t, a)
            for m in citeRe.matches(in: t, range: NSRange(location: 0, length: ns.length)) {
                let r = NSRange(location: at + m.range.location, length: m.range.length)
                out.addAttributes([.foregroundColor: accent, .font: font(9.5, mono: true)], range: r)
                citeN += 1
                out.addAttribute(kDest, value: "c-\(citeN)", range: NSRange(location: r.location, length: 1))
                let inner = ns.substring(with: m.range(at: 1)) as NSString
                for tm in timeRe.matches(in: inner as String, range: NSRange(location: 0, length: inner.length)) {
                    guard let i = ShareCore.target(ShareCore.seconds(inner.substring(with: tm.range)), starts) else { continue }
                    citeTargets.append((citeN, starts[i]))
                    out.addAttribute(kLink, value: ShareCore.turnDest(starts[i]), range: NSRange(location: at + m.range(at: 1).location + tm.range.location, length: tm.range.length))
                }
            }
        }

        if !s.summary.isEmpty {
            heading(L("export_heading_summary"), dest: ShareCore.summaryDest)
            for p in paragraphs(s.summary) { cited(p + "\n", bodyAttrs.merging([.paragraphStyle: ps(after: 7, line: 4)]) { $1 }) }
        } else { out.addAttribute(kDest, value: ShareCore.summaryDest, range: NSRange(location: 0, length: 1)) }
        let bullet = ps(after: 4, head: 14, tab: 14, line: 3)
        func section(_ sec: NoteSection, dest: String) {
            heading(sec.heading, dest: dest)
            for g in sec.groups {
                if let l = g.label { add(l + "\n", [.font: font(11, .semibold), .foregroundColor: ink, .paragraphStyle: ps(before: 6, after: 3), kHead: true]) }
                for i in g.items { add("•\t", bodyAttrs.merging([.paragraphStyle: bullet, .foregroundColor: grey]) { $1 }); cited(line(i) + "\n", bodyAttrs.merging([.paragraphStyle: bullet]) { $1 }) }
            }
        }
        if let a = acts, let sec = actionsSection(a) { section(sec, dest: "actions") }   // share v2: always, after the summary

        // Transcript: `clock⇥Speaker  text`, wrapped lines hanging at the gutter; a same-speaker line has no clock.
        if !ts.isEmpty {
            heading(L("export_heading_transcript"), dest: "transcript")
            let clockFont = font(9, mono: true), pad = gutterWidth(s.lines)
            let gutter = ("00:00:00".suffix(pad) as NSString).size(withAttributes: [.font: clockFont]).width + 12
            var named = Set<Int>()
            for l in ts {
                let at = out.length
                if l.cont {
                    add("\t" + l.text + "\n", bodyAttrs.merging([.paragraphStyle: ps(before: 2, head: gutter, tab: gutter)]) { $1 })
                    continue
                }
                let sec = Int(max(0, l.u.start)), style = ps(before: 8, head: gutter, tab: gutter)
                add(gutterClock(l.u.start, pad) + "\t", [.font: clockFont, .foregroundColor: grey, .paragraphStyle: style])
                add(s.name(l.u.speaker) + "  ", [.font: font(11, .semibold), .foregroundColor: ink, .paragraphStyle: style])
                add(l.text + "\n", bodyAttrs.merging([.paragraphStyle: style]) { $1 })
                if named.insert(sec).inserted { out.addAttribute(kDest, value: ShareCore.turnDest(sec), range: NSRange(location: at, length: 1)) }
                out.addAttribute(kBack, value: ShareCore.backDest(turnSec: sec, citeTargets: citeTargets), range: NSRange(location: at, length: 1))
            }
        }
        return render(out, title: title)
    }

    private static func render(_ text: NSAttributedString, title: String) -> Data {
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let margin = UIEdgeInsets(top: 62, left: 58, bottom: 62, right: 58)
        // The text box in CoreText's bottom-up space.
        let box = CGRect(x: margin.left, y: margin.bottom, width: page.width - margin.left - margin.right, height: page.height - margin.top - margin.bottom)
        let ns = text.string as NSString, len = text.length
        var dests: [Int: String] = [:], backs: [Int: String] = [:], rules = Set<Int>(), heads = Set<Int>(), links: [(NSRange, String)] = []
        text.enumerateAttributes(in: NSRange(location: 0, length: len)) { a, r, _ in
            if let d = a[kDest] as? String { dests[r.location] = d }
            if let d = a[kBack] as? String { backs[r.location] = d }
            if a[kRule] != nil { rules.insert(r.location) }
            if a[kHead] != nil { for i in r.location..<(r.location + r.length) { heads.insert(i) } }
            if let d = a[kLink] as? String { links.append((r, d)) }
        }
        let setter = CTFramesetterCreateWithAttributedString(text)
        let path = CGPath(rect: box, transform: nil)
        // Pages first (the footer needs the count); a heading never ends a page.
        var pages: [CFRange] = [], at = 0
        while at < len {
            let f = CTFramesetterCreateFrame(setter, CFRange(location: at, length: 0), path, nil)
            var n = CTFrameGetVisibleStringRange(f).length
            if n == 0 { break }
            let lines = CTFrameGetLines(f) as! [CTLine]
            if at + n < len {
                // Trailing heading lines (a section heading, then a group label) go to the next page together.
                var k = lines.count
                while k > 1, heads.contains(CTLineGetStringRange(lines[k - 1]).location) { k -= 1 }
                if k < lines.count { n = CTLineGetStringRange(lines[k]).location - at }
            }
            pages.append(CFRange(location: at, length: n)); at += n
        }
        func small(_ t: String, _ size: CGFloat, _ color: UIColor) -> CTLine {
            CTLineCreateWithAttributedString(NSAttributedString(string: t, attributes: [.font: font(size), .foregroundColor: color]))
        }
        let back = small("↑ " + L("share_pdf_back"), 7.5, accent), backW = CTLineGetTypographicBounds(back, nil, nil, nil)
        return UIGraphicsPDFRenderer(bounds: page).pdfData { ctx in
            for (pi, pr) in pages.enumerated() {
                ctx.beginPage()
                let c = ctx.cgContext
                c.translateBy(x: 0, y: page.height); c.scaleBy(x: 1, y: -1)   // CoreText draws bottom-up
                c.textMatrix = .identity
                // Running title (from page 2) and the page number.
                if pi > 0 {
                    let h = small(String(title.prefix(70)), 8, grey); c.textPosition = CGPoint(x: margin.left, y: page.height - 36); CTLineDraw(h, c)
                    c.setStrokeColor(ruleColor.cgColor); c.setLineWidth(0.5)
                    c.move(to: CGPoint(x: margin.left, y: page.height - 42)); c.addLine(to: CGPoint(x: page.width - margin.right, y: page.height - 42)); c.strokePath()
                }
                let num = small("\(pi + 1) / \(pages.count)", 8, grey)
                c.textPosition = CGPoint(x: (page.width - CTLineGetTypographicBounds(num, nil, nil, nil)) / 2, y: 32); CTLineDraw(num, c)

                let frame = CTFramesetterCreateFrame(setter, pr, path, nil)
                CTFrameDraw(frame, c)
                let lines = CTFrameGetLines(frame) as! [CTLine]
                var origins = [CGPoint](repeating: .zero, count: lines.count)
                CTFrameGetLineOrigins(frame, CFRange(location: 0, length: 0), &origins)
                for (li, l) in lines.enumerated() {
                    let lr = CTLineGetStringRange(l), o = CGPoint(x: box.minX + origins[li].x, y: box.minY + origins[li].y)
                    var asc: CGFloat = 0, desc: CGFloat = 0, lead: CGFloat = 0
                    _ = CTLineGetTypographicBounds(l, &asc, &desc, &lead)
                    let end = lr.location + lr.length
                    for loc in lr.location..<end {
                        if let d = dests[loc] { UIGraphicsAddPDFContextDestinationAtPoint(d, CGPoint(x: box.minX, y: min(page.height, o.y + asc + 240))) }   // Quick Look scrolls the point under its top bar: room above the line
                        if let d = backs[loc] {
                            c.textPosition = CGPoint(x: page.width - margin.right + 8, y: o.y); CTLineDraw(back, c)
                            UIGraphicsSetPDFContextDestinationForRect(d, CGRect(x: page.width - margin.right + 6, y: o.y - 3, width: backW + 4, height: 12))
                        }
                        if rules.contains(loc), loc == end - 1 || ns.character(at: loc) == 10 {
                            c.setStrokeColor(ruleColor.cgColor); c.setLineWidth(0.6)
                            c.move(to: CGPoint(x: box.minX, y: o.y - desc - 5)); c.addLine(to: CGPoint(x: box.maxX, y: o.y - desc - 5)); c.strokePath()
                        }
                    }
                    for (r, d) in links {
                        let a = max(r.location, lr.location), b = min(r.location + r.length, end)
                        guard a < b else { continue }
                        let x1 = CTLineGetOffsetForStringIndex(l, a, nil), x2 = CTLineGetOffsetForStringIndex(l, b, nil)
                        UIGraphicsSetPDFContextDestinationForRect(d, CGRect(x: o.x + x1 - 2, y: o.y - desc - 1, width: x2 - x1 + 4, height: asc + desc + 2))
                    }
                }
            }
        }
    }

    /// The Word document's model: the same content and order as the PDF (title block, summary, action items,
    /// transcript turns).
    static func docModel(_ s0: Session) -> ShareDocx.Doc {
        let d = rawDocModel(s0)
        guard let c = scriptConverters() else { return d }
        return ExportScript.doc(d, generated: c.gen.convert, transcript: c.tr.convert)
    }
    private static func rawDocModel(_ s0: Session) -> ShareDocx.Doc {
        var s = s0
        s.title = refs(s0.title, s0); s.summary = refs(s0.summary, s0)
        let zh = AppLanguage.current.resolved != .en
        var meta: [String] = []
        if let d = s0.meetingStart { let f = DateFormatter(); f.locale = AppLanguage.current.locale; f.dateStyle = .long; f.timeStyle = .short; meta.append(f.string(from: d)) }
        meta = [(meta + [durationText(s0.seconds)]).joined(separator: " · ")]
        var order: [Int] = []; for l in s.lines where !order.contains(l.speaker) { order.append(l.speaker) }
        if !order.isEmpty { meta.append(L("share_pdf_speakers", order.map { s.name($0) }.joined(separator: zh ? "、" : ", "))) }
        let acts = s0.actionItems.map { refs($0, s0) }.flatMap { actionsSection($0) }
        let pad = gutterWidth(s.lines)
        return ShareDocx.Doc(
            title: s.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? L("export_heading_transcript") : s.title, meta: meta,
            summaryHeading: L("export_heading_summary"), summary: s.summary.isEmpty ? [] : paragraphs(s.summary),
            sections: acts.map { a in [ShareDocx.Section(heading: a.heading, groups: a.groups.map { ShareDocx.Group(label: $0.label, items: $0.items.map(line)) })] } ?? [],
            transcriptHeading: L("export_heading_transcript"),
            turns: turns(s.lines).map { ShareDocx.Turn(clock: gutterClock($0.u.start, pad), speaker: s.name($0.u.speaker), text: $0.text, sec: Int(max(0, $0.u.start)), cont: $0.cont) },
            backLabel: L("share_pdf_back"), zh: zh, simplified: AppLanguage.current.script == .simplified)
    }
    /// The converters of the script generation uses (AppLanguage.script; an English interface keeps it, as on Android).
    private static func scriptConverters() -> (gen: OpenCC, tr: OpenCC)? {
        let sc = AppLanguage.current.script
        return (OpenCC.generated(sc), OpenCC.transcript(sc))
    }
    /// The meeting as the PDF shows it: Chinese text in the interface's script (the stored session is not changed).
    static func inScript(_ s0: Session) -> Session {
        guard let c = scriptConverters() else { return s0 }
        let g = c.gen.convert, t = c.tr.convert
        var s = s0
        s.title = g(s.title); s.summary = g(s.summary)
        s.lines = s.lines.map { var u = $0; u.text = t(u.text); return u }
        s.notes = s.notes.map { var n = $0; n.text = g(n.text); return n }
        s.speakerNames = ExportScript.names(s.speakerNames, generated: g)
        s.actionsEdited = s.actionsEdited.map(g); s.legacyNotes = s.legacyNotes.map(g)
        return s
    }
    static func docxFile(_ s: Session, base: String, dir: URL) -> URL? {
        let url = dir.appendingPathComponent(base + ".docx")
        do { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true); try ShareDocx.data(docModel(s)).write(to: url, options: .atomic); return url } catch { return nil }
    }

    /// Writes the PDF to `dir` as `<base>.pdf`.
    static func pdfFile(_ s: Session, base: String, dir: URL) -> URL? {
        let url = dir.appendingPathComponent(base + ".pdf")
        do { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true); try pdf(s).write(to: url, options: .atomic); return url } catch { return nil }
    }

}
