package studio.primeminutes.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.primeminutes.R

/** "This is me": every phase of the dialog says something of its own (none falls back to another's text). */
class MeEnrolMessageTest {
    @Test fun eachPhaseHasItsOwnLine() {
        val phases = listOf(0, 1, 2, 3, -1, -2, -3)
        assertEquals(phases.size, phases.map(::meEnrolMessage).toSet().size)
        assertEquals(R.string.voice_me_next, meEnrolMessage(0))
        assertEquals(R.string.voice_me_need_model, meEnrolMessage(-3))
        assertEquals(R.string.voice_me_permission, meEnrolMessage(-2))
    }
}
