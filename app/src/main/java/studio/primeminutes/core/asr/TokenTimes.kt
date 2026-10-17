package studio.primeminutes.core.asr

/**
 * Transcript tokens and their start times — the unit the speaker-boundary editor moves
 * (docs/design/speaker-boundary.md): one Han character, or one Latin word, with the punctuation and
 * spaces that follow it attached, so tokens concatenate back to the exact text.
 *
 * The engine reports a start time per codepoint of its raw segment text (model token times, see
 * nemo_jni.cpp); [fromCodepoints] maps them onto the tokens of the cleaned-up text the app keeps.
 * Mirrored 1:1 in ios/App/BoundaryEdits.swift (enum TokenTimes).
 */
object TokenTimes {

    private fun isHan(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
    private fun isWord(cp: Int) = Character.isLetterOrDigit(cp) && !isHan(cp) || cp == '\''.code || cp == '’'.code || cp == '-'.code

    /** Lossless tokenization: joinToString("") == text. */
    fun tokenize(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            when {
                isHan(cp) -> { out += StringBuilder().appendCodePoint(cp); i += n }
                isWord(cp) -> {
                    val sb = StringBuilder()
                    while (i < text.length && isWord(text.codePointAt(i))) {
                        val c = text.codePointAt(i); sb.appendCodePoint(c); i += Character.charCount(c)
                    }
                    out += sb
                }
                else -> {   // space / punctuation: attach to the previous token (or lead the first one)
                    if (out.isEmpty()) out += StringBuilder()
                    out.last().appendCodePoint(cp); i += n
                }
            }
        }
        return out.map { it.toString() }.filter { it.isNotEmpty() }
    }

    /**
     * Start time (absolute seconds) of every token of [text], given the engine's [raw] text and the
     * start of each of its codepoints ([cpStarts], absolute). [text] is [raw] after cleanup (dropped
     * characters, collapsed repeats, removed spaces), so the two are aligned greedily on non-space
     * codepoints with a short look-ahead. Null when they do not line up well enough to trust.
     */
    fun fromCodepoints(raw: String, cpStarts: List<Double>, text: String): List<Double>? {
        val rawCps = raw.codePoints().toArray()
        if (rawCps.size != cpStarts.size || rawCps.isEmpty()) return null
        val rawIdx = rawCps.indices.filter { !Character.isWhitespace(rawCps[it]) }
        val toks = tokenize(text)
        if (toks.isEmpty()) return null
        val out = ArrayList<Double?>(toks.size)
        var j = 0          // next unmatched position in rawIdx
        var matched = 0; var total = 0
        for (tok in toks) {
            var first: Double? = null
            for (cp in tok.codePoints().toArray()) {
                if (Character.isWhitespace(cp)) continue
                total++
                var k = j
                while (k < rawIdx.size && k < j + LOOKAHEAD && rawCps[rawIdx[k]] != cp) k++
                if (k < rawIdx.size && k < j + LOOKAHEAD) {
                    if (first == null) first = cpStarts[rawIdx[k]]
                    j = k + 1; matched++
                }
            }
            out += first
        }
        if (total == 0 || matched * 2 < total) return null
        // Unmatched tokens take the previous token's time (or the next one's at the start); then make
        // the sequence non-decreasing.
        val res = DoubleArray(out.size)
        var last: Double? = null
        for (i in out.indices) { out[i]?.let { last = it }; res[i] = last ?: Double.NaN }
        val firstKnown = out.firstNotNullOfOrNull { it } ?: return null
        for (i in res.indices) if (res[i].isNaN()) res[i] = firstKnown
        for (i in 1 until res.size) if (res[i] < res[i - 1]) res[i] = res[i - 1]
        return res.toList()
    }

    /** Relative token times (seconds after [startSec]) rounded to 10 ms: what is stored per line. */
    fun relative(abs: List<Double>, startSec: Double): List<Double> =
        abs.map { Math.round((it - startSec) * 100.0) / 100.0 }

    private const val LOOKAHEAD = 12
}
