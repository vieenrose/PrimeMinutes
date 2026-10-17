package studio.primeminutes.core.people

import studio.primeminutes.core.events.TranscriptEvent

/**
 * Which audio of a speaker a voice vector is computed from (docs/design/speaker-tags.md §4.3): its
 * lines that overlap no other speaker's line, trimmed [TRIM] s at each end and at least [MIN_SEG] s
 * long, taken round-robin over the meeting's thirds (longest first in each third, so a voice that
 * drifts during the meeting is sampled everywhere), up to [CAP] s. Under [FLOOR] s: no vector.
 */
object CleanSpeech {
    const val MIN_SEG = 1.5
    const val TRIM = 0.1
    const val CAP = 60.0
    const val FLOOR = 10.0

    data class Span(val start: Double, val end: Double) { val sec: Double get() = end - start }

    fun spans(turns: List<SpeakerCarryOver.Turn>, speaker: Int, cap: Double = CAP): List<Span> {
        val out = ArrayList<Span>()
        var have = 0.0
        for (s in ordered(turns, speaker)) {
            if (have >= cap) break
            val take = minOf(s.sec, cap - have)
            out += Span(s.start, s.start + take); have += take
        }
        return out.sortedBy { it.start }
    }

    /**
     * Every clean line of [speaker] in the order a voice vector consumes them: round-robin over the
     * meeting's thirds, longest first in each third. The voice lane reads them in this order through
     * [VoiceGate] until it has [CAP] s of actual voice (a line can hold long silences).
     */
    fun ordered(turns: List<SpeakerCarryOver.Turn>, speaker: Int): List<Span> {
        val sorted = turns.filter { it.end > it.start }.sortedBy { it.start }
        val others = sorted.filter { it.speaker != speaker }
        val clean = ArrayList<Span>()
        var j = 0
        for (t in sorted) {
            if (t.speaker != speaker) continue
            while (j < others.size && others[j].end <= t.start) j++
            var k = j; var overlaps = false
            while (k < others.size && others[k].start < t.end) { if (others[k].end > t.start) { overlaps = true; break }; k++ }
            if (overlaps) continue
            val s = Span(t.start + TRIM, t.end - TRIM)
            if (s.sec >= MIN_SEG) clean += s
        }
        if (clean.isEmpty()) return emptyList()
        val total = sorted.maxOf { it.end }.coerceAtLeast(1e-6)
        val thirds = (0 until 3).map { th -> clean.filter { ((it.start / total) * 3).toInt().coerceIn(0, 2) == th }.sortedByDescending { it.sec }.toMutableList() }
        val out = ArrayList<Span>()
        while (thirds.any { it.isNotEmpty() }) for (q in thirds) q.removeFirstOrNull()?.let { out += it }
        return out
    }

    fun turns(utts: List<TranscriptEvent.Utterance>): List<SpeakerCarryOver.Turn> =
        utts.map { SpeakerCarryOver.Turn(it.startSec, it.endSec, it.speaker) }

    /** A line with less text per second than this share of the meeting's median is mostly pause. */
    const val SPARSE = 0.4
    private const val NOBODY = -9

    /**
     * The turns a voice vector may use: a line far sparser in text than the meeting's usual (a long
     * pause around a whiteboard, a slide change, room noise) keeps its time (it still marks overlap)
     * but belongs to nobody. Relative to the meeting, so the same rule holds in every language.
     */
    fun voiceTurns(utts: List<TranscriptEvent.Utterance>): List<SpeakerCarryOver.Turn> {
        fun rate(u: TranscriptEvent.Utterance) = u.text.count { !it.isWhitespace() } / maxOf(0.5, u.endSec - u.startSec)
        val rates = utts.filter { it.endSec > it.startSec }.map(::rate).sorted()
        if (rates.isEmpty()) return turns(utts)
        val median = rates[rates.size / 2]
        return utts.map { u -> SpeakerCarryOver.Turn(u.startSec, u.endSec, if (rate(u) >= SPARSE * median) u.speaker else NOBODY) }
    }

    /** Seconds of clean speech [speaker] has (capped), for the "not enough speech" note. */
    fun seconds(utts: List<TranscriptEvent.Utterance>, speaker: Int): Double = spans(turns(utts), speaker).sumOf { it.sec }

    /** Where to play a short clip of [speaker]: its longest clean line, else its first line. */
    fun sampleStart(utts: List<TranscriptEvent.Utterance>, speaker: Int): Double? =
        spans(turns(utts), speaker, cap = Double.MAX_VALUE).maxByOrNull { it.sec }?.start
            ?: utts.firstOrNull { it.speaker == speaker }?.startSec
}

/**
 * Energy gate inside a speaker's lines: a transcript line can span long pauses (a whiteboard, a
 * slide change) whose room noise would dominate the voice vector. Keeps 20 ms frames louder than both
 * the line's loud frames minus [REL] dB and its quiet frames plus [ABOVE_FLOOR] dB (and an absolute
 * floor), bridges gaps under [GAP] s and drops runs under [MIN_RUN] s.
 */
object VoiceGate {
    const val FRAME = 0.02
    const val REL = 30.0
    const val ABOVE_FLOOR = 6.0
    const val FLOOR_DB = -55.0
    const val GAP = 0.2
    const val MIN_RUN = 0.3

    /** Voiced sample ranges [start, end) of [pcm] ([sr] Hz). */
    fun runs(pcm: FloatArray, sr: Int = 16_000): List<Pair<Int, Int>> {
        val n = (sr * FRAME).toInt()
        val k = pcm.size / n
        if (k == 0) return emptyList()
        val e = DoubleArray(k) { f ->
            var s = 0.0
            for (i in f * n until (f + 1) * n) s += pcm[i].toDouble() * pcm[i]
            10 * kotlin.math.log10(s / n + 1e-10)
        }
        val sorted = e.sorted()
        fun pct(p: Double) = sorted[((p / 100.0) * (k - 1)).toInt().coerceIn(0, k - 1)]
        val thr = maxOf(pct(95.0) - REL, FLOOR_DB, pct(10.0) + ABOVE_FLOOR)
        val merged = ArrayList<IntArray>()
        var i = 0
        while (i < k) {
            if (e[i] > thr) {
                var j = i
                while (j < k && e[j] > thr) j++
                val last = merged.lastOrNull()
                if (last != null && (i - last[1]) * FRAME < GAP) last[1] = j else merged += intArrayOf(i, j)
                i = j
            } else i++
        }
        return merged.filter { (it[1] - it[0]) * FRAME >= MIN_RUN }.map { it[0] * n to it[1] * n }
    }
}
