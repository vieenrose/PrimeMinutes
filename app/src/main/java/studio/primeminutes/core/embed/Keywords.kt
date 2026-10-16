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

    /** Lower case and the [table] script (OpenCcConverter.simplifiedChars), one character at a time. */
    fun fold(text: String, table: CharArray): String {
        val out = CharArray(text.length)
        for (i in text.indices) out[i] = table[text[i].lowercaseChar().code]
        return String(out)
    }

    /** A term as an Int: a CJK bigram packs its two characters (exact), anything else is hashed. */
    fun code(term: String): Int =
        if (term.length == 2 && cjk(term[0]) && cjk(term[1])) (term[0].code shl 16) or term[1].code else term.hashCode()

    /** A passage reduced to what BM25 needs: its term count and its term codes, sorted. */
    class Doc(val length: Int, val codes: IntArray) {
        fun tf(code: Int): Int {
            var i = codes.binarySearch(code)
            if (i < 0) return 0
            while (i > 0 && codes[i - 1] == code) i--
            var n = 0
            while (i + n < codes.size && codes[i + n] == code) n++
            return n
        }
    }

    fun doc(text: String): Doc = terms(text).let { ts -> Doc(ts.size, IntArray(ts.size) { code(ts[it]) }.also { it.sort() }) }

    /** BM25 of each document for [query]; 0 for a document sharing no term with it. */
    fun bm25(query: String, docs: List<String>): FloatArray = score(query, docs.map(::doc))

    fun score(query: String, docs: List<Doc>): FloatArray {
        val q = terms(query).map(::code).distinct()
        if (q.isEmpty() || docs.isEmpty()) return FloatArray(docs.size)
        val tf = Array(docs.size) { d -> IntArray(q.size) { docs[d].tf(q[it]) } }
        val avg = maxOf(docs.sumOf { it.length }.toDouble() / docs.size, 1.0)
        val df = IntArray(q.size) { t -> tf.count { it[t] > 0 } }
        val n = docs.size.toDouble()
        return FloatArray(docs.size) { d ->
            var s = 0.0
            for (t in q.indices) {
                val f = tf[d][t].toDouble()
                if (f == 0.0) continue
                val idf = ln(1 + (n - df[t] + 0.5) / (df[t] + 0.5))
                s += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * docs[d].length / avg))
            }
            s.toFloat()
        }
    }
}
