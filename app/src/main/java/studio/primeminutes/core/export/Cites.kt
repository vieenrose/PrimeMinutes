package studio.primeminutes.core.export

/**
 * Where a cite sits in exported text (was the mail's "design B"): a `[m:ss]` moved to the end of its
 * sentence and glued to it, so it never wraps alone. Pure; used by [ExportNotes] and the PDF.
 */
internal object Cites {
    private val TS = Regex("""\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)\]""")
    private val EMPTY = setOf("無", "无", "None", "-", "")

    /** Nothing to read: empty, a "none" marker, or no letter at all (a stray `3`, `-`, `…`). */
    fun trivial(text: String): Boolean = text.trim() in EMPTY || text.none { it.isLetter() }

    private const val WJ = "\u2060"
    private val HAN = Regex("""[㐀-鿿　-〿＀-￯]""")
    private val SPACED_TS = Regex("""[ \t]*""" + TS.pattern)
    val TERM = "。！？!?"

    /** The glue before a trailing cite: U+2060 after Han / fullwidth text (never a space, never a
     *  break), a no-break space after Latin text — the `[…]` never wraps alone to the next line. */
    private fun glue(before: String): String =
        if (before.isNotEmpty() && HAN.matches(before.last().toString())) WJ else "\u00A0"

    /** A minutes item's trailing cite: `…條款。⁠[1:12]` (a Han item gets its full stop back). */
    fun itemCite(text: String, ts: String?): String {
        if (ts == null) return ""
        val stop = if (text.isNotEmpty() && HAN.matches(text.last().toString()) && text.last() !in TERM + "」』）") "。" else ""
        return stop + glue(text + stop) + "[$ts]"
    }

    /** Sentences of [p], each with its terminator and following whitespace. A `.` ends one only before
     *  whitespace / the end (not `3.5`); closing brackets/quotes stay with their sentence. */
    private fun sentences(p: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < p.length) {
            val c = p[i]
            val ends = c in TERM || (c == '.' && (i + 1 == p.length || p[i + 1].isWhitespace()))
            if (ends && !inCite(p, i)) {
                var j = i + 1
                while (j < p.length && (p[j] in TERM || p[j] in "」』）)\"'")) j++
                while (j < p.length && p[j].isWhitespace()) j++
                out += p.substring(start, j); start = j; i = j; continue
            }
            i++
        }
        if (start < p.length) out += p.substring(start)
        return out
    }

    private fun inCite(p: String, i: Int): Boolean = p.lastIndexOf('[', i) > p.lastIndexOf(']', i)

    /**
     * Design B for prose: keep the FIRST cite of each sentence (as written: `[0:18, 0:35]` stays one
     * cite), move it after the sentence's full stop, glued; drop an exact repeat of the previous
     * sentence's cite. Bracket/separator shape untouched, so TS / ANCHOR_RE / repairTimestamps parse it.
     */
    fun clauseEndCites(p: String): String = buildString {
        var last: String? = null
        val all = sentences(p)
        for ((si, sen) in all.withIndex()) {
            val cite = TS.find(sen)?.value
            if (cite == null) { append(sen); continue }
            val body = SPACED_TS.replace(sen, "")
            val ws = body.length - body.trimEnd().length
            val core = body.trimEnd()
            var k = core.length
            while (k > 0 && (core[k - 1] in TERM || core[k - 1] == '.' || core[k - 1] in "」』）)\"'")) k--
            val head = core.substring(0, k).trimEnd()
            val tail = core.substring(k)
            append(head).append(tail)
            if (cite != last) {
                append(glue(head + tail)).append(cite)
                // One plain space between a cite and the next sentence (`…上班。⁠[0:00] 在搬家…`),
                // never at the end of the line.
                if (ws == 0 && si < all.lastIndex) append(' ')
            }
            last = cite
            append(body.substring(body.length - ws))
        }
    }


    private const val CLOSERS = "」』）)\"'"

    /** Where the sentence ending in the cite at [citeStart] begins (not before [floor]). */
    fun sentenceStart(text: String, citeStart: Int, floor: Int): Int {
        var i = citeStart - 1
        // Back over the glue and the sentence's own terminator (the cite follows its full stop).
        while (i >= floor && (text[i].isWhitespace() || text[i] == '⁠' || text[i] in TERM || text[i] == '.' || text[i] in CLOSERS)) i--
        while (i >= floor && !(text[i] in TERM || (text[i] == '.' && i + 1 < text.length && text[i + 1].isWhitespace()))) i--
        var s = maxOf(i + 1, floor)
        while (s < citeStart && (text[s].isWhitespace() || text[s] == '⁠')) s++
        return s
    }

}
