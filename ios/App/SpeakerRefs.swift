import Foundation

/// Android SpeakerRefs: the reader writes speakers as S1, S2 (speaker id n is "S{n+1}") and those stay in what is saved;
/// what is shown names them as the transcript does, so a later rename reaches the summary too.
enum SpeakerRefs {
    private static let ref = try! NSRegularExpression(pattern: #"(?<![A-Za-z0-9_])S(\d{1,2})(?![A-Za-z0-9_])"#)
    private static func han(_ c: Character) -> Bool { c.unicodeScalars.first.map { (0x3400...0x9FFF).contains($0.value) || (0xF900...0xFAFF).contains($0.value) } ?? false }

    /// `known`: speaker ids in the transcript; an S-number with no such speaker (a model slip) is left as written.
    static func resolve(_ text: String, label: (Int) -> String, known: Set<Int>?, wrap: (String) -> String = { $0 }) -> String {
        var out = "", last = text.startIndex
        for m in ref.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let r = Range(m.range, in: text), let g = Range(m.range(at: 1), in: text), let n = Int(text[g]) else { continue }
            let id = n - 1
            out += text[last..<r.lowerBound]; last = r.upperBound
            if id < 0 || (known.map { !$0.contains(id) } ?? false) { out += text[r]; continue }
            let name = label(id)
            // Chinese takes no space before a Chinese name: "而 S1 則" → "而語者 1 則".
            if out.hasSuffix(" "), out.count >= 2, han(out[out.index(out.endIndex, offsetBy: -2)]), let f = name.first, han(f) { out.removeLast() }
            out += wrap(name)
            // A name ending in a digit or Latin letter runs into the Chinese after it: "語者 1將" → "語者 1 將".
            if let l = name.last, l.isLetter || l.isNumber, !han(l), r.upperBound < text.endIndex, han(text[r.upperBound]) { out += " " }
        }
        return out + text[last...]
    }
    /// A name as one unit on screen: it never wraps between "語者" and its number.
    static func unbreakable(_ s: String) -> String { s.replacingOccurrences(of: " ", with: "\u{00A0}") }

    /// A meeting's speakers as `resolve` takes them: the label of an id, and the ids present (nil: any).
    struct Labels { let label: (Int) -> String; let known: Set<Int>? }

    /// Android SpeakerRefs.resolveCited: `resolve` for text citing several meetings (an Ask answer). Each S-number belongs
    /// to the meeting of the citation that follows it (the last one for a tail after the last citation); `labels` maps a
    /// citation match to its meeting's speakers, or nil to leave that part as written.
    static func resolveCited(_ text: String, cite: NSRegularExpression, labels: (NSTextCheckingResult) -> Labels?) -> String {
        let ns = text as NSString
        let cites = cite.matches(in: text, range: NSRange(location: 0, length: ns.length))
        if cites.isEmpty || ref.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) == nil { return text }
        func segment(_ t: String, _ l: Labels?) -> String { l.map { resolve(t, label: $0.label, known: $0.known) } ?? t }
        var out = "", last = 0
        for (i, m) in cites.enumerated() {
            let l = labels(m)
            out += segment(ns.substring(with: NSRange(location: last, length: m.range.location - last)), l)
            out += ns.substring(with: m.range)
            last = m.range.location + m.range.length
            if i == cites.count - 1 { out += segment(ns.substring(from: last), l) }
        }
        return out
    }
}

/// The live screen's speaker names — Android's `SessionState.speakerNames` while a run is going. Keyed by speaker id
/// and read by each row as it renders, so a name confirmed mid-run is on every line of that speaker in the same frame,
/// and clearing one puts "Speaker n" back at once. A blank entry is a name the user cleared: it keeps the clear from
/// being refilled by the names a re-run carries (Android drops the key: `s.speakerNames - speaker`).
enum LiveNames {
    /// A row's label (Android speakerLabel): the confirmed name, else the numbered default.
    static func label(_ names: [Int: SpeakerLink], _ spk: Int, numbered: (Int) -> String) -> String {
        guard let n = names[spk]?.name.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty else { return numbered(spk) }
        return n
    }
    /// Confirm `link` for `spk` (Android linkSpeaker / commitSpeakerName); a blank or nil link records a clear.
    static func setting(_ names: [Int: SpeakerLink], _ spk: Int, _ link: SpeakerLink?) -> [Int: SpeakerLink] {
        var n = names
        let t = link?.name.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        n[spk] = t.isEmpty ? SpeakerLink(name: "") : SpeakerLink(name: t, person: link?.person)
        return n
    }
    /// Back to "Speaker n" (Android clearSpeakerName); the person stays in the directory.
    static func cleared(_ names: [Int: SpeakerLink], _ spk: Int) -> [Int: SpeakerLink] { setting(names, spk, nil) }
    /// Merge one speaker into another (Android SpeakerEdits.merge: `names - from`): its lines move, its name goes with
    /// the id that is gone. The target keeps its own label, numbered when it has none — as in the finished meeting.
    static func merging(_ names: [Int: SpeakerLink], from: Int, into: Int) -> [Int: SpeakerLink] {
        guard from != into else { return names }
        var n = names
        n[from] = nil
        return n
    }
    /// The names as they are stored: a session file keeps no empty names, no cleared speakers.
    static func stored(_ names: [Int: SpeakerLink]) -> [Int: SpeakerLink] {
        names.filter { !$0.value.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
    /// What the run saves over `base` (the names the meeting had, or the ones a re-run carried): the live screen's
    /// choices win and its clears stay cleared (Android: the screen's own names are what persists after a run).
    static func overlaid(_ base: [Int: SpeakerLink], _ live: [Int: SpeakerLink]) -> [Int: SpeakerLink] {
        var n = base
        for (k, l) in live {
            let t = l.name.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty { n[k] = nil } else { n[k] = SpeakerLink(name: t, person: l.person) }
        }
        return n
    }
    /// A speaker left without lines loses its name (Android dropName).
    static func kept(_ names: [Int: SpeakerLink], _ lines: [Utterance]) -> [Int: SpeakerLink] {
        let ids = Set(lines.map(\.speaker))
        return names.filter { ids.contains($0.key) && !$0.value.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
}
