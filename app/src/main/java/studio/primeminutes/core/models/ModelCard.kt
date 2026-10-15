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
) {
    /** A one-shot instruction over the notes; [prompt] has a `{notes}` slot. [label] strips a label
     *  the model puts before its answer ("標題：…"), or null. */
    data class OneShot(val prompt: String, val maxTokens: Int, val label: Regex? = null) {
        fun render(notes: String) = prompt.replace("{notes}", notes)
    }

    /** Ask's answer budget and its system prompt per answer language (`zh-Hans`, `zh-Hant`, `en`). */
    data class AskCard(val answerTokens: Int, val noExcerpts: String, val system: Map<String, String>) {
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
            fun oneShot(o: JSONObject) = OneShot(o.getString("prompt"), o.getInt("max_tokens"), o.optString("label").ifEmpty { null }?.let(::Regex))
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
                    sections = r.getJSONArray("sections").let { g -> (0 until g.length()).map { g.getJSONArray(it).getString(0) to g.getJSONArray(it).getString(1) } },
                    sectionEmpty = r.getString("section_empty"),
                    proposalTag = r.getString("proposal_tag"), reclassifyTags = r.getJSONArray("reclassify_tags").strings(), actionTag = r.getString("action_tag"),
                    proposalCue = Regex(r.getString("proposal_cue")), decided = Regex(r.getString("decided")),
                    notesChars = r.getInt("notes_chars"),
                ),
                title = oneShot(j.getJSONObject("title")),
                prose = oneShot(j.getJSONObject("prose")),
                ask = AskCard(a.getInt("answer_tokens"), a.getString("no_excerpts"), sys.keys().asSequence().associateWith { sys.getString(it) }),
            )
        }
    }
}
