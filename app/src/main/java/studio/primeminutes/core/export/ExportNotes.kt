package studio.primeminutes.core.export

import studio.primeminutes.core.reader.Note

/**
 * The summary prose and the AI notes (reader journal) as the document exports lay them out — the
 * email's minutes layout: cites at the clause end, glued with U+2060 (Cites.clauseEndCites /
 * itemCite, never duplicated here), notes grouped under their localized type label, no `#N` index,
 * untyped (`-`) notes first and unlabelled. Pure, shared by TXT / MD / PDF.
 */
object ExportNotes {

    /** One note line: [text] without its cite; [ts] is the reader's `m:ss` (null = none). */
    data class Item(val text: String, val ts: String?)

    /** A type group; [label] null = the untyped notes. */
    data class Group(val label: String?, val items: List<Item>)

    /** The AI notes section of an export: its heading and the groups in display order. */
    data class Section(val heading: String, val groups: List<Group>)

    /** The reader's note types in display order (the email's minutes priority). */
    val ORDER = listOf("DECISION", "ACTION", "OPEN-ISSUE", "NUMBER", "PROPOSAL")

    /** A tag completed from its prefix ("ACT" → ACTION), as the Agent panel shows it; null for `-` / none. */
    fun fullTag(tag: String?): String? {
        val t = tag?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "-" } ?: return null
        return ORDER.firstOrNull { it.startsWith(t) } ?: t
    }

    /**
     * Groups [notes] by type: untyped first (no label), then [ORDER], then any unknown tag (shown
     * raw). [label] maps a full tag to its localized UI label (null = use the tag itself).
     */
    fun section(heading: String, notes: List<Note>, label: (String) -> String?, refs: (String) -> String = { it }): Section? {
        val by = LinkedHashMap<String?, MutableList<Item>>()
        by[null] = ArrayList()
        ORDER.forEach { by[it] = ArrayList() }
        for (n in notes) {
            val text = refs(n.text.trim()).trimEnd('。').trim()
            if (Cites.trivial(text)) continue
            by.getOrPut(fullTag(n.tag)) { ArrayList() } += Item(text, n.ts.trim().ifEmpty { null })
        }
        val groups = by.filterValues { it.isNotEmpty() }.map { (tag, items) -> Group(tag?.let { label(it) ?: it }, items) }
        return if (groups.isEmpty()) null else Section(heading, groups)
    }

    private val ITEM = Regex("""^[-*•・]\s*(.*?)\s*(?:\[(\d+:\d{2}(?::\d{2})?)])?\s*$""")

    /**
     * The action items block (raw `- text [m:ss]` lines from the extractor) as a formatted section:
     * cite at the line end, trivial lines dropped; a line that isn't a bullet is kept as an item.
     */
    fun actions(heading: String, raw: String?, refs: (String) -> String = { it }): Section? {
        val items = raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { l ->
            val m = ITEM.matchEntire(l)
            val text = refs((m?.groupValues?.get(1) ?: l).trim()).trimEnd('。').trim()
            if (Cites.trivial(text)) null else Item(text, m?.groupValues?.get(2)?.ifEmpty { null })
        }
        return if (items.isEmpty()) null else Section(heading, listOf(Group(null, items)))
    }

    /**
     * The action items of a shared PDF / Word document: always present, whatever the "show action
     * items" setting (as on iOS, spec v2 order: summary, then action items). The extractor's list
     * [raw] wins; without it, the reader's ACTION notes stand in (documents carry no AI notes).
     */
    fun documentActions(heading: String, raw: String?, notes: List<Note>?, refs: (String) -> String = { it }): Section? =
        actions(heading, raw, refs)
            ?: section(heading, notes.orEmpty().filter { fullTag(it.tag) == "ACTION" }.map { it.copy(tag = "-") }, { null }, refs)

    /** A note's text with its cite at the end, glued (`…條款。⁠[1:12]`). */
    fun line(i: Item): String = i.text + Cites.itemCite(i.text, i.ts)

    /** Summary prose: each paragraph with its cites moved to the clause end. */
    fun summary(text: String): String = paragraphs(text).joinToString("\n\n")

    /**
     * The summary's paragraphs, cites at the clause end: the model's own breaks (blank lines) kept,
     * and a paragraph longer than ~350 Han / ~600 Latin characters cut at the clause-end cites
     * nearest every ~250 / ~450 characters (only after a cite, so never inside a sentence).
     */
    fun paragraphs(text: String): List<String> =
        text.split(PARA).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { p ->
            split(p.lines().joinToString("\n") { Cites.clauseEndCites(it.trim()) })
        }

    private val PARA = Regex("""\n\s*\n""")
    private val CITE_END = Regex("""[\u2060\u00A0]\[[^\]\n]*] """)

    internal fun split(p: String): List<String> {
        val han = p.count { it in '㐀'..'鿿' }
        val zh = han * 2 > p.count { !it.isWhitespace() }
        val (limit, chunk) = if (zh) 350 to 250 else 600 to 450
        if (p.length <= limit) return listOf(p)
        val cuts = CITE_END.findAll(p).map { it.range.last + 1 }.filter { it < p.length }.toList()
        if (cuts.isEmpty()) return listOf(p)
        val chosen = ArrayList<Int>()
        var target = chunk
        while (target < p.length - chunk / 2) {
            val prev = chosen.lastOrNull() ?: 0
            val best = cuts.filter { it > prev }.minByOrNull { kotlin.math.abs(it - target) } ?: break
            if (p.length - best < chunk / 3) break   // no stub paragraph at the end
            chosen += best
            target = best + chunk
        }
        val out = ArrayList<String>()
        var start = 0
        for (c in chosen) { out += p.substring(start, c).trimEnd(); start = c }
        out += p.substring(start).trim()
        return out.filter { it.isNotEmpty() }
    }

    private val CITE = Regex("""\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)\]""")

    private val TIME = Regex("""\d+:\d{2}(?::\d{2})?""")

    /** `m:ss` / `h:mm:ss` → seconds. */
    fun seconds(t: String): Int = t.split(':').fold(0) { acc, p -> acc * 60 + p.toInt() }

    /** Index of the transcript turn a cite at [sec] jumps to: the last one starting at or before it, else the first. */
    fun targetFor(sec: Int, turnStarts: List<Double>): Int? {
        if (turnStarts.isEmpty()) return null
        var best = 0
        for ((i, s) in turnStarts.withIndex()) if (s.toInt() <= sec) best = i
        return best
    }

    /** The Markdown anchor id of a turn starting at [sec]. */
    fun anchor(sec: Double): String = "t-" + (if (sec > 0) sec.toInt() else 0)

    /**
     * Markdown cites: each time becomes an internal link to its transcript turn, `[0:15](#t-15)`
     * (a multi-cite one link per time, the separator kept); with no turns, a code span `` `0:15` ``.
     */
    fun mdCites(text: String, turnStarts: List<Double> = emptyList()): String = CITE.replace(text) { m ->
        if (turnStarts.isEmpty()) "`" + m.groupValues[1] + "`"
        else TIME.replace(m.groupValues[1]) { t ->
            val target = turnStarts[targetFor(seconds(t.value), turnStarts)!!]
            "[${t.value}](#${anchor(target)})"
        }
    }

    private fun isZh(s: String) = s.any { it in '㐀'..'鿿' }

    /** Plain-text heading, as the email's plain text: `【摘要】` in Chinese, upper case in English. */
    fun plainHeading(h: String): String = if (isZh(h)) "【$h】" else h.uppercase()

    /** The AI notes as plain text: the section heading, then `Label:` sub-headings and bulleted lines. */
    fun plain(s: Section): String = buildString {
        val zh = isZh(s.heading)
        val bullet = if (zh) "・" else "- "
        append(plainHeading(s.heading)).append('\n')
        for ((gi, g) in s.groups.withIndex()) {
            if (gi > 0) append('\n')
            g.label?.let { append(it).append(if (zh) "：" else ":").append('\n') }
            g.items.forEach { append(bullet).append(line(it)).append('\n') }
        }
    }.trimEnd()

    /** The AI notes as Markdown: `## heading`, `### label` per type, `- text。⁠`0:15`` bullets. */
    fun markdown(s: Section, turnStarts: List<Double> = emptyList()): String = buildString {
        append("## ").append(s.heading).append("\n\n")
        for (g in s.groups) {
            g.label?.let { append("### ").append(it).append("\n\n") }
            g.items.forEach { append("- ").append(mdCites(line(it), turnStarts)).append('\n') }
            append('\n')
        }
    }.trimEnd()
}
