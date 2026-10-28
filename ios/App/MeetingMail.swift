import Foundation

/// The logic-less Mustache subset of the participants e-mail templates (docs/design/speaker-contacts.md §5.3), the
/// same semantics as Android MiniMustache and the design's `render_samples.py`: `{{x}}` (HTML-escaped when `escape`),
/// `{{{x}}}` raw, `{{#x}}…{{/x}}` (a list repeats, a map or any other truthy value renders once), `{{^x}}…{{/x}}`, and the
/// standalone rule (a line holding only a section tag disappears with its newline).
enum MiniMustache {
    typealias Ctx = [String: Any]
    private static let tag = try! NSRegularExpression(pattern: #"\{\{(\{?)([#^/]?)\s*([\w.]+)\s*\}?\}\}"#)
    private static let standalone = try! NSRegularExpression(pattern: #"^[ \t]*(\{\{[#^/][^}]*\}\})[ \t]*\n"#, options: [.anchorsMatchLines])

    static func render(_ template: String, _ ctx: Ctx, escape: Bool) -> String {
        let t = standalone.stringByReplacingMatches(in: template, range: NSRange(template.startIndex..., in: template), withTemplate: "$1") as NSString
        var out = ""
        var pos = 0
        while true {
            guard let m = tag.firstMatch(in: t as String, range: NSRange(location: pos, length: t.length - pos)) else {
                out += t.substring(from: pos); break
            }
            out += t.substring(with: NSRange(location: pos, length: m.range.location - pos))
            let raw = m.range(at: 1).length > 0
            let kind = t.substring(with: m.range(at: 2)), name = t.substring(with: m.range(at: 3))
            let end = m.range.location + m.range.length
            if kind == "#" || kind == "^" {
                let (body, next) = section(t, end, name)
                pos = next
                let v = ctx[name]
                if kind == "^" { if !truthy(v) { out += render(body, ctx, escape: escape) } }
                else if let list = v as? [Any] {
                    for x in list {
                        var inner = ctx
                        if let d = x as? Ctx { inner.merge(d) { _, n in n } } else { inner["."] = x }
                        out += render(body, inner, escape: escape)
                    }
                } else if truthy(v) {
                    var inner = ctx
                    if let d = v as? Ctx { inner.merge(d) { _, n in n } }
                    out += render(body, inner, escape: escape)
                }
                continue
            }
            let v = string(ctx[name])
            out += escape && !raw ? escapeHtml(v) : v
            pos = end
        }
        return out
    }

    private static func string(_ v: Any?) -> String {
        switch v {
        case nil, is NSNull: return ""
        case let s as String: return s
        case let o?: return "\(o)"
        }
    }

    /// Python's truthiness, which the reference renderer uses.
    static func truthy(_ v: Any?) -> Bool {
        switch v {
        case nil, is NSNull: return false
        case let b as Bool: return b
        case let s as String: return !s.isEmpty
        case let a as [Any]: return !a.isEmpty
        case let d as Ctx: return !d.isEmpty
        case let n as Int: return n != 0
        default: return true
        }
    }

    private static func section(_ t: NSString, _ start: Int, _ name: String) -> (String, Int) {
        var depth = 1, i = start
        while let m = tag.firstMatch(in: t as String, range: NSRange(location: i, length: t.length - i)) {
            let k = t.substring(with: m.range(at: 2)), n = t.substring(with: m.range(at: 3))
            if n == name && (k == "#" || k == "^") { depth += 1 }
            else if n == name && k == "/" {
                depth -= 1
                if depth == 0 { return (t.substring(with: NSRange(location: start, length: m.range.location - start)), m.range.location + m.range.length) }
            }
            i = m.range.location + m.range.length
        }
        return (t.substring(from: start), t.length)
    }

    /// Python's `html.escape(s, quote=False)`.
    static func escapeHtml(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }
}

/// The participants e-mail (docs/design/speaker-contacts.md §5): a DETERMINISTIC template over what the app already
/// has — the prose summary the user saw, the minutes grouped from the typed notes, the meeting facts — with the fixed
/// "machine-generated" note. No model writes anything at send time. Same output as Android MeetingMail (golden files).
enum MeetingMail {
    struct Item: Equatable { var text: String; var ts: String? }
    struct Section: Equatable { var heading: String; var tag: String; var items: [Item] }

    struct Input {
        var title: String
        var summary: String?
        var sections: [Section]
        /// Speaker id → the name the meeting shows.
        var names: [Int: String]
        var participants: [String]
        var unnamed: Int
        var start: Date
        var timeZone: TimeZone
        var durationSec: Double
        /// False when the meeting's start is unknown (an import whose file has no date): no time line.
        var timeKnown = true
    }

    struct Options: Equatable {
        var summary = true
        var minutes = true
        /// Only these section tags ("Decisions and actions only"), or nil.
        var only: Set<String>? = nil
        var attachments: [String] = []
        /// Phase D (off): a model-written lead; the app passes nil.
        var lead: String? = nil
        var budget: Int? = nil
    }

    struct Mail { var subject: String; var plain: String; var html: String; var chat: String; var truncated: Bool }

    static let composerBudget = 20_000, chatBudget = 2_000, mailtoBudgetZh = 1_500, mailtoBudgetEn = 2_000
    static let langs = ["zh-Hant", "zh-Hans", "en"]

    struct Templates {
        var txt: String, html: String, chat: String, strings: [String: Any]
        /// The bundled `mail/` resources (VOX_MAIL_DIR overrides them for host tests).
        static let shipped: Templates = {
            let dir: URL
            if let d = ProcessInfo.processInfo.environment["VOX_MAIL_DIR"] { dir = URL(fileURLWithPath: d) }
            else { dir = Bundle.main.resourceURL!.appendingPathComponent("mail") }
            func r(_ n: String) -> String { (try? String(contentsOf: dir.appendingPathComponent(n), encoding: .utf8)) ?? "" }
            let s = (try? JSONSerialization.jsonObject(with: Data(r("strings.json").utf8))) as? [String: Any] ?? [:]
            return Templates(txt: r("meeting_mail.txt"), html: r("meeting_mail.html"), chat: r("chat.txt"), strings: s)
        }()
    }

    // MARK: minutes

    private static let itemRe = try! NSRegularExpression(pattern: #"^-\s*(.*?)\s*(?:\[(\d+:\d{2}(?::\d{2})?)\])?\s*$"#)
    private static let headRe = try! NSRegularExpression(pattern: #"^【(.+)】$"#)
    private static let empty: Set<String> = ["無", "无", "None", "-", ""]

    private static func groups(_ re: NSRegularExpression, _ s: String) -> [String?]? {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)), m.range.location == 0, m.range.length == ns.length else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? nil : ns.substring(with: m.range(at: $0)) }
    }

    /// The minutes text (`【heading】` then `- text [ts]`), as sections; empty ones dropped.
    static func parseMinutes(_ raw: String, _ sections: [(String, String)]) -> [Section] {
        var tags: [String: String] = [:]
        for (h, t) in sections { tags[h] = t }
        var out: [(String, [Item])] = []
        for l in raw.components(separatedBy: "\n") {
            let line = l.trimmingCharacters(in: .whitespaces)
            if let g = groups(headRe, line), let h = g[1] { out.append((h, [])); continue }
            if line.hasPrefix("-"), !out.isEmpty, let g = groups(itemRe, line) {
                let text = g[1] ?? ""
                if empty.contains(text) { continue }
                out[out.count - 1].1.append(Item(text: text, ts: g[2].flatMap { $0.isEmpty ? nil : $0 }))
            }
        }
        return out.filter { !$0.1.isEmpty }.map { Section(heading: $0.0, tag: tags[$0.0] ?? "", items: $0.1) }
    }

    /// The same sections from the typed notes (tag, text, ts), already through the proposal guard.
    static func sectionsOf(_ notes: [(tag: String?, text: String, ts: String)], _ sections: [(String, String)], heading: (String) -> String = { $0 }) -> [Section] {
        sections.compactMap { h, tag in
            let items = notes.filter { $0.tag?.uppercased() == tag }.map { n -> Item in
                var t = n.text; while t.hasSuffix("。") { t.removeLast() }
                return Item(text: t, ts: n.ts)
            }.filter { !empty.contains($0.text) }
            return items.isEmpty ? nil : Section(heading: heading(h), tag: tag, items: items)
        }
    }

    // MARK: speaker references

    private static let refRe = try! NSRegularExpression(pattern: #"(?<![A-Za-z0-9_])S(\d{1,2})(?![A-Za-z0-9_])"#)
    private static let hanSpace = try! NSRegularExpression(pattern: #"(?<=[\x{3400}-\x{9fff}]) \x{01}"#)
    private static let spaceHan = try! NSRegularExpression(pattern: #"\x{01} (?=[\x{3400}-\x{9fff}])"#)
    private static let ownerRe = try! NSRegularExpression(pattern: #"^S(\d{1,2})(?![A-Za-z0-9_])"#)

    /// S2 → the speaker's name; no space between a Han name and Han text (`S2 需` → `林雅婷需`).
    static func resolveRefs(_ text: String, _ names: [Int: String]) -> String {
        let ns = text as NSString
        var out = "", pos = 0
        for m in refRe.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: pos, length: m.range.location - pos))
            let n = Int(ns.substring(with: m.range(at: 1)))! - 1
            out += names[n].map { "\u{1}\($0)\u{1}" } ?? ns.substring(with: m.range)
            pos = m.range.location + m.range.length
        }
        out += ns.substring(from: pos)
        out = hanSpace.stringByReplacingMatches(in: out, range: NSRange(location: 0, length: (out as NSString).length), withTemplate: "\u{1}")
        out = spaceHan.stringByReplacingMatches(in: out, range: NSRange(location: 0, length: (out as NSString).length), withTemplate: "\u{1}")
        return out.replacingOccurrences(of: "\u{1}", with: "")
    }

    private static func ownerOf(_ text: String, _ names: [Int: String]) -> String? {
        let ns = text as NSString
        guard let m = ownerRe.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)), let n = Int(ns.substring(with: m.range(at: 1))) else { return nil }
        return names[n - 1]
    }

    // MARK: the mail

    private static let tsRe = try! NSRegularExpression(pattern: #"\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)\]"#)

    private static func fill(_ t: String, _ kv: [(String, Any)]) -> String {
        var s = t; for (k, v) in kv { s = s.replacingOccurrences(of: "{\(k)}", with: "\(v)") }; return s
    }
    /// Design C (HTML only): each cite a small pill; the `[ts]` characters stay in the source so copy-paste and the
    /// tsRe / Markdown anchor parsers still see them. A multi-cite becomes one pill per time (hair space between).
    private static func htmlTs(_ t: String) -> String {
        let e = MiniMustache.escapeHtml(t), ns = e as NSString
        var out = "", pos = 0
        for m in tsRe.matches(in: e, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: pos, length: m.range.location - pos))
            out += ns.substring(with: m.range(at: 1)).components(separatedBy: CharacterSet(charactersIn: ",，、"))
                .map { "<span style=\"\(pill)\">[\($0.trimmingCharacters(in: .whitespaces))]</span>" }.joined(separator: "\u{200A}")
            pos = m.range.location + m.range.length
        }
        return out + ns.substring(from: pos)
    }
    static let pill = "color:#5A6982;font-size:13px;background:#EEF1EC;border-radius:8px;padding:0 6px;white-space:nowrap;"

    // MARK: design B: cites at the clause end (plain text + chat)

    private static let wj = "\u{2060}", term: Set<Character> = ["。", "！", "？", "!", "?"], closers: Set<Character> = ["」", "』", "）", ")", "\"", "'"]
    private static func isHan(_ c: Character?) -> Bool {
        guard let v = c?.unicodeScalars.first?.value else { return false }
        return (0x3400...0x9FFF).contains(v) || (0x3000...0x303F).contains(v) || (0xFF00...0xFFEF).contains(v)
    }
    /// The glue before a trailing cite: U+2060 after Han / fullwidth text (never a space, never a break), a no-break
    /// space after Latin text — the `[…]` never wraps alone to the next line.
    private static func glue(_ before: String) -> String { isHan(before.last) ? wj : "\u{00A0}" }

    /// A minutes item's trailing cite: `…條款。⁠[1:12]` (a Han item gets its full stop back).
    static func itemCite(_ text: String, _ ts: String?) -> String {
        guard let ts else { return "" }
        let stop = isHan(text.last) && !term.contains(text.last!) && !"」』）".contains(text.last!) ? "。" : ""
        return stop + glue(text + stop) + "[\(ts)]"
    }

    /// Sentences of `p`, each with its terminator and following whitespace. A `.` ends one only before whitespace /
    /// the end (not `3.5`); closing brackets/quotes stay with their sentence; nothing ends inside a `[…]`.
    private static func sentences(_ p: String) -> [String] {
        let c = Array(p)
        var out: [String] = [], start = 0, i = 0, open = false
        while i < c.count {
            if c[i] == "[" { open = true } else if c[i] == "]" { open = false }
            let ends = term.contains(c[i]) || (c[i] == "." && (i + 1 == c.count || c[i + 1].isWhitespace))
            if ends && !open {
                var j = i + 1
                while j < c.count && (term.contains(c[j]) || closers.contains(c[j])) { j += 1 }
                while j < c.count && c[j].isWhitespace { j += 1 }
                out.append(String(c[start..<j])); start = j; i = j; continue
            }
            i += 1
        }
        if start < c.count { out.append(String(c[start...])) }
        return out
    }

    private static let spacedTs = try! NSRegularExpression(pattern: "[ \\t]*" + tsRe.pattern)

    /// Design B for prose: keep the FIRST cite of each sentence (`[0:18, 0:35]` stays one cite), move it after the
    /// sentence's full stop, glued; drop an exact repeat of the previous sentence's cite. Bracket/separator shape
    /// untouched, so tsRe / the anchors / repairTimestamps still parse it. Android MeetingMail.clauseEndCites.
    static func clauseEndCites(_ p: String) -> String {
        var out = "", last: String?
        let sens = sentences(p)
        for (si, sen) in sens.enumerated() {
            let ns = sen as NSString
            guard let m = tsRe.firstMatch(in: sen, range: NSRange(location: 0, length: ns.length)) else { out += sen; continue }
            let cite = ns.substring(with: m.range)
            let body = spacedTs.stringByReplacingMatches(in: sen, range: NSRange(location: 0, length: ns.length), withTemplate: "")
            var core = body; while core.last?.isWhitespace == true { core.removeLast() }
            let ws = String(body.dropFirst(core.count))
            var k = core.endIndex
            while k > core.startIndex, case let ch = core[core.index(before: k)], term.contains(ch) || ch == "." || closers.contains(ch) { k = core.index(before: k) }
            var head = String(core[..<k]); while head.last?.isWhitespace == true { head.removeLast() }
            let tail = String(core[k...])
            out += head + tail
            // A moved cite is followed by a space when its sentence had none and more text follows.
            if cite != last { out += glue(head + tail) + cite; if ws.isEmpty && si + 1 < sens.count { out += " " } }
            last = cite
            out += ws
        }
        return out
    }

    private final class Block {
        let kind: String, tag: String, heading: String
        var items: [Item], paragraphs: [String], dropped = 0
        init(_ kind: String, _ tag: String, _ heading: String, items: [Item] = [], paragraphs: [String] = []) {
            self.kind = kind; self.tag = tag; self.heading = heading; self.items = items; self.paragraphs = paragraphs
        }
    }

    /// The template context (render_samples.build) and whether the budget cut something.
    static func model(_ input: Input, lang: String, _ opts: Options, tpl: Templates = .shipped, htmlHeadings: Bool = false, budgetTemplate: String? = nil) -> ([String: Any], Bool) {
        let s = tpl.strings[lang] as? [String: Any] ?? [:]
        func str(_ k: String) -> String { s[k] as? String ?? "" }
        var cal = Calendar(identifier: .gregorian); cal.timeZone = input.timeZone
        let c = cal.dateComponents([.year, .month, .day, .weekday], from: input.start)
        let wdIndex = ((c.weekday ?? 2) + 5) % 7   // Monday = 0
        let wd = (s["weekdays"] as? [String])?[wdIndex] ?? ""
        let mon = (s["months"] as? [String])?[(c.month ?? 1) - 1] ?? ""
        let dateLong = fill(str("date_long"), [("y", c.year ?? 0), ("mo", c.month ?? 0), ("d", c.day ?? 0), ("wd", wd), ("mon", mon)])
        let dateShort = fill(str("date_short"), [("mo", c.month ?? 0), ("d", c.day ?? 0), ("mon", mon)])
        func sub(_ t: String) -> String { fill(t, [("title", input.title), ("date_long", dateLong), ("date_short", dateShort)]) }
        func plainHeading(_ h: String) -> String { htmlHeadings ? h : fill(str("plain_heading"), [("h", h), ("H", h.uppercased())]) }
        let minutes = Int((input.durationSec / 60).rounded(.toNearestOrEven))
        let duration = minutes >= 60 ? fill(str("duration_hm"), [("h", minutes / 60), ("m", minutes % 60)]) : fill(str("duration_m"), [("m", minutes)])
        let parts = input.participants + (input.unnamed > 0 ? [fill(str("unnamed"), [("n", input.unnamed)])] : [])
        let hm = DateFormatter(); hm.locale = Locale(identifier: "en_US_POSIX"); hm.timeZone = input.timeZone; hm.dateFormat = "HH:mm"
        let end = input.start.addingTimeInterval(input.durationSec)
        let facts: [[String: Any]] = ([
            ["label": str("label_date"), "value": dateLong],
            input.timeKnown ? ["label": str("label_time"), "value": "\(hm.string(from: input.start))–\(hm.string(from: end))"] : nil,
            ["label": str("label_duration"), "value": duration],
            ["label": str("label_participants"), "value": parts.joined(separator: str("list_sep"))],
        ] as [[String: Any]?]).compactMap { $0 }
        let names = input.names
        func refs(_ t: String) -> String { resolveRefs(t, names) }
        var model: [String: Any] = [
            "subject": sub(str("subject")), "greeting": str("greeting"), "intro": sub(str("intro")), "chat_intro": sub(str("chat_intro")),
            "facts_heading": plainHeading(str("facts_heading")), "facts": facts, "colon": str("colon"),
            "disclaimer": str("disclaimer"), "footer": str("footer"), "bullet": str("bullet"),
            "html_lang": lang == "zh-Hant" ? "zh-Hant-TW" : lang == "zh-Hans" ? "zh-Hans-CN" : "en",
        ]
        if let l = opts.lead { let r = refs(l); model["lead"] = r; model["lead_html"] = htmlTs(r) }
        var blocks: [Block] = []
        if opts.summary, let sm = input.summary, !sm.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            blocks.append(Block("summary", "", str("summary_heading"),
                                paragraphs: refs(sm).components(separatedBy: "\n\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }))
        }
        if opts.minutes { for sec in input.sections where opts.only == nil || opts.only!.contains(sec.tag) { blocks.append(Block("section", sec.tag, sec.heading, items: sec.items)) } }
        if !opts.attachments.isEmpty { model["attachments"] = fill(str("attachments"), [("files", opts.attachments.joined(separator: str("list_sep")))]) }

        func grouped(_ items: [[String: Any]]) -> [[String: Any]] {
            let none = str("owner_none")
            var order: [String] = [], by: [String: [[String: Any]]] = [:]
            for i in items {
                let owner = i["owner"] as? String, o = owner ?? none
                if by[o] == nil { by[o] = []; order.append(o) }
                var t = i["text"] as! String
                if let owner, t.hasPrefix(owner) { t = String(t.dropFirst(owner.count)); while t.hasPrefix(" ") { t.removeFirst() } }
                var it: [String: Any] = ["text": t, "cite": itemCite(t, i["ts"] as? String)]; if let ts = i["ts"] { it["ts"] = ts }
                by[o]!.append(it)
            }
            let sorted = order.filter { $0 != none } + (by[none] != nil ? [none] : [])
            return sorted.map { ["owner": $0, "items": by[$0]!] }
        }
        func assemble() {
            model["summary"] = nil
            var secs: [[String: Any]] = []
            for b in blocks {
                if b.kind == "summary" {
                    if !b.paragraphs.isEmpty { model["summary"] = ["heading": plainHeading(b.heading), "paragraphs": b.paragraphs.map { ["text": clauseEndCites($0), "html": htmlTs($0)] }] as [String: Any] }
                    continue
                }
                if b.items.isEmpty { continue }
                let items: [[String: Any]] = b.items.map { i in
                    var d: [String: Any] = ["text": refs(i.text), "cite": itemCite(refs(i.text), i.ts)]
                    if let ts = i.ts { d["ts"] = ts }
                    if b.tag == "ACTION", let o = ownerOf(i.text, names) { d["owner"] = o }
                    return d
                }
                let g = items.contains { $0["owner"] != nil } ? grouped(items) : [["items": items]]
                var sec: [String: Any] = ["heading": plainHeading(b.heading), "groups": g]
                if b.dropped > 0 { sec["more"] = fill(str("more"), [("n", b.dropped)]) }
                secs.append(sec)
            }
            model["sections"] = secs
        }
        assemble()
        var truncated = false
        if let budget = opts.budget {
            let bt = budgetTemplate ?? tpl.txt
            let steps: [(String, Int)] = [("PROPOSAL", 5), ("summary", 1), ("NUMBER", 5), ("PROPOSAL", 0), ("summary", 0), ("NUMBER", 0), ("OPEN-ISSUE", 3)]
            for (key, keep) in steps {
                if MiniMustache.render(bt, model, escape: false).unicodeScalars.count <= budget { break }
                truncated = true
                for b in blocks {
                    if key == "summary" && b.kind == "summary" { b.paragraphs = Array(b.paragraphs.prefix(keep)) }
                    else if b.tag == key && b.items.count > keep { b.dropped += b.items.count - keep; b.items = Array(b.items.prefix(keep)) }
                }
                assemble()
            }
        }
        return (model, truncated)
    }

    private static let blanks = try! NSRegularExpression(pattern: #"\n{3,}"#)
    private static let htmlBlanks = try! NSRegularExpression(pattern: #"\n\s*\n"#)

    static func plain(_ model: [String: Any], _ template: String) -> String {
        let r = MiniMustache.render(template, model, escape: false)
        return blanks.stringByReplacingMatches(in: r, range: NSRange(location: 0, length: (r as NSString).length), withTemplate: "\n\n")
            .trimmingCharacters(in: .whitespacesAndNewlines) + "\n"
    }
    static func html(_ model: [String: Any], _ template: String) -> String {
        let r = MiniMustache.render(template, model, escape: true)
        return htmlBlanks.stringByReplacingMatches(in: r, range: NSRange(location: 0, length: (r as NSString).length), withTemplate: "\n")
    }

    static func build(_ input: Input, lang: String, _ opts: Options, tpl: Templates = .shipped) -> Mail {
        let (m, cut) = model(input, lang: lang, opts, tpl: tpl)
        let (hm, _) = model(input, lang: lang, opts, tpl: tpl, htmlHeadings: true)
        var co = opts; co.summary = false; co.attachments = []; co.budget = chatBudget
        let (cm, _) = model(input, lang: lang, co, tpl: tpl, budgetTemplate: tpl.chat)
        return Mail(subject: m["subject"] as? String ?? "", plain: plain(m, tpl.txt), html: html(hm, tpl.html), chat: plain(cm, tpl.chat), truncated: cut)
    }

    /// English for an English meeting; for Chinese the interface's script, else Traditional (§5.4).
    static func defaultLang(meeting: String?, ui: Locale) -> String {
        if meeting == "en" { return "en" }
        let id = ui.identifier
        if id.hasPrefix("zh") && (id.contains("Hans") || id.hasSuffix("CN") || id.hasSuffix("SG")) { return "zh-Hans" }
        return "zh-Hant"
    }
}

/// "Last shared" of one meeting (§4.6): Application Support/shared/<uuid>.json, the same JSON as Android's shared.json.
enum ShareHistory {
    static let keep = 10
    struct Recipient: Equatable { var person: String?; var address: String }
    struct Content: Equatable { var summary: Bool; var minutes: Bool; var only: [String]?; var attach: [String] }
    struct Share: Equatable {
        var at: Int64
        /// "email" | "share-sheet".
        var channel: String
        /// "opened" | MFMail "sent" | "saved".
        var status: String
        var recipients: [Recipient]
        var content: Content
        var lang: String
    }

    static func encode(_ shares: [Share]) -> Data {
        let arr: [[String: Any]] = shares.suffix(keep).map { s in
            ["at": s.at, "channel": s.channel, "status": s.status,
             "recipients": s.recipients.map { r -> [String: Any] in var o: [String: Any] = ["address": r.address]; if let p = r.person { o["person"] = p }; return o },
             "content": ["summary": s.content.summary, "minutes": s.content.minutes, "only": s.content.only.map { $0 as Any } ?? NSNull(), "attach": s.content.attach] as [String: Any],
             "lang": s.lang]
        }
        return (try? JSONSerialization.data(withJSONObject: ["version": 1, "shares": arr] as [String: Any], options: [.sortedKeys])) ?? Data()
    }
    static func decode(_ data: Data?) -> [Share] {
        guard let data, let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let arr = o["shares"] as? [[String: Any]] else { return [] }
        return arr.map { s in
            let c = s["content"] as? [String: Any] ?? [:]
            return Share(at: (s["at"] as? NSNumber)?.int64Value ?? 0, channel: s["channel"] as? String ?? "email", status: s["status"] as? String ?? "opened",
                         recipients: ((s["recipients"] as? [[String: Any]]) ?? []).compactMap { r in
                             (r["address"] as? String).flatMap { $0.isEmpty ? nil : Recipient(person: (r["person"] as? String).flatMap { $0.isEmpty ? nil : $0 }, address: $0) } },
                         content: Content(summary: c["summary"] as? Bool ?? true, minutes: c["minutes"] as? Bool ?? true, only: c["only"] as? [String], attach: c["attach"] as? [String] ?? []),
                         lang: s["lang"] as? String ?? "")
        }
    }
    static func add(_ shares: [Share], _ s: Share) -> [Share] { Array((shares + [s]).suffix(keep)) }
    /// A person was deleted: their id and addresses (normalised keys) go from every share.
    static func scrub(_ shares: [Share], person: String, addresses: Set<String>) -> [Share] {
        shares.map { s in var t = s; t.recipients.removeAll { $0.person == person || addresses.contains(EmailRules.key($0.address)) }; return t }
    }

    static let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("shared")
    static func url(_ id: UUID) -> URL { dir.appendingPathComponent("\(id.uuidString).json") }
    static func load(_ id: UUID) -> [Share] { decode(try? Data(contentsOf: url(id))) }
    static func record(_ id: UUID, _ s: Share) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? encode(add(load(id), s)).write(to: url(id), options: .atomic)
    }
}

/// Who the mail goes to (§4.2), the same rules as Android Recipients: one row per person, in speaker order, the default
/// address; pre-checked = every person with an address except Me (everyone when no one is Me), or last time's recipients.
enum Recipients {
    struct Row: Equatable { var person: Person; var speakers: [Int]; var address: String?; var checked: Bool }
    struct Loose: Equatable { var speaker: Int; var label: String? }

    static func rows(_ people: [Int: Person], anyMe: Bool, last: ShareHistory.Share? = nil) -> [Row] {
        var order: [String] = [], by: [String: (Person, [Int])] = [:]
        for (sid, p) in people.sorted(by: { $0.key < $1.key }) {
            if by[p.id] == nil { by[p.id] = (p, []); order.append(p.id) }
            by[p.id]!.1.append(sid)
        }
        let lastPeople = Set(last?.recipients.compactMap(\.person) ?? [])
        let lastAddr = Set(last?.recipients.map { EmailRules.key($0.address) } ?? [])
        return order.map { id in
            let (p, sids) = by[id]!
            let a = p.emails.first?.address
            let checked: Bool
            if a == nil { checked = false }
            else if last != nil { checked = lastPeople.contains(p.id) || lastAddr.contains(EmailRules.key(a!)) }
            else { checked = !(anyMe && p.me) }
            return Row(person: p, speakers: sids, address: a, checked: checked)
        }
    }
    static func loose(_ speakers: [Int], names: [Int: String], people: [Int: Person]) -> [Loose] {
        Array(Set(speakers)).sorted().filter { people[$0] == nil }.map { Loose(speaker: $0, label: names[$0].flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }) }
    }
    static func to(_ rows: [Row], extra: [String]) -> [String] {
        var seen = Set<String>()
        return (rows.filter(\.checked).compactMap(\.address) + extra).filter { seen.insert(EmailRules.key($0)).inserted }
    }
}
