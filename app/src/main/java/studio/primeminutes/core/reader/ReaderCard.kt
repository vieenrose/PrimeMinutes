package studio.primeminutes.core.reader

/**
 * The reader protocol the weights were fine-tuned on (headers, the reply grammar, caps, the minutes'
 * sections and the proposal guard), from the model card ([studio.primeminutes.core.models.ModelCard]).
 * The values of the shipped card are the port of github.com/vieenrose/meeting-summarizer
 * (`eval/phone_live.py`, `eval/realtime_agent.py`): change one only against a re-measurement upstream.
 */
data class ReaderCard(
    /** Most tokens a reading turn may write. */
    val readMax: Int,
    /** Most notes kept from one window. */
    val maxNotes: Int,
    /** Feed granularity, in meeting seconds. */
    val segmentS: Int,
    /** Ends a reading turn (kept in the conversation). */
    val stop: String,
    /** The model's reply in the primed prefix (system, journal, then this turn). */
    val primer: String,
    val dupLookback: Int,
    val dupJaccard: Double,
    val journalHeader: String,
    val journalEmpty: String,
    /** `{k}` = window number. */
    val windowHeaderPattern: String,
    /** `{n}` = notes left out. */
    val omittedPattern: String,
    /** A reply line: group 1 = the act, group 2 = its argument. */
    val act: Regex,
    /** The act that writes a note. */
    val noteAct: String,
    /** A note: group 1 = timestamp, 2 = tag (optional), 3 = text. */
    val note: Regex,
    /** Which tags a compacted journal keeps first, in order; the rest come after. */
    val compactPriority: List<String>,
    /** Which tags the per-window cap keeps first, by group; the first group keeps its newest notes. */
    val capPriority: List<List<String>>,
    /** The minutes: (header, tag) in order. */
    val sections: List<Pair<String, String>>,
    /** The line under an empty section. */
    val sectionEmpty: String,
    val proposalTag: String,
    /** The tags the proposal guard looks at. */
    val reclassifyTags: List<String>,
    val actionTag: String,
    /** A [reclassifyTags] note worded as a suggestion… */
    val proposalCue: Regex,
    /** …with none of these words is a proposal. */
    val decided: Regex,
    /** Title and prose read the journal compacted to this many characters (0 = all of it). */
    val notesChars: Int,
    /** What counts as off-protocol output, and how much of it is too much ([ReaderGuard]). */
    val guard: GuardCard = GuardCard(),
) {
    fun windowHeader(k: Int) = windowHeaderPattern.replace("{k}", k.toString())
    fun omitted(n: Int) = omittedPattern.replace("{n}", n.toString())

    private fun compactRank(tag: String?): Int = compactPriority.indexOf(tag?.uppercase()).let { if (it < 0) compactPriority.size else it }

    /** The cap's rank of a tag: its group in [capPriority], the rest last. */
    fun capRank(tag: String?): Int = capPriority.indexOfFirst { tag?.uppercase() in it }.let { if (it < 0) capPriority.size else it }

    /** conversion_prompts.compact_notes: the notes a small context can take for the title and prose
     *  calls — [compactPriority] first, newest first within each, then the rest newest first, each
     *  costing its text length + 24 characters, kept in chronological order. */
    fun compactNotes(notes: List<Note>, budgetChars: Int): List<Note> {
        val order = notes.indices.sortedWith(compareBy<Int>({ compactRank(notes[it].tag) }, { -it }))
        val chosen = HashSet<Int>()
        var used = 0
        for (i in order) {
            val t = notes[i].text.length + 24
            if (used + t <= budgetChars) { chosen += i; used += t }
        }
        return chosen.sorted().map { notes[it] }
    }

    /** phone_live.compact: the same order within [budget] tokens; kept in chronological order. */
    fun compact(journal: List<Note>, count: (String) -> Int, budget: Int): String {
        val order = journal.indices.sortedWith(compareBy<Int>({ compactRank(journal[it].tag) }, { -it }))
        val chosen = HashSet<Int>()
        var used = 0
        for (i in order) {
            val t = count(ReaderProtocol.render(journal[i]))
            if (used + t <= budget) { chosen += i; used += t }
        }
        val rest = journal.size - chosen.size
        val text = chosen.sorted().joinToString("\n") { ReaderProtocol.render(journal[it]) } + (if (rest > 0) omitted(rest) else "")
        return text.ifEmpty { journalEmpty }
    }

    /** realtime_agent.reclassify_proposals: a DECISION or ACTION worded as a suggestion, with no
     *  word saying it was decided, is a proposal. Applied when the minutes are assembled. */
    fun reclassify(n: Note): Note {
        val tag = n.tag?.uppercase()
        return if (tag in reclassifyTags && proposalCue.containsMatchIn(n.text) && !decided.containsMatchIn(n.text))
            n.copy(tag = proposalTag) else n
    }

    /** realtime_agent v5 minutes: the checked notes grouped by type; every item keeps its `[ts]`. */
    fun minutes(journal: List<Note>): String {
        val notes = journal.map(::reclassify)
        val out = ArrayList<String>()
        for ((title, tag) in sections) {
            val items = notes.filter { it.tag?.uppercase() == tag }
            out += "【$title】"
            out += if (items.isEmpty()) listOf(sectionEmpty) else items.map { "- ${it.text.trimEnd('。')} [${it.ts}]" }
        }
        return out.joinToString("\n")
    }

    fun actions(journal: List<Note>): List<Note> = journal.map(::reclassify).filter { it.tag.equals(actionTag, ignoreCase = true) }
}
