package studio.primeminutes.core.asr

import studio.primeminutes.core.events.TranscriptEvent.Utterance

/**
 * One speaker talking without a pause comes out of the engine as a single utterance, minutes long:
 * an unreadable wall of text whose every summary link seeks to the same second. Cut such an
 * utterance at sentence ends. With the engine's per-token times each piece starts at its first token;
 * without them the cut times are estimated from character counts (speech rate is close to constant
 * over a sentence), which is accurate to a second or two.
 */
object LongUtteranceSplitter {
    private const val MIN_SEC = 20.0
    private const val MIN_CHARS = 100
    private const val CHUNK_CHARS = 90          // a piece is closed at the first sentence end past this
    private val SENTENCE_END = setOf('。', '？', '！', '?', '!', '.', '；', ';')

    fun split(list: List<Utterance>): List<Utterance> {
        if (list.none { it.endSec - it.startSec > MIN_SEC && it.text.length > MIN_CHARS }) return list
        val out = ArrayList<Utterance>(list.size + 8)
        for (u in list) {
            val parts = if (u.endSec - u.startSec > MIN_SEC && u.text.length > MIN_CHARS) pieces(u.text) else listOf(u.text)
            if (parts.size <= 1) { out += u.copy(index = out.size); continue }
            val timed = timedPieces(u, parts)
            if (timed != null) {
                for ((i, tp) in timed.withIndex()) {
                    val end = timed.getOrNull(i + 1)?.second?.first() ?: u.endSec
                    val start = tp.second.first()
                    out += u.copy(index = out.size, text = tp.first, startSec = start, endSec = end,
                        tokens = null, tokenTimes = TokenTimes.relative(tp.second, start))
                }
                continue
            }
            val total = parts.sumOf { it.length }.toDouble()
            var at = u.startSec
            var used = 0
            for ((i, p) in parts.withIndex()) {
                used += p.length
                val end = if (i == parts.lastIndex) u.endSec else u.startSec + (u.endSec - u.startSec) * used / total
                // Without per-token times the cut times are estimated from character counts.
                out += u.copy(index = out.size, text = p.trim(), startSec = at, endSec = end, tokens = null, tokenTimes = null)
                at = end
            }
        }
        return out
    }

    /**
     * With per-token times (the engine's, [Utterance.tokenTimes]) each piece keeps its own tokens' times
     * and starts at its first token: (trimmed text, absolute token starts) per piece, or null.
     */
    private fun timedPieces(u: Utterance, parts: List<String>): List<Pair<String, List<Double>>>? {
        val tt = u.tokenTimes ?: return null
        val toks = TokenTimes.tokenize(u.text)
        if (tt.size != toks.size || parts.joinToString("") != u.text) return null
        // Time of the token each UTF-16 index of the text belongs to.
        val charTime = DoubleArray(u.text.length)
        var c = 0
        for ((k, t) in toks.withIndex()) repeat(t.length) { if (c < charTime.size) charTime[c++] = u.startSec + tt[k] }
        if (c != u.text.length) return null
        val res = ArrayList<Pair<String, List<Double>>>(parts.size)
        var pos = 0
        for (p in parts) {
            val lead = p.length - p.trimStart().length
            val text = p.trim()
            var off = pos + lead
            val times = TokenTimes.tokenize(text).map { t -> charTime[off.coerceAtMost(charTime.size - 1)].also { off += t.length } }
            if (text.isNotEmpty() && times.isNotEmpty()) res += text to times
            pos += p.length
        }
        return res.takeIf { it.size >= 2 }
    }

    private fun pieces(text: String): List<String> {
        val res = ArrayList<String>()
        val sb = StringBuilder()
        for ((i, c) in text.withIndex()) {
            sb.append(c)
            // ASCII '.' only ends a sentence when followed by a space (not 3.14, not "e.g.x")
            val ends = c in SENTENCE_END && (c != '.' || i + 1 >= text.length || text[i + 1] == ' ')
            if (ends && sb.length >= CHUNK_CHARS) { res += sb.toString(); sb.setLength(0) }
        }
        if (sb.isNotBlank()) {
            if (res.isNotEmpty() && sb.length < CHUNK_CHARS / 3) res[res.lastIndex] = res.last() + sb else res += sb.toString()
        }
        return res.filter { it.isNotBlank() }
    }
}
