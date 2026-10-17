package studio.primeminutes.data

import studio.primeminutes.core.asr.TokenTimes
import studio.primeminutes.core.events.TranscriptEvent

/**
 * Pure ops for correcting WHERE one speaker's turn ends and the next one's begins (the diarizer's
 * boundary is often a word or two off, mostly late at overlapped speech: docs/design/speaker-boundary.md).
 *
 * Unit of correction = a token: one Han character, or one Latin word, with the punctuation and spaces
 * that follow it attached (so tokens concatenate back to the exact text). Times: the utterance's own
 * per-token times (the engine's, carried over JNI) when they line up with this tokenization, else spread
 * over [startSec, endSec] by character count. The edited pair gets per-token times written back
 * ([TranscriptEvent.Utterance.tokenTimes], relative to startSec) so the next nudge starts from the same times.
 */
object BoundaryEdits {

    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /** Lossless tokenization: joinToString("") == text ([TokenTimes.tokenize]). */
    fun tokenize(text: String): List<String> = TokenTimes.tokenize(text)

    /** Absolute start time of every token of [u] (seconds). */
    fun tokenStarts(u: TranscriptEvent.Utterance, toks: List<String> = tokenize(u.text)): List<Double> {
        // The engine's times (or the ones an earlier edit wrote back) when they still describe this text's tokens.
        val tt = u.tokenTimes
        if (tt != null && tt.size == toks.size && (u.tokens == null || u.tokens.size == toks.size)) return tt.map { u.startSec + it }
        val weights = toks.map { t -> t.count { !it.isWhitespace() }.coerceAtLeast(1).toDouble() }
        val total = weights.sum().coerceAtLeast(1.0)
        val span = (u.endSec - u.startSec).coerceAtLeast(0.0)
        var acc = 0.0
        return weights.map { w -> (u.startSec + span * acc / total).also { acc += w } }
    }

    /** The two sides of a seam, as one token strip: tokens, their start times, and where B begins. */
    data class Seam(val tokens: List<String>, val starts: List<Double>, val split: Int, val end: Double)

    fun seam(a: TranscriptEvent.Utterance, b: TranscriptEvent.Utterance): Seam {
        val ta = tokenize(a.text); val tb = tokenize(b.text)
        return Seam(ta + tb, tokenStarts(a, ta) + tokenStarts(b, tb), ta.size, b.endSec)
    }

    /** Concatenate tokens, adding the space two Latin words need when they meet across the seam. */
    internal fun join(toks: List<String>): String {
        val sb = StringBuilder()
        for (t in toks) {
            if (sb.isNotEmpty() && t.isNotEmpty() && !sb.last().isWhitespace() && !isHan(sb.last()) && !isHan(t.first())
                && t.first().isLetterOrDigit() && sb.last() != '-') sb.append(' ')
            sb.append(t)
        }
        return sb.toString().trim()
    }

    /**
     * Move the boundary between utterances [seam]-1 (A) and [seam] (B) to token [split] of the combined
     * strip (0 < split < tokens): tokens before it go to A, the rest to B. Speakers, indices and the other
     * lines are untouched. A.end and B.start both become the (estimated) time of B's first token.
     */
    fun move(utts: List<TranscriptEvent.Utterance>, seam: Int, split: Int): List<TranscriptEvent.Utterance> {
        if (seam !in 1 until utts.size) return utts
        val a = utts[seam - 1]; val b = utts[seam]
        val s = seam(a, b)
        val k = split.coerceIn(1, s.tokens.size - 1)
        if (k == s.split) return utts
        val t = s.starts[k]
        val na = withTimes(a.copy(text = join(s.tokens.subList(0, k)), endSec = t), s.starts.subList(0, k))
        val nb = withTimes(b.copy(text = join(s.tokens.subList(k, s.tokens.size)), startSec = t), s.starts.subList(k, s.tokens.size))
        return utts.toMutableList().also { it[seam - 1] = na; it[seam] = nb }
    }

    /** Store per-token times (relative to startSec) when the text re-tokenizes to the same count. */
    private fun withTimes(u: TranscriptEvent.Utterance, starts: List<Double>): TranscriptEvent.Utterance {
        val toks = tokenize(u.text)
        return if (toks.size == starts.size) u.copy(tokens = null, tokenTimes = TokenTimes.relative(starts, u.startSec))
        else u.copy(tokens = null, tokenTimes = null)
    }

    /**
     * Who may say the rest of line [index] when it is split, likeliest first: the speakers of the
     * neighbouring lines (next, then previous), then every other speaker of the meeting, then a new
     * speaker id (one past the highest). The line's own speaker is never offered.
     */
    fun splitCandidates(utts: List<TranscriptEvent.Utterance>, index: Int): List<Int> {
        val own = utts.getOrNull(index)?.speaker
        val ids = utts.mapNotNull { it.speaker }.distinct().sorted()
        val near = listOfNotNull(utts.getOrNull(index + 1)?.speaker, utts.getOrNull(index - 1)?.speaker)
        return ((near + ids).filter { it != own }.distinct()) + ((ids.maxOrNull() ?: -1) + 1)
    }

    /**
     * Split utterance [index] at token [at] (0 < at < tokens): the tail becomes a new line of [speaker]
     * (the "missed turn" case — a short reply swallowed by its neighbour). Indices are renumbered.
     */
    fun split(utts: List<TranscriptEvent.Utterance>, index: Int, at: Int, speaker: Int): List<TranscriptEvent.Utterance> {
        if (index !in utts.indices) return utts
        val u = utts[index]
        val toks = tokenize(u.text)
        if (at !in 1 until toks.size) return utts
        val starts = tokenStarts(u, toks)
        val t = starts[at]
        val head = withTimes(u.copy(text = join(toks.subList(0, at)), endSec = t), starts.subList(0, at))
        val tail = withTimes(u.copy(text = join(toks.subList(at, toks.size)), startSec = t, speaker = speaker), starts.subList(at, toks.size))
        val out = utts.toMutableList().apply { set(index, head); add(index + 1, tail) }
        return out.mapIndexed { i, x -> if (x.index == i) x else x.copy(index = i) }
    }
}
