package studio.primeminutes

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.primeminutes.core.events.TranscriptEvent.Utterance
import studio.primeminutes.data.BoundaryEdits

class BoundaryEditsTest {
    private fun u(i: Int, spk: Int, a: Double, b: Double, t: String) = Utterance(i, t, a, b, spk)

    @Test fun tokenizeIsLosslessForHanAndLatin() {
        for (t in listOf("好，我們今天討論預算。", "Okay, so the remote is ready.", "我用 iPhone 15 錄音，OK?", "  leading space")) {
            assertEquals(t, BoundaryEdits.tokenize(t).joinToString(""))
        }
        assertEquals(listOf("好，", "我", "們"), BoundaryEdits.tokenize("好，我們"))
        assertEquals(listOf("Okay, ", "so ", "the"), BoundaryEdits.tokenize("Okay, so the"))
    }

    @Test fun moveLateBoundaryEarlierEnglish() {
        // B's first two words were left at the end of A (the diarizer's usual late boundary).
        val utts = listOf(u(0, 0, 0.0, 4.0, "We decided on the budget. Yeah but"), u(1, 1, 4.0, 6.0, "what about the case?"))
        val out = BoundaryEdits.move(utts, 1, 5)
        assertEquals("We decided on the budget.", out[0].text)
        assertEquals("Yeah but what about the case?", out[1].text)
        assertEquals(out[0].endSec, out[1].startSec, 1e-9)
        assertEquals(0, out[0].speaker); assertEquals(1, out[1].speaker)
        // moving back restores the text
        val back = BoundaryEdits.move(out, 1, 7)
        assertEquals(utts[0].text, back[0].text); assertEquals(utts[1].text, back[1].text)
    }

    @Test fun moveHanCharacters() {
        val utts = listOf(u(0, 0, 10.0, 13.0, "預算就這樣定了對"), u(1, 1, 13.0, 15.0, "可是時間呢"))
        val out = BoundaryEdits.move(utts, 1, 7)
        assertEquals("預算就這樣定了", out[0].text); assertEquals("對可是時間呢", out[1].text)
        assertEquals(10.0 + 3.0 * 7 / 8, out[1].startSec, 1e-9)
    }

    @Test fun moveKeepsAtLeastOneTokenEachSide() {
        val utts = listOf(u(0, 0, 0.0, 1.0, "好的"), u(1, 1, 1.0, 2.0, "嗯"))
        val out = BoundaryEdits.move(utts, 1, 99)
        assertEquals("好的", out[0].text.take(2)); assertEquals(1, BoundaryEdits.tokenize(out[1].text).size)
    }

    @Test fun engineTokenTimesAreUsedInsteadOfTheEstimate() {
        // "預算定了對" + "可是": the engine says 對 starts at 12.9 s (the proportional guess would be 12.4 s).
        val a = Utterance(0, "預算定了對", 10.0, 13.0, 0, tokenTimes = listOf(0.0, 0.5, 1.0, 1.6, 2.9))
        val b = Utterance(1, "可是", 13.0, 14.0, 1, tokenTimes = listOf(0.0, 0.4))
        val out = BoundaryEdits.move(listOf(a, b), 1, 4)
        assertEquals("對可是", out[1].text)
        assertEquals(12.9, out[1].startSec, 1e-9); assertEquals(12.9, out[0].endSec, 1e-9)
        // the moved token keeps its time, the rest are re-based on the new start
        assertEquals(listOf(0.0, 0.1, 0.5), out[1].tokenTimes)
        assertEquals(listOf(0.0, 0.5, 1.0, 1.6), out[0].tokenTimes)
        // times that no longer match the text (an edit changed the token count) fall back to the estimate
        val stale = a.copy(text = "預算定了對嗎")
        assertEquals(6, BoundaryEdits.tokenStarts(stale).size)
        assertEquals(10.0 + 3.0 * 5 / 6, BoundaryEdits.tokenStarts(stale)[5], 1e-9)
    }

    @Test fun splitCandidatesNeighboursFirstThenOthersThenNew() {
        val utts = listOf(u(0, 3, 0.0, 1.0, "a"), u(1, 0, 1.0, 2.0, "b c"), u(2, 2, 2.0, 3.0, "d"), u(3, 1, 3.0, 4.0, "e"))
        assertEquals(listOf(2, 3, 1, 4), BoundaryEdits.splitCandidates(utts, 1))
        // the line's own speaker is never offered, even as a neighbour
        val same = listOf(u(0, 0, 0.0, 1.0, "a"), u(1, 0, 1.0, 2.0, "b c"))
        assertEquals(listOf(1), BoundaryEdits.splitCandidates(same, 1))
    }

    @Test fun splitMissedTurn() {
        val utts = listOf(u(0, 0, 0.0, 6.0, "I think so. Sure. Then the buttons"), u(1, 2, 6.0, 8.0, "next"))
        val out = BoundaryEdits.split(utts, 0, 3, 1)
        assertEquals(3, out.size)
        assertEquals("I think so.", out[0].text); assertEquals("Sure. Then the buttons", out[1].text)
        assertEquals(1, out[1].speaker); assertEquals(listOf(0, 1, 2), out.map { it.index })
    }
}
