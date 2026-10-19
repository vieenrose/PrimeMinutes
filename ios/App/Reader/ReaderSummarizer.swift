import Foundation

/// Title and prose for a finished meeting (port of the post-reading half of Android `ReaderLane`):
/// each is one fresh conversation on the same model, not part of the upstream protocol, with its
/// output sanitised hard. Call from the reader's thread.
struct ReaderSummarizer {
    let llm: ReaderLlm
    /// The model's card for this meeting's language (`ModelCard.forLanguage`): budgets, sampler, the title/prose
    /// prompts and how far the journal is compacted.
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
        let cut = Self.cutTitle(t)
        return cut.isEmpty ? nil : cut
    }

    /// The summary as prose. Every `[ts]` it cites must be a journal time (a near miss is snapped onto one, anything else stripped).
    /// Nil when the journal is empty or the reply is unusable; callers fall back to the grouped minutes.
    ///
    /// A journal longer than the card's prose `segment_above` tokens (`segment_tokens` when 0) is summarized in parts of
    /// consecutive notes (`proseParts`), one fresh conversation and one full budget each, and the parts' paragraphs are
    /// joined in order: a single reply under one budget ends long before a long meeting does (Android `ReaderLane.prose`).
    func prose(_ journal: [Note]) -> String? {
        guard !journal.isEmpty else { return nil }
        let p = card.prose
        let known = Set(journal.map(\.ts))
        let sizes = p.segmentTokens > 0 ? journal.map { llm.tokenize(ReaderProtocol.render($0) + "\n", special: false).count } : []
        var parts = Self.proseParts(sizes, segmentTokens: p.segmentTokens, segmentMax: p.segmentMax, segmentAbove: p.segmentAbove)
        if parts.isEmpty { parts = [0...(journal.count - 1)] }
        var texts: [String] = []
        for (i, range) in parts.enumerated() {
            let part = Array(journal[range])
            llm.reset()
            let prompt: (String) -> String = i == 0 ? p.render : { p.renderSegment($0, from: part.first!.ts, to: part.last!.ts) }
            let toks = fitting(part, p.maxTokens, prompt)
            guard llm.append(toks) >= 0 else { continue }
            let raw = llm.generateContinue(maxTokens: p.maxTokens, stop: llm.template.end, temp: card.sampler.temp) { _ in }
            if let t = Self.cleanProse(raw, known: known, turnEnd: llm.template.end) { texts.append(t) }
        }
        let out = texts.joined(separator: "\n\n")
        return out.isEmpty ? nil : out
    }

    /// The prose summary's parts (Android `ReaderLane.proseParts`): consecutive note ranges of a journal whose notes take
    /// `sizes` tokens each. One part (the whole journal) when its total is at most `segmentAbove` tokens (`segmentTokens`
    /// when 0) or segmenting is off (`segmentTokens` 0); else ceil(total / `segmentTokens`) parts, at least 2 and at most
    /// `segmentMax`, cut where the running total crosses each equal share. Empty for no notes.
    static func proseParts(_ sizes: [Int], segmentTokens: Int, segmentMax: Int, segmentAbove: Int = 0) -> [ClosedRange<Int>] {
        if sizes.isEmpty { return [] }
        let total = sizes.reduce(0, +)
        let above = segmentAbove > 0 ? segmentAbove : segmentTokens
        let k = segmentTokens <= 0 || total <= above ? 1
            : min(max(2, (total + segmentTokens - 1) / segmentTokens), max(1, min(segmentMax, sizes.count)))
        if k == 1 { return [0...(sizes.count - 1)] }
        var out: [ClosedRange<Int>] = []
        var start = 0, run = 0
        for i in sizes.indices {
            run += sizes[i]
            let left = sizes.count - 1 - i, partsLeft = k - out.count - 1
            // Close here once this part's share is reached, keeping a note for each part still to come.
            if partsLeft > 0 && left >= partsLeft && (left == partsLeft || run * k >= total * (out.count + 1)) { out.append(start...i); start = i + 1 }
        }
        out.append(start...(sizes.count - 1))
        return out
    }

    /// A title kept short: 40 characters, or 60 cut at a word end for a spaced (English) one (Android `ReaderLane.cutTitle`).
    static func cutTitle(_ t: String) -> String {
        if t.count <= 40 { return t }
        if !t.contains(" ") { return String(t.prefix(40)) }
        if t.count <= 60 { return t }
        var c = String(t.prefix(61))
        if let sp = c.lastIndex(of: " ") { c = String(c[..<sp]) }
        while let l = c.last, ",;:- ".contains(l) { c.removeLast() }
        return c
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

    private static let doubleSpace = rx(#"(?<=\S)[ \t]{2,}(?=\S)"#)
    private static let spaceBeforePunct = rx(#"[ \t]+([。，、；：！？.,;:!?])"#)
    private static let sentenceEnd = rx(#"[。！？!?](\s*\[\d+:\d{2}(?::\d{2})?\])?|[.](?=\s|$)"#)

    /// The prose reply made presentable: `[ts]` tags checked against the notes' times (`ReaderProtocol.repairTimestamps`:
    /// kept, snapped onto a near one, or stripped), no headings or bullets, and a
    /// reply cut off by the card's prose budget (no end-of-turn) ends at its last whole sentence.
    static func cleanProse(_ raw: String, known: Set<String>, turnEnd: String = ModelCard.default.template.end) -> String? {
        let ended = raw.contains(turnEnd)
        let repaired = ReaderProtocol.repairTimestamps(raw.components(separatedBy: turnEnd)[0], known: Array(known))
        var text = replace(spaceBeforePunct, replace(doubleSpace, repaired, " "), "$1")
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
