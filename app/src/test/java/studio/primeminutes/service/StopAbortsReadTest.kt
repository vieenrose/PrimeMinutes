package studio.primeminutes.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which readings a Stop cuts short. */
class StopAbortsReadTest {
    @Test fun aSessionsOwnReadingAlwaysStops() {
        assertTrue(stopAbortsRead(7, hasEntry = false, queueStop = false))
        assertTrue(stopAbortsRead(7, hasEntry = false, queueStop = true))
    }

    @Test fun aQueueItemStopsOnlyWhenTheDrainIsStopped() {
        assertTrue(stopAbortsRead(TranscriptionService.QUEUE_GEN, hasEntry = true, queueStop = true))
        assertFalse("a Stop elsewhere leaves the queue's reading alone",
            stopAbortsRead(TranscriptionService.QUEUE_GEN, hasEntry = true, queueStop = false))
    }

    @Test fun aFinishedRecordingKeepsBeingRead() {
        assertFalse(stopAbortsRead(4, hasEntry = true, queueStop = true))
        assertFalse(stopAbortsRead(4, hasEntry = true, queueStop = false))
    }
}
