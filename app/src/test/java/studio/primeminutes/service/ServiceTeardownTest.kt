package studio.primeminutes.service

import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The service going away leaves no run behind in the process-wide registry. */
@RunWith(RobolectricTestRunner::class)
class ServiceTeardownTest {
    @Before fun setUp() = RunRegistry.resetForTest()
    @After fun tearDown() = RunRegistry.resetForTest()

    private fun importRunning(gen: Int) {
        RunRegistry.runStarting(recording = false, queue = false)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.IMPORT, gen, "content://x/a.mp3", 1L))
    }

    @Test fun aRunThatNeverUnwoundIsClearedWhenTheServiceIsDestroyed() {
        // A Stop whose drain timed out (the run deaf to cancellation, as the decoder parked in
        // trySendBlocking was before bf403f66) stops the service anyway; the run's teardown never comes.
        val c = Robolectric.buildService(TranscriptionService::class.java).create()
        TranscriptionService::class.java.getDeclaredField("pipelineJob").apply { isAccessible = true }.set(c.get(), Job())
        importRunning(gen = 5)
        assertTrue(5 in RunRegistry.jobs.value.activeSessionGens)
        c.destroy()
        // An open screen sees the run's gen leave the service's work and settles (runEnded).
        assertFalse(RunRegistry.pipelineActive)
        assertTrue(RunRegistry.jobs.value.activeSessionGens.isEmpty())
        assertFalse(RunRegistry.busy)
    }

    @Test fun aServiceWithNoRunLeftLeavesTheRegistryAlone() {
        val c = Robolectric.buildService(TranscriptionService::class.java).create()
        importRunning(gen = 6)   // what a newer start published (pipelineJob of this one is null)
        c.destroy()
        assertTrue(RunRegistry.pipelineActive)
    }
}
