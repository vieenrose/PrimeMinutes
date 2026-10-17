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
    var reader: ReaderCard
    var title: OneShot
    var prose: OneShot
    let ask: AskCard
    /// Per meeting language (`zh`, `en`, as `MeetingLanguage` detects it): what the card changes for a
    /// meeting in that language. A language without an entry uses the card as it is.
    var languages: [String: LanguageVariant] = [:]

    /// Card fields a meeting language overrides; nil = keep the card's own (Android `ModelCard.LanguageVariant`).
    struct LanguageVariant {
        var directive: String?, systemSuffix: String?, sections: [(String, String)]?, sectionEmpty: String?
        /// The proposal guard's words in this language (`ReaderCard.proposalCue`, `decided`).
        var proposalCue: NSRegularExpression? = nil, decided: NSRegularExpression? = nil
        var title: OneShot?, prose: OneShot?
    }

    /// This card for a meeting in `language` (nil or a language the card does not list: unchanged).
    func forLanguage(_ language: String?) -> ModelCard {
        guard let language, let v = languages[language] else { return self }
        var c = self
        c.reader.directive = v.directive ?? reader.directive
        c.reader.systemSuffix = v.systemSuffix ?? reader.systemSuffix
        c.reader.sections = v.sections ?? reader.sections
        c.reader.sectionEmpty = v.sectionEmpty ?? reader.sectionEmpty
        c.reader.proposalCue = v.proposalCue ?? reader.proposalCue
        c.reader.decided = v.decided ?? reader.decided
        c.title = v.title ?? title
        c.prose = v.prose ?? prose
        return c
    }

    struct Sampler: Equatable { let topK: Int, topP: Float, temp: Float }

    /// A one-shot instruction over the notes; `prompt` has a `{notes}` slot. `label` strips a label the
    /// model puts before its answer ("標題：…").
    struct OneShot {
        let prompt: String, maxTokens: Int, label: NSRegularExpression?
        func render(_ notes: String) -> String { prompt.replacingOccurrences(of: "{notes}", with: notes) }
    }

    /// Ask's answer budget and its system prompt per answer language (`zh-Hans`, `zh-Hant`, `en`).
    /// `answerIn`: per answer language, read after a question asked in another language than the system
    /// prompt's (the system prompt stays the interface language's: the conversation's cached prefix).
    struct AskCard { let answerTokens: Int, noExcerpts: String, system: [String: String]; var answerIn: [String: String] = [:] }

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
            let directive: String?, system_suffix: String?
            struct G: Decodable { let known_tags: [String]?, ignore_acts: [String]?, max_off_protocol_rate: Double?, min_lines: Int? }
            let guard_: G?
            enum CodingKeys: String, CodingKey {
                case read_max, max_notes, segment_s, stop, primer, dup_lookback, dup_jaccard, journal_header, journal_empty, window_header, omitted
                case act, note_act, note, compact_priority, cap_priority, sections, section_empty, proposal_tag, reclassify_tags, action_tag
                case proposal_cue, decided, notes_chars, directive, system_suffix, guard_ = "guard"
            }
        }
        struct O: Decodable { let prompt: String, max_tokens: Int, label: String? }
        struct A: Decodable { let answer_tokens: Int, no_excerpts: String, system: [String: String], answer_in: [String: String]? }
        /// A language's prompt keeps the base budget and label unless it gives its own.
        struct LO: Decodable { let prompt: String, max_tokens: Int?, label: String? }
        struct L: Decodable {
            let directive: String?, system_suffix: String?, sections: [[String]]?, section_empty: String?
            let proposal_cue: String?, decided: String?
            let title: LO?, prose: LO?
        }
        let schema: Int, name: String, template: T, sampler: S, budget: B, reader: R, title: O, prose: O, ask: A
        let languages: [String: L]?
    }

    static func parse(_ data: Data) throws -> ModelCard {
        let j = try JSONDecoder().decode(Raw.self, from: data)
        guard j.schema == schema else { throw ReaderError(description: "model card schema \(j.schema), expected \(schema)") }
        let t = j.template, r = j.reader
        func one(_ o: Raw.O) -> OneShot { OneShot(prompt: o.prompt, maxTokens: o.max_tokens, label: o.label.map(rx)) }
        func over(_ o: Raw.LO?, _ base: Raw.O) -> OneShot? {
            o.map { OneShot(prompt: $0.prompt, maxTokens: $0.max_tokens ?? base.max_tokens, label: ($0.label ?? base.label).map(rx)) }
        }
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
                proposalCue: rx(r.proposal_cue), decided: rx(r.decided), notesChars: r.notes_chars,
                guardrail: r.guard_.map { g in GuardCard(knownTags: Set((g.known_tags ?? []).map { $0.uppercased() }), ignoreActs: Set(g.ignore_acts ?? []),
                                                     maxOffProtocolRate: g.max_off_protocol_rate ?? 0.2, minLines: g.min_lines ?? 10) } ?? GuardCard(),
                directive: r.directive, systemSuffix: r.system_suffix),
            title: one(j.title), prose: one(j.prose),
            ask: AskCard(answerTokens: j.ask.answer_tokens, noExcerpts: j.ask.no_excerpts, system: j.ask.system, answerIn: j.ask.answer_in ?? [:]),
            languages: (j.languages ?? [:]).mapValues { v in
                LanguageVariant(directive: v.directive, systemSuffix: v.system_suffix, sections: v.sections?.map { ($0[0], $0[1]) }, sectionEmpty: v.section_empty,
                                proposalCue: v.proposal_cue.map(rx), decided: v.decided.map(rx),
                                title: over(v.title, j.title), prose: over(v.prose, j.prose))
            })
    }
}

/// The reader protocol the weights were fine-tuned on (Android `ReaderCard`): headers, the reply
/// grammar, caps, the minutes' sections and the proposal guard. The shipped card's values are the
/// port of vieenrose/meeting-summarizer (`eval/phone_live.py`): change one only against a re-measurement.
struct ReaderCard {
    let readMax: Int
    var maxNotes: Int   // var: tests pin upstream's 6
    let segmentS: Int
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
    var sections: [(String, String)], sectionEmpty: String
    /// A `reclassifyTags` note worded as a suggestion (`proposalCue`) with none of the `decided` words is a proposal.
    let proposalTag: String, reclassifyTags: [String], actionTag: String
    var proposalCue: NSRegularExpression, decided: NSRegularExpression
    /// Title and prose read the journal compacted to this many characters (0 = all of it).
    let notesChars: Int
    /// What counts as off-protocol output, and how much of it is too much (ReaderGuard).
    var guardrail: GuardCard = GuardCard()
    /// Appended verbatim after each window's transcript, just before the model's reading turn (nil = none,
    /// upstream's protocol). A card sets it per meeting language (`ModelCard.forLanguage`).
    var directive: String? = nil
    /// Appended to the reader's system prompt (the one shipped with the weights), or nil.
    var systemSuffix: String? = nil

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

/// The model card's guardrail settings (reader.guard, Android `GuardCard`). Absent from a card: no tag check, 20 %, 10 lines.
struct GuardCard: Equatable {
    /// The tags the model was trained to write; empty = any tag is fine.
    var knownTags: Set<String> = []
    /// Acts that end a turn rather than say something (NEXT).
    var ignoreActs: Set<String> = []
    var maxOffProtocolRate: Double = 0.2
    var minLines: Int = 10
    func known(_ tag: String?) -> Bool { tag == nil || knownTags.isEmpty || knownTags.contains(tag!.uppercased()) }
}

/// Model guardrail (Android `ReaderGuard`): how much of what the reader wrote in one job was off the
/// protocol its card describes, per non-blank reply line (the card's `ignoreActs` are not counted):
/// `nonNote` a line that is not a NOTE (never used); `malformed` a NOTE whose argument does not parse
/// (dropped); `unknownTag` a NOTE with a tag the card does not list (kept, but counted: the first sign of
/// a drifting model). Logged per job, a warning above the card's threshold, saved with the meeting, shown
/// in the AI notes panel (DEV).
struct ReaderGuard: Codable, Equatable {
    var lines = 0, notes = 0, nonNote = 0, unknownTag = 0, malformed = 0
    var offProtocol: Int { nonNote + unknownTag + malformed }
    var rate: Double { lines == 0 ? 0 : Double(offProtocol) / Double(lines) }
    /// Above the card's threshold, once there are enough lines for a rate to mean something.
    func exceeds(_ card: GuardCard) -> Bool { lines >= card.minLines && rate > card.maxOffProtocolRate }
    var summary: String { "lines=\(lines) notes=\(notes) nonNote=\(nonNote) unknownTag=\(unknownTag) malformed=\(malformed) rate=" + String(format: "%.2f", rate) }
}
