package studio.primeminutes.core.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.reader.Note

class MeetingSearchTest {
    private val us = listOf(
        TranscriptEvent.Utterance(0, "Welcome everyone", 0.0, 4.0, 0),
        TranscriptEvent.Utterance(1, "The Budget for the movers", 245.0, 250.0, 1),
        TranscriptEvent.Utterance(2, "we cannot spend more than that", 580.0, 584.0, 0),
        TranscriptEvent.Utterance(3, "budget again, and the truck", 700.0, 706.0, 1),
    )
    private val notes = listOf(
        Note(1, 0, "4:12", "decision", "Movers budget capped at 80k"),
        Note(2, 1, "11:50", null, "Anna books the truck"),
    )

    @Test fun notesFirstThenLinesInMeetingOrderCaseInsensitive() {
        val rs = MeetingSearch.results("budget", us, notes)
        assertEquals(listOf(MeetingSearch.Kind.NOTE, MeetingSearch.Kind.LINE, MeetingSearch.Kind.LINE), rs.map { it.kind })
        assertEquals(listOf(1, 1, 3), rs.map { it.line })   // the 4:12 note falls in the 4:05 line
        assertEquals(252.0, rs[0].timeSec, 0.0)
        assertEquals(1, rs[1].speaker)
    }

    @Test fun blankQueryHasNoResults() = assertTrue(MeetingSearch.results("  ", us, notes).isEmpty())

    @Test fun denseHitsJoinOnceMarkedSemanticOnly() {
        val dense = listOf(
            LibrarySearch.Passage(SessionIndex.Kind.CHUNK, 2, 2, 580.0, null, "we cannot spend", true, 0.6f),
            LibrarySearch.Passage(SessionIndex.Kind.CHUNK, 1, 1, 245.0, null, "The Budget", true, 0.7f),   // already a keyword hit
            LibrarySearch.Passage(SessionIndex.Kind.NOTE, 1, 1, 710.0, null, "Anna books the truck", true, 0.5f),
            LibrarySearch.Passage(SessionIndex.Kind.MEETING, -1, -1, 0.0, null, "title", true, 0.9f),
        )
        val rs = MeetingSearch.results("budget", us, notes, dense)
        assertEquals(listOf(false, true, false, true, false), rs.map { it.semanticOnly })
        assertEquals(listOf(1, 3, 1, 2, 3), rs.map { it.line })
        assertEquals(listOf(1, 3), MeetingSearch.stepLines(rs, semantic = false))
        assertEquals(listOf(1, 2, 3), MeetingSearch.stepLines(rs, semantic = true))
    }

    @Test fun lineAtPicksTheLineATimeFallsIn() {
        assertEquals(0, MeetingSearch.lineAt(us, 3.0))
        assertEquals(2, MeetingSearch.lineAt(us, 600.0))
        assertEquals(0, MeetingSearch.lineAt(us, -5.0))
        assertEquals(-1, MeetingSearch.lineAt(emptyList(), 5.0))
    }

    @Test fun resultsAreCapped() {
        val many = (0 until 80).map { TranscriptEvent.Utterance(it, "budget $it", it * 10.0, it * 10.0 + 5) }
        assertEquals(MeetingSearch.MAX_RESULTS, MeetingSearch.results("budget", many, emptyList()).size)
    }

    @Test fun approxTokensCountsHanCharactersAndWords() {
        assertEquals(4, SessionIndex.approxTokens("搬遷預算"))
        assertEquals(4, SessionIndex.approxTokens("the movers budget"))   // 3 words × 4/3
        assertEquals(9, SessionIndex.approxTokens("預算 80k 上限是多少"))   // 7 Han + one word (2)
    }
}
