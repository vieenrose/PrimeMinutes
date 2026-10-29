package studio.primeminutes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.export.TranscriptExport
import studio.primeminutes.core.reader.ReaderProtocol

/** The PDF transcript's turns and clock gutter. Pure JVM. */
class TranscriptExportTest {

    private fun u(i: Int, text: String, start: Double, end: Double, spk: Int? = null) =
        TranscriptEvent.Utterance(index = i, text = text, startSec = start, endSec = end, speaker = spk)

    @Test fun turnsGroupSameSpeakerAndDropBlanks() {
        val t = TranscriptExport.turns(listOf(
            u(0, "Hello there", 1.0, 2.5, 0),
            u(1, "   ", 2.5, 3.0, 1),
            u(2, "我們開始吧", 65.2, 70.0, 1),
            u(3, "first line\nsecond line", 67.0, 80.0, 1),
            u(4, "OK", 81.0, 83.0, 0),
        ))
        assertEquals(listOf("Hello there", "我們開始吧", "first line second line", "OK"), t.map { it.text })
        assertEquals(listOf(false, false, true, false), t.map { it.cont })
    }

    @Test fun unknownSpeakersNeverMerge() {
        val t = TranscriptExport.turns(listOf(u(0, "one", 1.0, 2.0), u(1, "two", 3.0, 4.0)))
        assertFalse(t.any { it.cont })
    }

    @Test fun hourClockIsZeroPaddedAcrossTheWholeDocument() {
        val long = listOf(u(0, "start", 5.0, 6.0, 0), u(1, "一小時後", 3723.0, 3725.0, 1))
        val w = TranscriptExport.gutterWidth(long)
        assertEquals(8, w)
        assertEquals("00:00:05", TranscriptExport.clock(5.0, w))
        assertEquals("01:02:03", TranscriptExport.clock(3723.0, w))
        assertEquals("01:05", TranscriptExport.clock(65.2))
        assertTrue(ReaderProtocol.parseLine("[01:02:03] Speaker 2: x") != null)
    }
}
