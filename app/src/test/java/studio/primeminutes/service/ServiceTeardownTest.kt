package studio.primeminutes.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Why the pipeline job's teardown sits in a `finally`: a hop to another dispatcher, NonCancellable
     * inside, still throws on the way back into a cancelled coroutine, so a second hop after it never
     * runs. The job's resume-queue hop did exactly that after every Stop, and its teardown hop (the
     * only call to pipelineEnded) was skipped.
     */
    @Test fun aSecondMainHopAfterACancelIsOnlyReachedFromAFinally() = kotlinx.coroutines.runBlocking {
        val main = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val work = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            suspend fun run(withFinally: Boolean): Boolean {
                var tornDown = false
                val started = kotlinx.coroutines.CompletableDeferred<Unit>()
                val job = launch(work) {
                    started.complete(Unit)
                    try { kotlinx.coroutines.awaitCancellation() } catch (_: Throwable) {}
                    val tail: suspend () -> Unit = { withContext(NonCancellable + main) { tornDown = true } }
                    if (withFinally) {
                        try { withContext(NonCancellable + main) { } } finally { tail() }
                    } else {
                        withContext(NonCancellable + main) { }
                        tail()
                    }
                }
                started.await(); job.cancelAndJoin()
                return tornDown
            }
            assertFalse("the second hop is skipped", run(withFinally = false))
            assertTrue("reached from a finally", run(withFinally = true))
        } finally { main.close(); work.close() }
    }
}
