package studio.primeminutes.core.reader

import java.util.TreeMap

/**
 * The meeting reader's protocol, ported from github.com/vieenrose/meeting-summarizer
 * (`eval/phone_live.py`, `eval/realtime_agent.py` harness v3, `summarizer/ingest.py`): the transcript
 * lines and journal notes as the model reads them. A port, not a design choice.
 */
object ReaderProtocol {
    // The model-specific part (headers, reply grammar, caps, minutes) is the model card's: ReaderCard.

    /** ingest.format_ts: `M:SS` under an hour, `H:MM:SS` from an hour on. */
    fun formatTs(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        // Locale.ROOT: the model reads and writes these (a default locale may use other digits)
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s) else "%d:%02d".format(java.util.Locale.ROOT, m, s)
    }

    /** Inverse of [formatTs]; null when not a timestamp. */
    fun parseTs(ts: String): Int? {
        val p = ts.split(":").map { it.toIntOrNull() ?: return null }
        return when (p.size) {
            2 -> p[0] * 60 + p[1]
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            else -> null
        }
    }

    /** How far (seconds) a cited time may be from a citable one to be moved onto it ([repairTimestamps]). */
    const val SNAP_S = 30

    /** A bracket holding only digits and separators: a timestamp tag, well-formed or not. */
    private val TS_TAG = Regex("""(?<!\[)\[([0-9:：\s,，、;；\-–~～]*\d[0-9:：\s,，、;；\-–~～]*)\](?!\])""")
    private val TS_SEP = Regex("""[\s,，、;；\-–~～]+""")

    /**
     * The timestamp tags of model-written text checked against the times it may cite ([known], as the
     * notes write them): a tag naming a known time stays; one within [snapS] seconds of a known time is
     * moved onto it; anything else is dropped. Covers what small models write besides `[m:ss]`: `[5:6]`
     * (one-digit seconds, or m:s0–m:s9 cut off), lists and ranges (`[8:03, 11:04, 7]`, `[1:02-1:30]`, each item
     * checked on its own) and bare numbers (`[21]`, ambiguous: dropped). A bracket holding anything
     * but digits and separators is not a timestamp and is left alone.
     */
    fun repairTimestamps(text: String, known: Collection<String>, snapS: Int = SNAP_S): String {
        val bySec = TreeMap<Int, String>()
        known.forEach { k -> parseTs(k)?.let { bySec.putIfAbsent(it, k) } }
        return TS_TAG.replace(text) { m ->
            m.groupValues[1].replace('：', ':').split(TS_SEP).filter { it.isNotEmpty() }
                .mapNotNull { item -> tsSpans(item)?.let { nearest(bySec, it, snapS) } }
                .distinct().joinToString(" ") { "[$it]" }
        }
    }

    /** The second ranges a cited item can mean: `m:ss`/`h:mm:ss` exactly; `m:s` m:0s, or a cut-off
     *  m:s0–m:s9 when that is a time. Null for a bare number or nonsense. */
    private fun tsSpans(item: String): List<IntRange>? {
        val p = item.split(":")
        if (p.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
        val n = p.map { it.toInt() }
        return when {
            p.size == 2 && p[1].length == 2 && n[1] < 60 -> listOf(n[0] * 60 + n[1]).map { it..it }
            p.size == 2 && p[1].length == 1 -> listOf(n[0] * 60 + n[1]..n[0] * 60 + n[1]) +
                (if (n[1] <= 5) listOf(n[0] * 60 + n[1] * 10..n[0] * 60 + n[1] * 10 + 9) else emptyList())
            p.size == 3 && p[1].length == 2 && p[2].length == 2 && n[1] < 60 && n[2] < 60 -> listOf(n[0] * 3600 + n[1] * 60 + n[2]).map { it..it }
            else -> null
        }
    }

    /** The known time closest to any of [spans] (the earliest on a tie), if within [snapS]. */
    private fun nearest(bySec: TreeMap<Int, String>, spans: List<IntRange>, snapS: Int): String? {
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for ((k, v) in bySec) {
            val d = spans.minOf { r -> if (k in r) 0 else minOf(kotlin.math.abs(k - r.first), kotlin.math.abs(k - r.last)) }
            if (d < bestD) { bestD = d; best = v }
        }
        return best.takeIf { bestD <= snapS }
    }

    private val LINE = Regex("""^\[(\d+:\d{2}(?::\d{2})?)\]\s*(.*)$""")
    private val SPEAKER = Regex("""S\d+|[^\s:]{1,20}""")

    /** ingest.parse_line: `[ts] speaker: text`, speaker optional; null for a non-transcript line. */
    fun parseLine(raw: String): Line? {
        val m = LINE.find(raw.trim()) ?: return null
        val start = parseTs(m.groupValues[1]) ?: return null
        val rest = m.groupValues[2]
        val i = rest.indexOf(": ")
        return if (i > 0 && SPEAKER.matches(rest.substring(0, i))) Line(start, rest.substring(0, i), rest.substring(i + 2))
        else Line(start, null, rest)
    }

    private val NON_SPEECH_TAG = Regex("""\[[A-Za-z][A-Za-z _-]*\]""")
    private val FILLER = Regex("""(?:(?<=^)|(?<=[，。！？、\s]))[嗯啊呃]+[，。、]?""")
    private val WS = Regex("""\s+""")

    /** ingest.clean_text. */
    fun cleanText(text: String): String =
        WS.replace(FILLER.replace(NON_SPEECH_TAG.replace(text, ""), ""), " ").trim()

    /** realtime_agent.similar: character-bigram Jaccard. */
    fun similar(a: String, b: String): Double {
        fun bg(t: String): Set<String> = (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
        val x = bg(a)
        val y = bg(b)
        return (x intersect y).size.toDouble() / maxOf(1, (x union y).size)
    }

    /** realtime_agent.render: a journal note as the model reads it back. */
    fun render(n: Note): String = "#${n.id} [${n.ts}] " + (n.tag?.let { "($it) " } ?: "") + n.text
}

/** One transcript line as the model reads it (ingest.Line). [speaker] is "S1", "S2", … or null. */
data class Line(val startS: Int, val speaker: String?, val text: String) {
    fun render(): String = "[${ReaderProtocol.formatTs(startS)}] " + (speaker?.let { "$it: " } ?: "") + text
}

/** A journal entry. [tag] is DECISION / ACTION / NUMBER / OPEN-ISSUE / "-" or null. */
data class Note(val id: Int, val window: Int, val ts: String, val tag: String?, val text: String)

/**
 * Window size, context budget and the journal budget on restart, from the model card. [STANDARD]
 * is the upstream 8k protocol (§4, the parity golden). The shipped card holds the LiteRT mobile
 * graphs' 4k protocol (§12.3): a 1,500-token window and a 1,200-token compacted journal — with 4k
 * of context the restart check fires before every window, so each window is read from a fresh
 * prompt (system, compacted journal, window), exactly the per-window protocol.
 */
data class ReaderBudget(val windowTokens: Int, val ctxBudget: Int, val restartBudget: Int) {
    companion object {
        /** phone_live.py: realtime_agent.WINDOW_TOKENS, the 8k context (note §4.4), the compacted journal on restart. */
        val STANDARD = ReaderBudget(windowTokens = 2000, ctxBudget = 8192, restartBudget = 2500)
    }
}
