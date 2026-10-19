package studio.primeminutes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The live notes card's first-reading estimate: the window fills at the transcript's pace so far. */
class FirstReadingTest {
    @Test fun tooEarlyToTell() {
        assertNull(FirstReading.etaMinutes(200, 1500, 30_000))
        assertNull(FirstReading.etaMinutes(0, 1500, 120_000))
    }

    @Test fun zhPace() {
        // ~3.3 tok/s (zh speech ≈ 4.4 chars/s): 200 tokens in 60 s, 1300 left ≈ 390 s → 7 minutes.
        assertEquals(7, FirstReading.etaMinutes(200, 1500, 60_000))
        assertEquals(1, FirstReading.etaMinutes(1300, 1500, 390_000))
    }

    @Test fun underAMinuteIsZero() {
        assertEquals(0, FirstReading.etaMinutes(1450, 1500, 400_000))
        assertEquals(0, FirstReading.etaMinutes(1600, 1500, 400_000))
    }
}
