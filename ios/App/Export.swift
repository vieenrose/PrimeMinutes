import UIKit

/// Session export (Android ExportSheet subset): MD, TXT, SRT, VTT, PDF. Files are written to the temp dir for the share sheet.
enum Export {
    enum Format: String, CaseIterable, Identifiable { case md, txt, srt, vtt, lrc, pdf; var id: String { rawValue } }

    static func clock(_ t: Double, comma: Bool = false) -> String {
        let ms = Int((t * 1000).rounded()); let h = ms / 3_600_000, m = ms / 60_000 % 60, s = ms / 1000 % 60
        return String(format: "%02d:%02d:%02d%@%03d", h, m, s, comma ? "," : ".", ms % 1000)
    }
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
    private static func refs(_ t: String, _ s: Session) -> String { SpeakerRefs.resolve(t, label: { s.name($0) }, known: Set(s.lines.map(\.speaker))) }
    static func text(_ s0: Session, _ f: Format) -> String {
        var s = s0
        s.title = refs(s0.title, s0); s.summary = refs(s0.summary, s0); let acts = s0.actionItems.map { refs($0, s0) }
        s.notes = s0.notes.map { var n = $0; n.text = refs($0.text, s0); return n }
        switch f {
        case .srt:
            return cues(s.lines).enumerated().map { i, l in "\(i + 1)\n\(clock(l.start, comma: true)) --> \(clock(endOf(l), comma: true))\n\(oneCue(s.name(l.speaker))): \(oneCue(l.text))\n" }.joined(separator: "\n")
        case .vtt:
            return "WEBVTT\n\n" + cues(s.lines).map { "\(clock($0.start)) --> \(clock(endOf($0)))\n\(vtt(oneCue(s.name($0.speaker)))): \(vtt($0.text))\n" }.joined(separator: "\n")
        case .lrc:
            let head = s.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : "[ti:\(s.title)]\n"
            return head + s.lines.compactMap { l in
                let x = l.text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces); guard !x.isEmpty else { return nil }
                let cs = Int(max(0, l.start) * 100)
                return String(format: "[%02d:%02d.%02d]", cs / 6000, cs / 100 % 60, cs % 100) + "\(s.name(l.speaker)): \(x)" }.joined(separator: "\n") + "\n"
        case .txt, .pdf:
            var o = s.title + "\n\n"
            if !s.summary.isEmpty { o += plainHeading(L("export_heading_summary")) + "\n" + summary(s.summary) + "\n\n" }
            if Prefs.showActions, let a = acts, let sec = actionsSection(a) { o += plain(sec) + "\n\n" }
            if let n = notesSection(s.notes) { o += plain(n) + "\n\n" }
            // Speaker turns: `[mm:ss] Speaker: text` opens a turn, a blank line separates turns, a further line of the
            // same speaker hangs under the text. The `[mm:ss]` token is what ReaderProtocol / the anchors parse.
            let pad = gutterWidth(s.lines), hang = String(repeating: " ", count: pad + 3)
            if !turns(s.lines).isEmpty { o += plainHeading(L("export_heading_transcript")) + "\n" }
            return o + turns(s.lines).map { l in l.cont ? hang + l.text : "\n[\(gutterClock(l.u.start, pad))] \(s.name(l.u.speaker)): \(l.text)" }
                .joined(separator: "\n").dropFirst().appending(turns(s.lines).isEmpty ? "" : "\n")
        case .md:
            // Each turn opens with an anchor `t-<start second>` (once per second); every cite links to its turn.
            let ts = turns(s.lines), starts = ts.filter { !$0.cont }.map { Int(max(0, $0.u.start)) }
            var o = "# \(s.title.isEmpty ? L("export_heading_transcript") : s.title)\n\n"
            if !s.summary.isEmpty { o += "## " + L("export_heading_summary") + "\n\n" + mdCites(summary(s.summary), starts) + "\n\n" }
            if Prefs.showActions, let a = acts, let sec = actionsSection(a) { o += markdown(sec, starts) + "\n\n" }
            if let n = notesSection(s.notes) { o += markdown(n, starts) + "\n\n" }
            let pad = gutterWidth(s.lines)
            var named = Set<Int>()
            return o + "## " + L("export_heading_transcript") + "\n\n" + ts.map { l -> String in
                if l.cont { return "  " + l.text }
                let sec = Int(max(0, l.u.start)), anchor = named.insert(sec).inserted ? "<a id=\"t-\(sec)\"></a>" : ""
                return "\n- \(anchor)`\(gutterClock(l.u.start, pad))` **\(s.name(l.u.speaker))** \(l.text)" }
                .joined(separator: "\n").dropFirst().appending(ts.isEmpty ? "" : "\n")
        }
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
            if MeetingMail.trivial(t) { continue }
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
            var it = MeetingMail.itemLine(line).map { NoteItem(text: $0.text, ts: $0.ts) } ?? NoteItem(text: line, ts: nil)
            while it.text.hasSuffix("。") { it.text.removeLast() }
            return MeetingMail.trivial(it.text) ? nil : it
        }
        return items.isEmpty ? nil : NoteSection(heading: heading, groups: [NoteGroup(label: nil, items: items)])
    }

    /// A note's text with its cite at the end, glued (`…條款。⁠[1:12]`).
    static func line(_ i: NoteItem) -> String { i.text + MeetingMail.itemCite(i.text, i.ts) }

    /// Summary prose: paragraphs (blank-line separated) with their cites moved to the clause end.
    static func summary(_ t: String) -> String { paragraphs(t).joined(separator: "\n\n") }

    /// The summary's paragraphs after `MeetingMail.clauseEndCites`. A long one (over 350 characters when mostly Han,
    /// 600 otherwise) is cut at cite boundaries (right after a `[…]` cite and its space) nearest every 250 / 450
    /// characters; the space is dropped at the cut, so a cut never falls inside a sentence.
    static func paragraphs(_ t: String) -> [String] {
        t.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.flatMap { raw -> [String] in
            let p = MeetingMail.clauseEndCites(raw)
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

    /// Markdown cites: `[0:15]` → `[0:15](#t-15)`, the anchor of the last turn starting at or before it (else the
    /// first); a multi-cite `[a, b]` → `[a](#t-A), [b](#t-B)`, separator kept. No turns (`starts` empty) → `` `0:15` ``.
    static func mdCites(_ t: String, _ starts: [Int] = []) -> String {
        let ns = t as NSString
        var out = "", at = 0
        for m in citeRe.matches(in: t, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: at, length: m.range.location - at)); at = m.range.location + m.range.length
            let inner = ns.substring(with: m.range(at: 1))
            if starts.isEmpty { out += "`\(inner)`"; continue }
            let ins = inner as NSString
            var j = 0
            for tm in timeRe.matches(in: inner, range: NSRange(location: 0, length: ins.length)) {
                out += ins.substring(with: NSRange(location: j, length: tm.range.location - j))   // the separator
                let v = ins.substring(with: tm.range)
                out += "[\(v)](#t-\(starts[target(seconds(v), starts)!]))"; j = tm.range.location + tm.range.length
            }
        }
        return out + ns.substring(from: at)
    }

    private static func isZh(_ s: String) -> Bool { s.unicodeScalars.contains { (0x3400...0x9FFF).contains($0.value) } }

    /// Plain-text heading, as the email's plain text: `【摘要】` in Chinese, upper case in English.
    static func plainHeading(_ h: String) -> String { isZh(h) ? "【\(h)】" : h.uppercased() }

    /// The AI notes as plain text: the heading, then `Label:` sub-headings and bulleted lines.
    static func plain(_ s: NoteSection) -> String {
        let zh = isZh(s.heading), bullet = zh ? "・" : "- "
        var o = plainHeading(s.heading) + "\n"
        for (gi, g) in s.groups.enumerated() {
            if gi > 0 { o += "\n" }
            if let l = g.label { o += l + (zh ? "：" : ":") + "\n" }
            for i in g.items { o += bullet + line(i) + "\n" }
        }
        while o.hasSuffix("\n") { o.removeLast() }
        return o
    }

    /// The AI notes as Markdown: `## heading`, `### label` per type, `- text。⁠`0:15`` bullets.
    static func markdown(_ s: NoteSection, _ starts: [Int] = []) -> String {
        var o = "## " + s.heading + "\n\n"
        for g in s.groups {
            if let l = g.label { o += "### " + l + "\n\n" }
            for i in g.items { o += "- " + mdCites(line(i), starts) + "\n" }
            o += "\n"
        }
        while o.hasSuffix("\n") { o.removeLast() }
        return o
    }

    /// `m:ss` / `h:mm:ss` → seconds.
    static func seconds(_ t: String) -> Int { t.split(separator: ":").reduce(0) { $0 * 60 + (Int($1) ?? 0) } }

    /// The transcript turn a cite at `sec` jumps to: the last one starting at or before it (else the first).
    static func target(_ sec: Int, _ starts: [Int]) -> Int? {
        guard !starts.isEmpty else { return nil }
        var best = 0
        for (i, s) in starts.enumerated() where s <= sec { best = i }
        return best
    }

    /// Android TranscriptExport.cues: a long turn is cut at punctuation into subtitles of ≤64 display cells
    /// (a CJK character is 2: 32 Chinese characters or 64 Latin ones), timed by length. A cut forced by
    /// length backs off to the last space so no word is split.
    static func cues(_ ls: [Utterance]) -> [Utterance] {
        ls.flatMap { u -> [Utterance] in
            let text = oneCue(u.text)
            if text.isEmpty { return [] }
            var u0 = u; u0.text = text
            if width(text) <= 64 { return [u0] }
            var pieces: [String] = [], sb = "", w = 0
            for c in text {
                sb.append(c); w += width(c)
                let soft = "，、,；;：:".contains(c) && w >= 32, hard = "。？！?!.".contains(c)
                if hard || soft { pieces.append(sb.trimmingCharacters(in: .whitespaces)); sb = ""; w = 0 }
                else if w >= 64 {
                    if let cut = sb.lastIndex(of: " "), cut > sb.startIndex {
                        pieces.append(String(sb[..<cut]).trimmingCharacters(in: .whitespaces))
                        sb = String(sb[sb.index(after: cut)...]); w = width(sb)
                    } else { pieces.append(sb.trimmingCharacters(in: .whitespaces)); sb = ""; w = 0 }
                }
            }
            if !sb.trimmingCharacters(in: .whitespaces).isEmpty { pieces.append(sb.trimmingCharacters(in: .whitespaces)) }
            pieces = pieces.filter { !$0.isEmpty }
            let total = Double(max(1, pieces.reduce(0) { $0 + $1.count })), dur = endOf(u) - u.start
            var at = u.start, used = 0
            return pieces.map { p in used += p.count; let e = u.start + dur * Double(used) / total
                var c = u; c.id = UUID(); c.text = p; c.start = at; c.end = e; at = e; return c }
        }
    }
    static func endOf(_ u: Utterance) -> Double { u.end > u.start ? u.end : u.start + 1 }
    /// Display cells: CJK ideographs, kana, hangul and fullwidth forms take 2, anything else 1.
    static func width(_ c: Character) -> Int {
        guard let v = c.unicodeScalars.first?.value else { return 1 }
        return (0x2E80...0x9FFF).contains(v) || (0xAC00...0xD7A3).contains(v) || (0xF900...0xFAFF).contains(v) || (0xFF00...0xFF60).contains(v) ? 2 : 1
    }
    static func width(_ s: String) -> Int { s.reduce(0) { $0 + width($1) } }
    /// A blank line inside a cue ends it early and desyncs every later cue.
    static func oneCue(_ s: String) -> String { s.replacingOccurrences(of: #"\n\s*\n+"#, with: "\n", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines) }
    static func vtt(_ s: String) -> String { s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;") }

    /// Writes the export and returns its URL (nil when the write fails).
    static func file(_ s: Session, _ f: Format) -> URL? {
        let safe = String(s.title.map { "/\\:*?\"<>|".contains($0) ? "_" : $0 }.prefix(60))
        let url = FileManager.default.temporaryDirectory.appendingPathComponent((safe.isEmpty ? "Prime Minutes" : safe) + "." + f.rawValue)
        do {
            if f == .pdf { try pdf(text(s, .txt), title: s.title).write(to: url, options: .atomic) }
            else { try text(s, f).write(to: url, atomically: true, encoding: .utf8) }
            return url
        } catch { return nil }
    }

    /// A transcript turn line of the txt body: `[clock] Speaker: ` (group 2 = the label the body hangs under).
    private static let turnRe = try! NSRegularExpression(pattern: #"^\[(\d{2}:\d{2}(?::\d{2})?)\] ([^:\n]*?: )"#)

    static func pdf(_ body: String, title: String) -> Data {
        let page = CGRect(x: 0, y: 0, width: 595, height: 842), inset = page.insetBy(dx: 40, dy: 48)
        let para = NSMutableParagraphStyle(); para.lineSpacing = 3
        let text = NSMutableAttributedString(string: body, attributes: [.font: UIFont.systemFont(ofSize: 11), .paragraphStyle: para])
        let ns = body as NSString
        let clockFont = UIFont.monospacedDigitSystemFont(ofSize: 10, weight: .regular), hidden = UIFont.systemFont(ofSize: 0.01)
        let grey = UIColor(red: 0x5A / 255, green: 0x69 / 255, blue: 0x82 / 255, alpha: 1), bodyFont = UIFont.systemFont(ofSize: 11)
        func width<S: StringProtocol>(_ t: S, _ f: UIFont) -> CGFloat { (String(t) as NSString).size(withAttributes: [.font: f]).width }
        func style(_ before: CGFloat, head: CGFloat = 0, first: CGFloat = 0) -> NSParagraphStyle {
            let ps = NSMutableParagraphStyle(); ps.lineSpacing = 3; ps.paragraphSpacingBefore = before; ps.headIndent = head; ps.firstLineHeadIndent = first; return ps
        }
        let headings = Set(["export_heading_summary", "agent_title", "export_heading_transcript"].map { plainHeading(L($0)) } + [plainHeading(L("export_heading_actions"))])
        let labels = Set(tagOrder.compactMap(tagLabel).flatMap { [$0 + "：", $0 + ":"] })
        var hang: NSParagraphStyle?, inTranscript = false, inNotes = false
        var cites: [(range: NSRange, sec: Int)] = [], turnAt: [(loc: Int, sec: Int)] = []
        // Title line (bold 18).
        if !title.isEmpty { text.addAttribute(.font, value: UIFont.boldSystemFont(ofSize: 18), range: NSRange(location: 0, length: min((title as NSString).length, ns.length))) }
        ns.enumerateSubstrings(in: NSRange(location: 0, length: ns.length), options: [.byLines, .substringNotRequired]) { _, r, _, _ in
            let line = ns.substring(with: r)
            if !title.isEmpty, r.location == 0 { return }   // the title line is already styled above
            if headings.contains(line) {
                text.addAttributes([.font: UIFont.boldSystemFont(ofSize: 13), .paragraphStyle: style(14)], range: r)
                inTranscript = line == plainHeading(L("export_heading_transcript")); inNotes = line == plainHeading(L("agent_title")) || line == plainHeading(L("export_heading_actions")); hang = nil; return
            }
            if inNotes, labels.contains(line) || (line.hasSuffix("：") && !line.hasPrefix("・")) {
                text.addAttributes([.font: UIFont.boldSystemFont(ofSize: 11.5), .paragraphStyle: style(6)], range: r); return
            }
            // Transcript turns (design A): the clock in a grey left gutter, its brackets invisible (still in the copied
            // text), the body hanging under the text after the speaker label; continuation lines hang the same way.
            if inTranscript, let m = turnRe.firstMatch(in: line, range: NSRange(location: 0, length: r.length)) {
                let clock = (line as NSString).substring(with: m.range(at: 1)), who = (line as NSString).substring(with: m.range(at: 2))
                turnAt.append((r.location, seconds(clock)))
                let gutter = width("00:00:00".prefix(clock.count), clockFont) + 10
                text.addAttributes([.font: hidden, .foregroundColor: UIColor.clear], range: NSRange(location: r.location, length: 1))
                text.addAttributes([.font: clockFont, .foregroundColor: grey], range: NSRange(location: r.location + 1, length: m.range(at: 1).length))
                text.addAttributes([.font: hidden, .foregroundColor: UIColor.clear], range: NSRange(location: r.location + 1 + m.range(at: 1).length, length: 1))
                // The space after `]` pads the clock out to the gutter edge.
                let sp = NSRange(location: r.location + m.range(at: 1).length + 2, length: 1)
                text.addAttribute(.kern, value: gutter - width(clock, clockFont) - width(" ", bodyFont), range: sp)
                let ps = NSMutableParagraphStyle(); ps.lineSpacing = 3; ps.headIndent = gutter + width(who, bodyFont)
                text.addAttribute(.paragraphStyle, value: ps, range: r); hang = ps
                return
            } else if inTranscript, let h = hang, line.hasPrefix("  ") {
                let lead = line.prefix { $0 == " " }.count
                text.addAttribute(.font, value: hidden, range: NSRange(location: r.location, length: lead))
                let ps = NSMutableParagraphStyle(); ps.lineSpacing = 3; ps.headIndent = h.headIndent; ps.firstLineHeadIndent = h.headIndent
                text.addAttribute(.paragraphStyle, value: ps, range: r)
                return
            }
            if inTranscript { return }
            // Summary paragraphs and note bullets: grey cites, each time a link; bullets hang.
            if inNotes, line.hasPrefix("・") || line.hasPrefix("- ") {
                let b = line.hasPrefix("・") ? "・" : "- "
                text.addAttribute(.paragraphStyle, value: style(3, head: width(b, bodyFont)), range: r)
            } else if r.length > 0 { text.addAttribute(.paragraphStyle, value: style(6), range: r) }
            for m in citeRe.matches(in: body, range: r) {
                text.addAttribute(.foregroundColor, value: grey, range: m.range)
                for t in timeRe.matches(in: body, range: m.range) { cites.append((t.range, seconds(ns.substring(with: t.range)))) }
            }
        }
        // Each cite time links to its transcript turn (a named destination per turn start second).
        let starts = turnAt.map(\.sec)
        var linkOf: [Int: String] = [:]   // cite location → destination name
        for c in cites { if let i = target(c.sec, starts) { linkOf[c.range.location] = "t-\(starts[i])" } }
        var destAt: [Int: String] = [:], named = Set<String>()
        for t in turnAt where !named.contains("t-\(t.sec)") { named.insert("t-\(t.sec)"); destAt[t.loc] = "t-\(t.sec)" }
        let setter = CTFramesetterCreateWithAttributedString(text)
        var at = 0
        return UIGraphicsPDFRenderer(bounds: page).pdfData { ctx in
            repeat {
                ctx.beginPage()
                let c = ctx.cgContext
                c.translateBy(x: 0, y: page.height); c.scaleBy(x: 1, y: -1)   // CoreText draws bottom-up
                let box = CGRect(x: inset.minX, y: page.height - inset.maxY, width: inset.width, height: inset.height)
                let frame = CTFramesetterCreateFrame(setter, CFRange(location: at, length: 0), CGPath(rect: box, transform: nil), nil)
                CTFrameDraw(frame, c)
                // Links and destinations, in the same (flipped) user space CoreText drew in.
                let lines = CTFrameGetLines(frame) as! [CTLine]
                var origins = [CGPoint](repeating: .zero, count: lines.count)
                CTFrameGetLineOrigins(frame, CFRange(location: 0, length: 0), &origins)
                for (li, l) in lines.enumerated() {
                    let lr = CTLineGetStringRange(l), o = CGPoint(x: box.minX + origins[li].x, y: box.minY + origins[li].y)
                    var asc: CGFloat = 0, desc: CGFloat = 0, lead: CGFloat = 0
                    _ = CTLineGetTypographicBounds(l, &asc, &desc, &lead)
                    for loc in lr.location..<(lr.location + lr.length) {
                        if let d = destAt[loc] { UIGraphicsAddPDFContextDestinationAtPoint(d, CGPoint(x: 0, y: o.y + asc + 4)) }
                        guard let d = linkOf[loc], let cite = cites.first(where: { $0.range.location == loc }) else { continue }
                        let end = min(cite.range.location + cite.range.length, lr.location + lr.length)
                        let x1 = CTLineGetOffsetForStringIndex(l, loc, nil), x2 = CTLineGetOffsetForStringIndex(l, end, nil)
                        UIGraphicsSetPDFContextDestinationForRect(d, CGRect(x: o.x + x1 - 1, y: o.y - desc, width: x2 - x1 + 2, height: asc + desc))
                    }
                }
                let n = CTFrameGetVisibleStringRange(frame).length
                if n == 0 { break }
                at += n
            } while at < text.length
        }
    }
}
