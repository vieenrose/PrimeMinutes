package studio.primeminutes.core.cite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CiteWindowTest {
    private val starts = listOf(0.0, 4.5, 10.2, 15.0, 21.7, 30.0, 42.9, 50.0)

    @Test fun targetIsLastTurnStartingAtOrBefore() {
        assertEquals(0, CiteWindow.target(3, starts))
        assertEquals(2, CiteWindow.target(10, starts))   // 10.2 truncates to 10, as in the export
        assertEquals(3, CiteWindow.target(15, starts))
        assertEquals(7, CiteWindow.target(999, starts))
    }

    @Test fun beforeFirstTurnFallsOnTheFirst() = assertEquals(0, CiteWindow.target(1, listOf(2.0, 5.0)))

    @Test fun noTurnsNoTarget() = assertNull(CiteWindow.target(5, emptyList()))

    @Test fun windowHasOneBeforeAndThreeAfter() = assertEquals(2..6, CiteWindow.around(3, 8))

    @Test fun windowClampsAtTheStart() = assertEquals(0..4, CiteWindow.around(0, 8))

    @Test fun windowClampsAtTheEnd() {
        assertEquals(3..7, CiteWindow.around(7, 8))
        assertEquals(3..7, CiteWindow.around(6, 8))
    }

    @Test fun shortTranscriptShowsEverything() {
        assertEquals(0..2, CiteWindow.around(1, 3))
        assertEquals(0..0, CiteWindow.around(0, 1))
    }

    @Test fun emptyTranscriptEmptyWindow() = assertTrue(CiteWindow.around(0, 0).isEmpty())

    @Test fun followKeepsTheWindowWhilePlaybackIsInside() = assertEquals(2..6, CiteWindow.follow(2..6, 5, 8))

    @Test fun followOpensOnThePlayingTurnOnceItLeaves() = assertEquals(5..9, CiteWindow.follow(0..4, 5, 12))

    @Test fun followClampsAtTheEnd() = assertEquals(3..7, CiteWindow.follow(0..4, 6, 8))

    @Test fun followIgnoresNoPlayingTurn() = assertEquals(2..6, CiteWindow.follow(2..6, -1, 8))
}
