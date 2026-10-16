package studio.primeminutes.core.embed

import kotlin.math.ln

/**
 * Keyword scoring for Ask's lexical pass: BM25 over terms that work without word segmentation —
 * character bigrams inside runs of CJK characters (a lone character stands for itself), and words
 * or numbers elsewhere. The caller normalizes case and script first (both sides the same way).
 * Names, amounts and rare terms the embedder blurs are what this pass adds to the dense one.
 */
object Keywords {
    private const val K1 = 1.2
    private const val B = 0.75

    /** Han (unified, extension A, compatibility), kana and Hangul — the same ranges as iOS Keywords. */
    private fun cjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF' ||
        c in '\u3040'..'\u30FF' || c in '\uAC00'..'\uD7AF' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F'

    fun terms(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                cjk(c) -> {
                    var j = i
                    while (j < text.length && cjk(text[j])) j++
                    if (j - i == 1) out += c.toString() else for (k in i until j - 1) out += text.substring(k, k + 2)
                    i = j
                }
                c.isLetterOrDigit() -> {
                    var j = i
                    while (j < text.length && text[j].isLetterOrDigit() && !cjk(text[j])) j++
                    out += text.substring(i, j)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    /** BM25 of each document for [query]; 0 for a document sharing no term with it. */
    fun bm25(query: String, docs: List<String>): FloatArray {
        val q = terms(query).toSet()
        if (q.isEmpty() || docs.isEmpty()) return FloatArray(docs.size)
        val tf = docs.map { d -> terms(d).let { ts -> Pair(ts.size, ts.filter { it in q }.groupingBy { it }.eachCount()) } }
        val avg = tf.sumOf { it.first }.toDouble() / docs.size
        val df = HashMap<String, Int>()
        tf.forEach { (_, c) -> c.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size.toDouble()
        return FloatArray(docs.size) { i ->
            val (len, counts) = tf[i]
            counts.entries.sumOf { (t, f) ->
                val idf = ln(1 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                idf * f * (K1 + 1) / (f + K1 * (1 - B + B * len / maxOf(avg, 1.0)))
            }.toFloat()
        }
    }
}
