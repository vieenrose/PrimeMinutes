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
        var have = 0.0
        while (have < cap && thirds.any { it.isNotEmpty() }) {
            for (q in thirds) {
                if (have >= cap) break
                val s = q.removeFirstOrNull() ?: continue
                val take = minOf(s.sec, cap - have)
                out += Span(s.start, s.start + take); have += take
            }
        }
        return out.sortedBy { it.start }
    }

    fun turns(utts: List<TranscriptEvent.Utterance>): List<SpeakerCarryOver.Turn> =
        utts.map { SpeakerCarryOver.Turn(it.startSec, it.endSec, it.speaker) }

    /** Seconds of clean speech [speaker] has (capped), for the "not enough speech" note. */
    fun seconds(utts: List<TranscriptEvent.Utterance>, speaker: Int): Double = spans(turns(utts), speaker).sumOf { it.sec }

    /** Where to play a short clip of [speaker]: its longest clean line, else its first line. */
    fun sampleStart(utts: List<TranscriptEvent.Utterance>, speaker: Int): Double? =
        spans(turns(utts), speaker, cap = Double.MAX_VALUE).maxByOrNull { it.sec }?.start
            ?: utts.firstOrNull { it.speaker == speaker }?.startSec
}
