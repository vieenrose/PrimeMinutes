package studio.primeminutes

import org.junit.Assert.assertEquals
import studio.primeminutes.core.asr.SeamEnrollment
import studio.primeminutes.core.asr.SeamStitcher
import studio.primeminutes.core.events.TranscriptEvent.Utterance
import org.junit.Test

class SeamStitcherTest {
    private fun u(i: Int, a: Double, b: Double, s: Int) = Utterance(i, "t$i", a, b, s)

    @Test fun speakersAreMatchedThroughThePreroll() {
        val prior = listOf(u(0, 40.0, 70.0, 0), u(1, 70.0, 90.0, 1))
        // Resumed run: ids swapped (its speaker 0 is the old 1), preroll covers 60–90, then new audio.
        val fresh = listOf(u(0, 60.0, 70.0, 1), u(1, 70.0, 90.0, 0), u(2, 90.0, 100.0, 0), u(3, 100.0, 110.0, 1))
        val (all, stable) = SeamStitcher.stitch(prior, 90.0, fresh, freshStable = 3)
        assertEquals(listOf(0, 1, 2, 3), all.map { it.index })
        assertEquals(listOf(0, 1, 1, 0), all.map { it.speaker!! })
        assertEquals(3, stable)   // prior 2 + the one stable fresh utterance after the seam
    }

    @Test fun aSpeakerUnseenBeforeGetsAFreshId() {
        val prior = listOf(u(0, 0.0, 90.0, 0))
        val fresh = listOf(u(0, 60.0, 90.0, 0), u(1, 90.0, 100.0, 1))
        val (all, _) = SeamStitcher.stitch(prior, 90.0, fresh, 2)
        assertEquals(listOf(0, 1), all.map { it.speaker!! })
    }

    @Test fun noPriorIsAPassThrough() {
        val fresh = listOf(u(0, 0.0, 5.0, 0))
        assertEquals(fresh to 1, SeamStitcher.stitch(emptyList(), 0.0, fresh, 1))
    }

    @Test fun aSpeakerSilentInThePrerollKeepsItsIdThroughEnrollment() {
        // Speaker 1 spoke only at 10–20 s; the preroll (60–90) holds speaker 0 alone.
        val prior = listOf(u(0, 0.0, 10.0, 0), u(1, 10.0, 20.0, 1), u(2, 20.0, 90.0, 0))
        val en = SeamEnrollment.plan(prior, offsetSec = 60.0)
        // Arrival order: speaker 0's clip first (its longest line ends after 60 s, so the 0–10 one), then speaker 1's.
        assertEquals(listOf(0.0 to 6.0, 10.0 to 16.0), en.clips)
        assertEquals(14.0, en.lengthSec, 1e-9)
        // Engine timeline: clip 0 = 0–6, gap 6–7, clip 1 = 7–13, gap 13–14, preroll from 14 (= 60 s original).
        assertEquals(13.0, en.toOriginal(10.0), 1e-9)
        assertEquals(6.0, en.toOriginal(6.5), 1e-9)   // a gap maps to its clip's end
        assertEquals(70.0, en.toOriginal(24.0), 1e-9)
        // A line running from clip 0 into clip 1 is cut at clip 0's end, not mapped across 0–16 s.
        assertEquals(2.0 to 6.0, en.toOriginal(2.0, 9.0))
        assertEquals(10.0 to 12.0, en.toOriginal(6.5, 9.0))   // a turn opened early in the gap belongs to clip 1
        assertEquals(6.0 to 6.0, en.toOriginal(6.2, 6.8))     // silence only
        assertEquals(60.0 to 62.0, en.toOriginal(13.5, 16.0)) // early start in the last gap → the preroll
        // The restarted diarizer numbers by arrival: 0 for speaker 0's clip, 1 for speaker 1's clip.
        val raw = listOf(Triple(0.0, 6.0, 0), Triple(7.0, 13.0, 1), Triple(14.0, 44.0, 0), Triple(44.0, 52.0, 1))
        val fresh = raw.mapIndexed { i, (a, b, sp) -> en.toOriginal(a, b - 1e-6).let { (x, y) -> u(i, x, y, sp) } }
        val (all, _) = SeamStitcher.stitch(prior, 90.0, fresh, fresh.size)
        assertEquals(listOf(0, 1, 0, 1), all.map { it.speaker!! })   // the line after the seam is speaker 1, not a new 2
    }

    @Test fun noEnrollmentWithoutAPrerollGap() {
        assertEquals(0, SeamEnrollment.plan(listOf(u(0, 0.0, 20.0, 0)), offsetSec = 0.0).clips.size)
    }

    @Test fun theWordsRepeatedAcrossTheSeamAreDroppedOnce() {
        // Seen on the emulator (move meeting, killed at 268 s): the prior line ends "…很好，客戶" and the
        // resumed run's straddling line restarts with it.
        val prior = listOf(Utterance(0, "三月十號當天早上生效。很好，客戶", 262.0, 268.0, 0))
        val fresh = listOf(Utterance(0, "三月十號當天早上生效。", 262.0, 266.5, 0),
            Utterance(1, "很好，客戶通知信，我希望二月底前寄出", 266.5, 275.0, 1))
        val (all, _) = SeamStitcher.stitch(prior, 268.0, fresh, 2)
        assertEquals(listOf("三月十號當天早上生效。很好，客戶", "通知信，我希望二月底前寄出"), all.map { it.text })
    }

    @Test fun repeatedHeadTrimKeepsTokenTimings() {
        val f = Utterance(0, "see you on Thursday", 10.0, 12.0, 0,
            tokens = listOf("see", " you", " on", " Thursday"), tokenTimes = listOf(0.0, 0.3, 0.6, 0.9))
        val g = SeamStitcher.trimRepeatedHead("we'll see you", f)
        assertEquals("on Thursday", g.text)
        assertEquals(10.6, g.startSec, 1e-9)
        assertEquals(listOf(0.0, 0.3), g.tokenTimes!!.map { Math.round(it * 10) / 10.0 })
    }

    @Test fun aPartialLatinWordIsNotARepeat() {
        val f = Utterance(0, "on the table", 1.0, 2.0, 0)
        assertEquals(f, SeamStitcher.trimRepeatedHead("put it upon", f))
    }

    @Test fun noRepeatLeavesTheUtteranceAlone() {
        val f = Utterance(0, "通知信", 1.0, 2.0, 0)
        assertEquals(f, SeamStitcher.trimRepeatedHead("生效。", f))
    }
}
