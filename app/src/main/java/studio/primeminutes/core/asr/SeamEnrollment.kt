package studio.primeminutes.core.asr

import studio.primeminutes.core.events.TranscriptEvent.Utterance

/**
 * A resumed run's restarted diarizer only hears the [SeamStitcher.PREROLL_SEC] before the seam, so a
 * speaker silent there came back as a new speaker (iPhone, 45-min import killed at 1000 s: 10 speakers
 * instead of 8). Before the preroll the engine is now fed a few seconds of every saved speaker, in
 * order of first appearance (the diarizer numbers speakers by arrival): their re-recognised lines map
 * back onto the saved utterances they were cut from, so [SeamStitcher] matches every speaker by overlap
 * as it does in the preroll. Those lines lie before the seam and are dropped like the preroll's.
 *
 * Engine timeline: [clip, [GAP_SEC] of silence]… then the preroll from [offsetSec] onwards. The gap makes
 * the ASR end its line at each clip edge; a line that still runs past one is cut at that clip's end
 * ([toOriginal] of a range): mapped whole, a line spanning two clips 300 s apart overlapped minutes of
 * saved speech and outvoted the preroll (iPhone: the two main speakers swapped).
 */
class SeamEnrollment private constructor(
    /** Original (start, end) seconds of each clip, in feeding order. */
    val clips: List<Pair<Double, Double>>,
    private val offsetSec: Double,
) {
    /** Seconds of enrollment audio (clips and gaps) fed before the preroll. */
    val lengthSec: Double = clips.sumOf { it.second - it.first + GAP_SEC }

    /** Engine time (silence-skip already undone) → original audio time; a gap maps to its clip's end. */
    fun toOriginal(t: Double): Double = segment(t)?.let { (s, e, at) -> minOf(s + (t - at), e) } ?: (t - lengthSec + offsetSec)

    /**
     * An engine-time line → original times, cut at the end of the clip it starts in. The diarizer
     * opens a turn a little before the voice: a line starting in a gap belongs to what follows the
     * gap (the next clip, or the preroll), not to the clip before it (ec45 resumed at 1422 s: speaker
     * 3's whole clip collapsed to a zero-length line and the speaker came back as a new one).
     */
    fun toOriginal(start: Double, end: Double): Pair<Double, Double> {
        val seg = segment(start) ?: return toOriginal(start) to toOriginal(end)
        val (s, e, at) = seg
        if (start - at < e - s) {   // starts inside the clip
            val s0 = s + (start - at)
            return s0 to maxOf(s0, minOf(e, s + (end - at)))
        }
        val next = segment(end) ?: return offsetSec to maxOf(offsetSec, toOriginal(end))
        if (next == seg) return e to e   // silence only
        val (ns, ne, nat) = next
        return ns to minOf(ne, ns + (end - nat))
    }

    /** (clip start, clip end, engine time where it starts) of the clip or gap holding [t]; null past the clips. */
    private fun segment(t: Double): Triple<Double, Double, Double>? {
        if (t >= lengthSec) return null
        var at = 0.0
        for ((s, e) in clips) {
            if (t < at + (e - s) + GAP_SEC) return Triple(s, e, at)
            at += e - s + GAP_SEC
        }
        return null
    }

    companion object {
        const val PER_SPEAKER_SEC = 6.0
        const val GAP_SEC = 1.0
        const val MAX_TOTAL_SEC = 60.0
        private const val MIN_CLIP_SEC = 1.0

        val NONE = SeamEnrollment(emptyList(), 0.0)

        /** Clips taken from [prior] utterances that end before [offsetSec] (the preroll start). */
        fun plan(prior: List<Utterance>, offsetSec: Double): SeamEnrollment {
            if (prior.isEmpty() || offsetSec <= 0.0) return SeamEnrollment(emptyList(), offsetSec)
            val usable = prior.filter { it.speaker != null && it.endSec <= offsetSec && it.endSec - it.startSec >= MIN_CLIP_SEC }
            val order = prior.mapNotNull { it.speaker }.distinct()   // arrival order
            val clips = ArrayList<Pair<Double, Double>>()
            var total = 0.0
            for (spk in order) {
                var got = 0.0
                for (u in usable.filter { it.speaker == spk }.sortedByDescending { it.endSec - it.startSec }) {
                    if (got >= PER_SPEAKER_SEC || total >= MAX_TOTAL_SEC) break
                    val len = minOf(u.endSec - u.startSec, PER_SPEAKER_SEC - got, MAX_TOTAL_SEC - total)
                    if (len < MIN_CLIP_SEC) break
                    clips += u.startSec to u.startSec + len
                    got += len; total += len
                }
            }
            return SeamEnrollment(clips, offsetSec)
        }
    }
}
