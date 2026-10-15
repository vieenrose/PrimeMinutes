import Foundation

/// Title and prose for a finished meeting (port of the post-reading half of Android `ReaderLane`):
/// each is one fresh conversation on the same model, not part of the upstream protocol, with its
/// output sanitised hard. Call from the reader's thread.
struct ReaderSummarizer {
    let llm: ReaderLlm
    /// The model's card: budgets, sampler, the title/prose prompts and how far the journal is compacted.
    var card: ModelCard = .default
    private var budget: ReaderBudget { card.budget }
    private var notesChars: Int { card.reader.notesChars }

    func title(_ journal: [Note]) -> String? {
        guard !journal.isEmpty else { return nil }
        llm.reset()
        let toks = fitting(journal, card.title.maxTokens, card.title.render)
        guard llm.append(toks) >= 0 else { return nil }
        let raw = llm.generateContinue(maxTokens: card.title.maxTokens, stop: llm.template.end, temp: card.sampler.temp) { _ in }
        let first = raw.components(separatedBy: llm.template.end)[0].split(whereSeparator: \.isNewline)
            .map(String.init).first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let junk = CharacterSet(charactersIn: "「」\"*# ")
        guard var t = first?.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: junk) else { return nil }
        // "會議：…" / "標題：…" — the model labels its answer now and then.
        if let label = card.title.label, find(label, t) {
            t = replace(label, t, "").trimmingCharacters(in: junk)
        }
        let cut = String(t.prefix(40))
        return cut.isEmpty ? nil : cut
    }

    /// The summary as prose. Every `[ts]` it cites must be a journal time (anything else is stripped).
    /// Nil when the journal is empty or the reply is unusable; callers fall back to the grouped minutes.
    func prose(_ journal: [Note]) -> String? {
        guard !journal.isEmpty else { return nil }
        llm.reset()
        let toks = fitting(journal, card.prose.maxTokens, card.prose.render)
        guard llm.append(toks) >= 0 else { return nil }
        let raw = llm.generateContinue(maxTokens: card.prose.maxTokens, stop: llm.template.end, temp: card.sampler.temp) { _ in }
        return Self.cleanProse(raw, known: Set(journal.map(\.ts)), turnEnd: llm.template.end)
    }

    /// The one-shot prompt over the notes, compacted further while it would not leave `maxOut`
    /// tokens of room in the context.
    private func fitting(_ journal: [Note], _ maxOut: Int, _ prompt: (String) -> String) -> [Int] {
        var chars = notesChars
        while true {
            let notes = (chars > 0 ? card.reader.compactNotes(journal, budgetChars: chars) : journal)
                .map(ReaderProtocol.render).joined(separator: "\n")
            let toks = llm.tokenize(llm.template.userFirst, special: true) + llm.tokenize(prompt(notes), special: false) +
                llm.tokenize(llm.template.p2, special: true)
            if chars <= 0 || toks.count + maxOut + 8 <= budget.ctxBudget || chars <= 800 { return toks }
            chars -= 400
        }
    }

    private static let tsRx = rx(#"\[(\d+:\d{2}(?::\d{2})?)\]"#)
    private static let spaceBeforePunct = rx(#"[ \t]+([。，、；：！？.,;:!?])"#)
    private static let sentenceEnd = rx(#"[。！？!?](\s*\[\d+:\d{2}(?::\d{2})?\])?|[.](?=\s|$)"#)

    /// The prose reply made presentable: unknown `[ts]` stripped, no headings or bullets, and a
    /// reply cut off by the card's prose budget (no end-of-turn) ends at its last whole sentence.
    static func cleanProse(_ raw: String, known: Set<String>, turnEnd: String = ModelCard.default.template.end) -> String? {
        let ended = raw.contains(turnEnd)
        var text = raw.components(separatedBy: turnEnd)[0]
        let ns = text as NSString
        var stripped = ""; var last = 0
        for m in tsRx.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            stripped += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let whole = ns.substring(with: m.range)
            if known.contains(ns.substring(with: m.range(at: 1))) { stripped += whole }
            last = m.range.location + m.range.length
        }
        stripped += ns.substring(from: last)
        text = replace(spaceBeforePunct, stripped, "$1")
        text = text.split(whereSeparator: \.isNewline).map { l -> String in
            var s = l.trimmingCharacters(in: .whitespaces); if s.hasPrefix("#") { s.removeFirst() }; return s.trimmingCharacters(in: .whitespaces)
        }.filter { !$0.isEmpty && !$0.hasPrefix("-") && !$0.hasPrefix("*") }.joined(separator: "\n\n")
        if !ended {
            let t = text as NSString
            if let e = sentenceEnd.matches(in: text, range: NSRange(location: 0, length: t.length)).last {
                text = t.substring(to: e.range.location + e.range.length).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return text.count >= 20 ? text : nil
    }

    /// An utterance as the model reads it (ingest.segments_to_lines): cleaned text, `S{n}` labels, whole seconds.
    static func toLine(_ u: Utterance) -> Line? {
        let text = ReaderProtocol.cleanText(u.text)
        guard !text.isEmpty else { return nil }
        return Line(startS: Int(u.start.rounded(.down)), speaker: "S\(u.speaker + 1)", text: text)
    }
}
