package studio.primeminutes.core.models

import org.json.JSONArray
import org.json.JSONObject
import studio.primeminutes.core.reader.ReaderBudget
import studio.primeminutes.core.reader.ReaderCard

/**
 * Everything model-specific the app needs to drive a reader model, read from a versioned
 * `model_cards/<name>.json` (src/main/resources; the iOS app bundles the same file): chat template,
 * sampler, budgets, the reader protocol the weights were trained on, and the plain-instruction
 * prompts (title, prose, Ask). The reader, Ask and the bench take their strings, regexes and numbers
 * from here, so new weights with another template or protocol need a new card, not new code.
 * The reader's system prompt is not in the card: it ships next to the weights.
 */
data class ModelCard(
    val name: String,
    val template: ChatTemplate,
    val sampler: SamplerProfile,
    val budget: ReaderBudget,
    val reader: ReaderCard,
    val title: OneShot,
    val prose: OneShot,
    val ask: AskCard,
    /** Per meeting language (`zh`, `en`, as [studio.primeminutes.core.reader.MeetingLanguage] detects
     *  it): what the card changes for a meeting in that language. A language without an entry uses the
     *  card as it is. */
    val languages: Map<String, LanguageVariant> = emptyMap(),
) {
    /** Card fields a meeting language overrides; null = keep the card's own. */
    data class LanguageVariant(
        val directive: String? = null,
        val systemSuffix: String? = null,
        val sections: List<Pair<String, String>>? = null,
        val sectionEmpty: String? = null,
        /** The proposal guard's words in this language ([ReaderCard.proposalCue], [ReaderCard.decided]). */
        val proposalCue: Regex? = null,
        val decided: Regex? = null,
        val title: OneShot? = null,
        val prose: OneShot? = null,
    )

    /** This card for a meeting in [language] (null or a language the card does not list: unchanged). */
    fun forLanguage(language: String?): ModelCard {
        val v = languages[language ?: return this] ?: return this
        return copy(
            reader = reader.copy(
                directive = v.directive ?: reader.directive, systemSuffix = v.systemSuffix ?: reader.systemSuffix, sections = v.sections ?: reader.sections,
                sectionEmpty = v.sectionEmpty ?: reader.sectionEmpty,
                proposalCue = v.proposalCue ?: reader.proposalCue, decided = v.decided ?: reader.decided,
            ),
            title = v.title ?: title, prose = v.prose ?: prose,
        )
    }

    /** A one-shot instruction over the notes; [prompt] has a `{notes}` slot. [label] strips a label
     *  the model puts before its answer ("標題：…"), or null.
     *
     *  Segmented (the prose summary): a journal longer than [segmentAbove] tokens ([segmentTokens]
     *  when 0) is written in about total / [segmentTokens] parts, at least 2 and at most [segmentMax] of consecutive notes, each one fresh conversation with its own [maxTokens]
     *  budget, joined as paragraphs: one reply under one budget stops before the end of a long meeting.
     *  The first part reads [prompt], the next ones [segmentPrompt] (`{notes}`, `{from}`, `{to}`: the
     *  part's first and last note times), or [prompt] when there is none. [segmentTokens] 0 = one part. */
    data class OneShot(
        val prompt: String, val maxTokens: Int, val label: Regex? = null,
        val segmentTokens: Int = 0, val segmentMax: Int = 1, val segmentPrompt: String? = null, val segmentAbove: Int = 0,
    ) {
        fun render(notes: String) = prompt.replace("{notes}", notes)
        fun renderSegment(notes: String, from: String, to: String) =
            (segmentPrompt ?: prompt).replace("{notes}", notes).replace("{from}", from).replace("{to}", to)
    }

    /** Ask's answer budget and its system prompt per answer language (`zh-Hans`, `zh-Hant`, `en`).
     *  [answerIn]: per answer language, read after a question asked in another language than the system
     *  prompt's (the system prompt stays the interface language's: the conversation's cached prefix). */
    data class AskCard(val answerTokens: Int, val noExcerpts: String, val system: Map<String, String>, val answerIn: Map<String, String> = emptyMap()) {
        fun system(lang: String): String = system.getValue(lang)
    }

    companion object {
        const val SCHEMA = 1

        /** The card the shipped models use. */
        val DEFAULT: ModelCard by lazy { load("gemma4-meeting-agent-zh") }

        fun load(name: String): ModelCard {
            val text = checkNotNull(ModelCard::class.java.getResourceAsStream("/model_cards/$name.json")) { "no model card $name" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
            return parse(text)
        }

        fun parse(text: String): ModelCard {
            val j = JSONObject(text)
            check(j.getInt("schema") == SCHEMA) { "model card schema ${j.getInt("schema")}, expected $SCHEMA" }
            val t = j.getJSONObject("template")
            val s = j.getJSONObject("sampler")
            val b = j.getJSONObject("budget")
            val r = j.getJSONObject("reader")
            val a = j.getJSONObject("ask")
            fun JSONArray.strings() = (0 until length()).map { getString(it) }
            fun oneShot(o: JSONObject) = OneShot(o.getString("prompt"), o.getInt("max_tokens"), o.optString("label").ifEmpty { null }?.let(::Regex),
                o.optInt("segment_tokens", 0), o.optInt("segment_max", 1), o.optString("segment_prompt").ifEmpty { null }, o.optInt("segment_above", 0))
            // A language's prompt keeps the base budget, label and segmenting unless it gives its own; a
            // segment prompt is in the language's words, so it is never inherited.
            fun oneShotOver(o: JSONObject?, base: JSONObject) = o?.let {
                OneShot(it.getString("prompt"), it.optInt("max_tokens", base.getInt("max_tokens")),
                    (it.optString("label").ifEmpty { null } ?: base.optString("label").ifEmpty { null })?.let(::Regex),
                    it.optInt("segment_tokens", base.optInt("segment_tokens", 0)), it.optInt("segment_max", base.optInt("segment_max", 1)),
                    it.optString("segment_prompt").ifEmpty { null }, it.optInt("segment_above", base.optInt("segment_above", 0)))
            }
            fun JSONArray.pairs() = (0 until length()).map { getJSONArray(it).getString(0) to getJSONArray(it).getString(1) }
            val sys = a.getJSONObject("system")
            return ModelCard(
                name = j.getString("name"),
                template = ChatTemplate(
                    bos = t.getString("bos"), bosId = t.getInt("bos_id"), system = t.getString("system"), user = t.getString("user"),
                    model = t.getString("model"), end = t.getString("end"), sep = t.getString("sep"),
                    stopIds = t.getJSONArray("stop_ids").let { ids -> (0 until ids.length()).map { ids.getInt(it) }.toSet() },
                ),
                sampler = SamplerProfile(s.getInt("top_k"), s.getDouble("top_p").toFloat(), s.getDouble("temp").toFloat()),
                budget = ReaderBudget(b.getInt("window_tokens"), b.getInt("ctx_budget"), b.getInt("restart_budget")),
                reader = ReaderCard(
                    readMax = r.getInt("read_max"), maxNotes = r.getInt("max_notes"), segmentS = r.getInt("segment_s"),
                    stop = r.getString("stop"), primer = r.getString("primer"),
                    dupLookback = r.getInt("dup_lookback"), dupJaccard = r.getDouble("dup_jaccard"),
                    journalHeader = r.getString("journal_header"), journalEmpty = r.getString("journal_empty"),
                    windowHeaderPattern = r.getString("window_header"), omittedPattern = r.getString("omitted"),
                    act = Regex(r.getString("act")), noteAct = r.getString("note_act"), note = Regex(r.getString("note")),
                    compactPriority = r.getJSONArray("compact_priority").strings(),
                    capPriority = r.getJSONArray("cap_priority").let { g -> (0 until g.length()).map { g.getJSONArray(it).strings() } },
                    sections = r.getJSONArray("sections").pairs(),
                    sectionEmpty = r.getString("section_empty"),
                    proposalTag = r.getString("proposal_tag"), reclassifyTags = r.getJSONArray("reclassify_tags").strings(), actionTag = r.getString("action_tag"),
                    proposalCue = Regex(r.getString("proposal_cue")), decided = Regex(r.getString("decided")),
                    notesChars = r.getInt("notes_chars"),
                    guard = r.optJSONObject("guard")?.let { g ->
                        studio.primeminutes.core.reader.GuardCard(
                            knownTags = g.optJSONArray("known_tags")?.strings()?.map { it.uppercase() }?.toSet() ?: emptySet(),
                            ignoreActs = g.optJSONArray("ignore_acts")?.strings()?.toSet() ?: emptySet(),
                            maxOffProtocolRate = g.optDouble("max_off_protocol_rate", 0.2),
                            minLines = g.optInt("min_lines", 10),
                        )
                    } ?: studio.primeminutes.core.reader.GuardCard(),
                    directive = r.optString("directive").ifEmpty { null },
                    systemSuffix = r.optString("system_suffix").ifEmpty { null },
                ),
                title = oneShot(j.getJSONObject("title")),
                prose = oneShot(j.getJSONObject("prose")),
                ask = AskCard(a.getInt("answer_tokens"), a.getString("no_excerpts"), sys.keys().asSequence().associateWith { sys.getString(it) },
                    a.optJSONObject("answer_in")?.let { ai -> ai.keys().asSequence().associateWith { ai.getString(it) } } ?: emptyMap()),
                languages = j.optJSONObject("languages")?.let { l ->
                    l.keys().asSequence().filter { l.opt(it) is JSONObject }.associateWith { code ->
                        val v = l.getJSONObject(code)
                        LanguageVariant(
                            directive = v.optString("directive").ifEmpty { null },
                            systemSuffix = v.optString("system_suffix").ifEmpty { null },
                            sections = v.optJSONArray("sections")?.pairs(),
                            sectionEmpty = v.optString("section_empty").ifEmpty { null },
                            proposalCue = v.optString("proposal_cue").ifEmpty { null }?.let(::Regex),
                            decided = v.optString("decided").ifEmpty { null }?.let(::Regex),
                            title = oneShotOver(v.optJSONObject("title"), j.getJSONObject("title")),
                            prose = oneShotOver(v.optJSONObject("prose"), j.getJSONObject("prose")),
                        )
                    }
                } ?: emptyMap(),
            )
        }
    }
}
