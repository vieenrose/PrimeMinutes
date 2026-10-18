import SwiftUI

/// AttributedString keys spelled out: the `a.font = …` shorthand forms key paths over non-Sendable attribute scopes,
/// which strict concurrency rejects.
enum Attr {
    typealias Link = AttributeScopes.FoundationAttributes.LinkAttribute
    typealias Intent = AttributeScopes.FoundationAttributes.InlinePresentationIntentAttribute
    typealias Foreground = AttributeScopes.SwiftUIAttributes.ForegroundColorAttribute
    typealias Background = AttributeScopes.SwiftUIAttributes.BackgroundColorAttribute
    typealias Font = AttributeScopes.SwiftUIAttributes.FontAttribute
    typealias Baseline = AttributeScopes.SwiftUIAttributes.BaselineOffsetAttribute
}

/// Android renderMarkdown: bold, italic, inline code, hash headings and dash/star/plus bullets for the reader's prose,
/// with each `[m:ss]` / `[h:mm:ss]` anchor drawn as a tappable superscript (no brackets, grey, small, glued to the
/// word before it) that opens `vox://<seconds>`, and speaker names in their transcript colour.
enum Markdown {
    private static let anchor = try! NSRegularExpression(pattern: #"\[(\d+):(\d{2})(?::(\d{2}))?\]"#)
    /// "[1:48, 12:00]": several times in one bracket, which the model sometimes writes; split into one anchor each.
    private static let multi = try! NSRegularExpression(pattern: #"\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、;；]\s*\d+:\d{2}(?::\d{2})?)+)\]"#)
    static func splitAnchors(_ text: String) -> String {
        var out = "", last = text.startIndex
        for m in multi.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let r = Range(m.range, in: text), let g = Range(m.range(at: 1), in: text) else { continue }
            out += text[last..<r.lowerBound]; last = r.upperBound
            out += text[g].split(whereSeparator: { ",，、;；".contains($0) }).map { "[\($0.trimmingCharacters(in: .whitespaces))]" }.joined(separator: " ")
        }
        return out + text[last...]
    }

    static func render(_ md: String, anchors: Color? = nil, speakers: [(String, Color)] = []) -> AttributedString {
        var out = AttributedString()
        let lines = md.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: "\n")
        for (i, raw) in lines.enumerated() {
            if let h = raw.firstMatch(of: #/^\s{0,3}#{1,6}\s+(.*)$/#) {
                var a = withAnchors(String(h.1), anchors); for r in a.runs { a[r.range][Attr.Intent.self] = (r[Attr.Intent.self] ?? []).union(.stronglyEmphasized) }; out += a
            } else if let b = raw.firstMatch(of: #/^(\s*)[-*+]\s+(.*)$/#) {
                out += AttributedString("•  ") + withAnchors(String(b.2), anchors)
            } else { out += withAnchors(raw, anchors) }
            if i < lines.count - 1 { out += AttributedString("\n") }
        }
        for (name, color) in speakers where !name.isEmpty {
            var from = out.startIndex
            while let r = out[from...].range(of: name) { out[r][Attr.Foreground.self] = color; out[r][Attr.Intent.self] = .stronglyEmphasized; from = r.upperBound }
        }
        return out
    }

    private static func withAnchors(_ raw: String, _ color: Color?) -> AttributedString {
        guard let color else { return inline(raw) }
        let text = splitAnchors(raw)
        var out = AttributedString(), last = text.startIndex, prevAnchor = false
        for m in anchor.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let r = Range(m.range, in: text), let a = Range(m.range(at: 1), in: text), let b = Range(m.range(at: 2), in: text),
                  let g1 = Int(text[a]), let g2 = Int(text[b]) else { continue }
            let c = Range(m.range(at: 3), in: text).flatMap { Int(text[$0]) }
            let secs = c.map { g1 * 3600 + g2 * 60 + $0 } ?? g1 * 60 + g2
            // Raised like a footnote mark, so the space the model puts before it would only open a gap.
            var before = String(text[last..<r.lowerBound]); while before.hasSuffix(" ") { before.removeLast() }
            if prevAnchor && before.isEmpty {
                // "[0:44] [4:09]": with the space trimmed the two labels read as one ("0:444:09").
                var sep = AttributedString(", "); sep[Attr.Foreground.self] = color; sep[Attr.Font.self] = .caption2.weight(.medium); sep[Attr.Baseline.self] = 6
                out += sep
            } else { out += inline(before) }
            out += AttributedString("\u{2060}"); prevAnchor = true
            var mark = AttributedString(c.map { _ in "\(text[a]):\(text[b]):\(text[Range(m.range(at: 3), in: text)!])" } ?? "\(text[a]):\(text[b])")
            mark[Attr.Link.self] = URL(string: "vox://\(secs)"); mark[Attr.Foreground.self] = color
            mark[Attr.Font.self] = .caption2.weight(.medium); mark[Attr.Baseline.self] = 6
            out += mark; last = r.upperBound
        }
        return out + inline(String(text[last...]))
    }

    /// Inline `***x***` / `**x**` / `__x__` / `*x*` / `_x_` / `` `x` ``; an unclosed marker stays literal.
    private static func inline(_ t: String) -> AttributedString {
        var out = AttributedString(); var i = t.startIndex; var plain = ""
        func flush() { if !plain.isEmpty { out += AttributedString(plain); plain = "" } }
        func span(_ mark: String, _ intent: InlinePresentationIntent) -> Bool {
            guard t[i...].hasPrefix(mark), let end = t.range(of: mark, range: t.index(i, offsetBy: mark.count)..<t.endIndex) else { return false }
            flush(); var a = AttributedString(String(t[t.index(i, offsetBy: mark.count)..<end.lowerBound])); a[Attr.Intent.self] = intent
            out += a; i = end.upperBound; return true
        }
        while i < t.endIndex {
            if span("***", [.stronglyEmphasized, .emphasized]) || span("___", [.stronglyEmphasized, .emphasized]) { continue }
            if t[i...].hasPrefix("***") || t[i...].hasPrefix("___") { plain += t[i..<t.index(i, offsetBy: 3)]; i = t.index(i, offsetBy: 3); continue }
            if span("**", .stronglyEmphasized) || span("__", .stronglyEmphasized) { continue }
            if t[i...].hasPrefix("**") || t[i...].hasPrefix("__") { plain += t[i..<t.index(i, offsetBy: 2)]; i = t.index(i, offsetBy: 2); continue }
            if span("*", .emphasized) || span("_", .emphasized) || span("`", .code) { continue }
            plain.append(t[i]); i = t.index(after: i)
        }
        flush(); return out
    }
}


/// Android LocalSpeakerRefs: how model text (notes, title) names speakers in the views below.
private struct SpeakerRefsKey: EnvironmentKey { static let defaultValue: @Sendable (String) -> String = { $0 } }
extension EnvironmentValues {
    var speakerRefs: @Sendable (String) -> String { get { self[SpeakerRefsKey.self] } set { self[SpeakerRefsKey.self] = newValue } }
}
