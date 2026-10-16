package studio.primeminutes.core.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ask shows its stage and the seconds since the question. */
class AskProgressTest {
    @Test fun elapsedSeconds() {
        assertEquals(0, AskEngine.elapsedSec(0L, 50_000L))         // never started
        assertEquals(12, AskEngine.elapsedSec(10_000L, 22_900L))
        assertEquals(0, AskEngine.elapsedSec(10_000L, 9_000L))      // clock never goes back on screen
    }

    @Test fun workingStages() {
        assertTrue(AskEngine.Phase.Retrieving.working)
        assertTrue(AskEngine.Phase.Reading(6).working)
        assertTrue(AskEngine.Phase.Answering.working)
        assertFalse(AskEngine.Phase.Idle.working)
        assertFalse(AskEngine.Phase.Busy.working)
        assertFalse(AskEngine.Phase.Failed("x").working)
    }
}
