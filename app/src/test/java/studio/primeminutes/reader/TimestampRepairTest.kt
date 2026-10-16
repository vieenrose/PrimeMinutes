package studio.primeminutes.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.primeminutes.core.reader.ReaderLane
import studio.primeminutes.core.reader.ReaderProtocol

/** Timestamp tags in the prose are checked against the notes' times (quality eval 2026-10: E2B wrote
 *  `[5:6]`, `[8:03, 11:04, 7]`, `[21]`). Mirrored by ios/tests GuardAndPlanTests. */
class TimestampRepairTest {
    private val known = listOf("3:41", "6:34", "8:03", "11:04", "1:02:15")
    private fun fix(s: String) = ReaderProtocol.repairTimestamps(s, known)

    @Test fun knownTagsStay() = assertEquals("a [3:41] b [1:02:15]", fix("a [3:41] b [1:02:15]"))

    @Test fun nearMissSnapsOntoTheNote() {
        assertEquals("[6:34]", fix("[6:40]"))
        assertEquals("[11:04]", fix("[10:50]"))
    }

    @Test fun farTagIsDropped() = assertEquals("x  y", fix("x [20:00] y"))

    @Test fun cutOffSecondsAreReadAsTheirTens() {
        // "6:3" is 6:03 or a cut-off 6:3x: the note at 6:34 is in that range.
        assertEquals("[6:34]", fix("[6:3]"))
        assertEquals("", fix("[5:6]"))   // 5:06 only (5:6x is no time): 88 s from the nearest note
        assertEquals("", fix("[7:8]"))   // 7:08 only (7:8x is no time): no note within 30 s
    }

    @Test fun listsAreSplitAndBareNumbersDropped() {
        assertEquals("[8:03] [11:04]", fix("[8:03, 11:04, 7]"))
        assertEquals("", fix("[21]"))
        assertEquals("[3:41] [6:34]", fix("[3:41-6:34]"))
        assertEquals("[8:03]", fix("[8:03，8:05]"))   // both snap onto the same note: once
    }

    @Test fun fullWidthColonAndHours() {
        assertEquals("[8:03]", fix("[8：03]"))
        assertEquals("[1:02:15]", fix("[1:02:20]"))
        assertEquals("", fix("[1:62:15]"))
    }

    @Test fun otherBracketsAreLeftAlone() {
        assertEquals("[S1] [note] [[2]] [a1]", fix("[S1] [note] [[2]] [a1]"))
    }

    @Test fun keepsTheNotesOwnSpelling() =
        assertEquals("[05:03]", ReaderProtocol.repairTimestamps("[5:03]", listOf("05:03")))

    @Test fun cleanProseRepairsAndTidies() {
        val raw = "決定採用紅外線 [5:6]。預算超支 [21]，下次再議 [8:03, 11:04, 7]，範圍約五米 [6:3]。<turn|>"
        assertEquals("決定採用紅外線。預算超支，下次再議 [8:03] [11:04]，範圍約五米 [6:34]。", ReaderLane.cleanProse(raw, known.toSet()))
    }
}
