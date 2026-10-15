import Foundation

/// Everything model-specific the app needs to drive a reader model (port of Android `ModelCard`), read
/// from the bundled `model_cards/<name>.json` — the same file the Android app ships: chat template,
/// sampler, budgets, the reader protocol the weights were trained on, and the title/prose/Ask prompts.
/// New weights with another template or protocol need a new card, not new code. The reader's system
/// prompt is not in the card: it ships next to the weights.
struct ModelCard {
    let name: String
    let template: ChatTemplate
    let sampler: Sampler
    let budget: ReaderBudget
    let reader: ReaderCard
    let title: OneShot
    let prose: OneShot
    let ask: AskCard

    struct Sampler: Equatable { let topK: Int, topP: Float, temp: Float }

    /// A one-shot instruction over the notes; `prompt` has a `{notes}` slot. `label` strips a label the
    /// model puts before its answer ("標題：…").
    struct OneShot {
        let prompt: String, maxTokens: Int, label: NSRegularExpression?
        func render(_ notes: String) -> String { prompt.replacingOccurrences(of: "{notes}", with: notes) }
    }

    /// Ask's answer budget and its system prompt per answer language (`zh-Hans`, `zh-Hant`, `en`).
    struct AskCard { let answerTokens: Int, noExcerpts: String, system: [String: String] }

    static let schema = 1

    /// The card the shipped models use.
    static let `default`: ModelCard = load("gemma4-meeting-agent-zh")

    /// From the app bundle, else `$VOX_MODEL_CARDS/` (the host test runner).
    static func load(_ name: String) -> ModelCard {
        let url = Bundle.main.url(forResource: name, withExtension: "json", subdirectory: "model_cards")
            ?? ProcessInfo.processInfo.environment["VOX_MODEL_CARDS"].map { URL(fileURLWithPath: $0).appendingPathComponent("\(name).json") }
        guard let url, let data = try? Data(contentsOf: url) else { fatalError("no model card \(name)") }
        do { return try parse(data) } catch { fatalError("model card \(name): \(error)") }
    }

    private struct Raw: Decodable {
        struct T: Decodable { let bos: String, bos_id: Int, system: String, user: String, model: String, end: String, sep: String, stop_ids: [Int] }
        struct S: Decodable { let top_k: Int, top_p: Float, temp: Float }
        struct B: Decodable { let window_tokens: Int, ctx_budget: Int, restart_budget: Int }
        struct R: Decodable {
            let read_max: Int, max_notes: Int, segment_s: Int, stop: String, primer: String, dup_lookback: Int, dup_jaccard: Double
            let journal_header: String, journal_empty: String, window_header: String, omitted: String
            let act: String, note_act: String, note: String, compact_priority: [String], cap_priority: [[String]]
            let sections: [[String]], section_empty: String, proposal_tag: String, reclassify_tags: [String], action_tag: String
            let proposal_cue: String, decided: String, notes_chars: Int
        }
        struct O: Decodable { let prompt: String, max_tokens: Int, label: String? }
        struct A: Decodable { let answer_tokens: Int, no_excerpts: String, system: [String: String] }
        let schema: Int, name: String, template: T, sampler: S, budget: B, reader: R, title: O, prose: O, ask: A
    }

    static func parse(_ data: Data) throws -> ModelCard {
        let j = try JSONDecoder().decode(Raw.self, from: data)
        guard j.schema == schema else { throw ReaderError(description: "model card schema \(j.schema), expected \(schema)") }
        let t = j.template, r = j.reader
        func one(_ o: Raw.O) -> OneShot { OneShot(prompt: o.prompt, maxTokens: o.max_tokens, label: o.label.map(rx)) }
        return ModelCard(
            name: j.name,
            template: ChatTemplate(bos: t.bos, bosId: t.bos_id, system: t.system, user: t.user, model: t.model, end: t.end, sep: t.sep, stopIds: Set(t.stop_ids)),
            sampler: Sampler(topK: j.sampler.top_k, topP: j.sampler.top_p, temp: j.sampler.temp),
            budget: ReaderBudget(windowTokens: j.budget.window_tokens, ctxBudget: j.budget.ctx_budget, restartBudget: j.budget.restart_budget),
            reader: ReaderCard(
                readMax: r.read_max, maxNotes: r.max_notes, segmentS: r.segment_s, stop: r.stop, primer: r.primer,
                dupLookback: r.dup_lookback, dupJaccard: r.dup_jaccard, journalHeader: r.journal_header, journalEmpty: r.journal_empty,
                windowHeaderPattern: r.window_header, omittedPattern: r.omitted, act: rx(r.act), noteAct: r.note_act, note: rx(r.note),
                compactPriority: r.compact_priority, capPriority: r.cap_priority, sections: r.sections.map { ($0[0], $0[1]) },
                sectionEmpty: r.section_empty, proposalTag: r.proposal_tag, reclassifyTags: r.reclassify_tags, actionTag: r.action_tag,
                proposalCue: rx(r.proposal_cue), decided: rx(r.decided), notesChars: r.notes_chars),
            title: one(j.title), prose: one(j.prose),
            ask: AskCard(answerTokens: j.ask.answer_tokens, noExcerpts: j.ask.no_excerpts, system: j.ask.system))
    }
}

/// The reader protocol the weights were fine-tuned on (Android `ReaderCard`): headers, the reply
/// grammar, caps, the minutes' sections and the proposal guard. The shipped card's values are the
/// port of vieenrose/meeting-summarizer (`eval/phone_live.py`): change one only against a re-measurement.
struct ReaderCard {
    let readMax: Int, maxNotes: Int, segmentS: Int
    /// Ends a reading turn; `primer` is the model's reply in the primed prefix.
    let stop: String, primer: String
    let dupLookback: Int, dupJaccard: Double
    let journalHeader: String, journalEmpty: String
    /// `{k}` = window number; `{n}` = notes left out.
    let windowHeaderPattern: String, omittedPattern: String
    /// A reply line (group 1 = act, 2 = argument); the act that writes a note; a note (ts, tag, text).
    let act: NSRegularExpression, noteAct: String, note: NSRegularExpression
    /// Which tags a compacted journal keeps first; which the per-window cap keeps first, by group.
    let compactPriority: [String], capPriority: [[String]]
    /// The minutes: (header, tag) in order, and the line under an empty section.
    let sections: [(String, String)], sectionEmpty: String
    /// A `reclassifyTags` note worded as a suggestion (`proposalCue`) with none of the `decided` words is a proposal.
    let proposalTag: String, reclassifyTags: [String], actionTag: String
    let proposalCue: NSRegularExpression, decided: NSRegularExpression
    /// Title and prose read the journal compacted to this many characters (0 = all of it).
    let notesChars: Int

    func windowHeader(_ k: Int) -> String { windowHeaderPattern.replacingOccurrences(of: "{k}", with: String(k)) }
    func omitted(_ n: Int) -> String { omittedPattern.replacingOccurrences(of: "{n}", with: String(n)) }

    private func compactRank(_ n: Note) -> Int { compactPriority.firstIndex(of: n.tag?.uppercased() ?? "") ?? compactPriority.count }
    /// The cap's rank of a tag: its group in `capPriority`, the rest last.
    func capRank(_ tag: String?) -> Int { capPriority.firstIndex { $0.contains(tag?.uppercased() ?? "") } ?? capPriority.count }
    private func order(_ notes: [Note]) -> [Int] {
        notes.indices.sorted { (compactRank(notes[$0]), -$0) < (compactRank(notes[$1]), -$1) }
    }

    /// conversion_prompts.compact_notes (title/prose input for a small context).
    func compactNotes(_ notes: [Note], budgetChars: Int) -> [Note] {
        var chosen = Set<Int>(), used = 0
        for i in order(notes) {
            let t = notes[i].text.utf16.count + 24
            if used + t <= budgetChars { chosen.insert(i); used += t }
        }
        return chosen.sorted().map { notes[$0] }
    }

    /// phone_live.compact: the journal that fits `budget` tokens after a restart.
    func compact(_ journal: [Note], count: (String) -> Int, budget: Int) -> String {
        var chosen = Set<Int>(), used = 0
        for i in order(journal) {
            let t = count(ReaderProtocol.render(journal[i]))
            if used + t <= budget { chosen.insert(i); used += t }
        }
        let rest = journal.count - chosen.count
        let text = chosen.sorted().map { ReaderProtocol.render(journal[$0]) }.joined(separator: "\n") + (rest > 0 ? omitted(rest) : "")
        return text.isEmpty ? journalEmpty : text
    }

    /// realtime_agent.reclassify_proposals.
    func reclassify(_ n: Note) -> Note {
        guard reclassifyTags.contains(n.tag?.uppercased() ?? ""), find(proposalCue, n.text), !find(decided, n.text) else { return n }
        var m = n; m.tag = proposalTag; return m
    }

    /// The checked notes grouped by type; every item keeps its `[ts]`.
    func minutes(_ journal: [Note]) -> String {
        let notes = journal.map(reclassify)
        var out: [String] = []
        for (title, tag) in sections {
            let items = notes.filter { $0.tag?.uppercased() == tag }
            out.append("【\(title)】")
            out += items.isEmpty ? [sectionEmpty] : items.map { n in
                var t = n.text; while t.hasSuffix("。") { t.removeLast() }
                return "- \(t) [\(n.ts)]"
            }
        }
        return out.joined(separator: "\n")
    }

    func actions(_ journal: [Note]) -> [Note] { journal.map(reclassify).filter { $0.tag?.uppercased() == actionTag } }
}
