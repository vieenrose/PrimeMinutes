package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

/** An import superseded by a new import goes back to the head of the processing queue. */
@RunWith(RobolectricTestRunner::class)
class SupersedeRequeueTest {
    private lateinit var ctx: Context
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        ctx = stringlessContext(ApplicationProvider.getApplicationContext())
        SessionLibrary.root(ctx).deleteRecursively()
        ProcessingQueue.clear(ctx)
        RunRegistry.resetForTest()
        TranscriptionService.resumeRequested = false
        TranscriptionService.importResumeRequested = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun tearDown() { scope.cancel(); ProcessingQueue.clear(ctx); SessionLibrary.root(ctx).deleteRecursively() }

    private fun importCopy(seconds: Double) =
        testWav(File(File(ctx.filesDir, "audio"), "shared_${System.nanoTime()}"), seconds)

    private fun run(kind: RunRegistry.Kind) = RunRegistry.Run(kind = kind, gen = 3)

    @Test fun onlyAnUnstoppedForegroundFileRunIsRequeued() {
        assertTrue(SupersedeRequeue.applies(run(RunRegistry.Kind.IMPORT), prevActive = true, userStopped = false))
        assertFalse("the user stopped it", SupersedeRequeue.applies(run(RunRegistry.Kind.IMPORT), true, userStopped = true))
        assertFalse("already over", SupersedeRequeue.applies(run(RunRegistry.Kind.IMPORT), prevActive = false, userStopped = false))
        assertFalse("a drain keeps its own items queued", SupersedeRequeue.applies(run(RunRegistry.Kind.QUEUE), true, false))
        assertFalse(SupersedeRequeue.applies(run(RunRegistry.Kind.RECORDING), true, false))
        assertFalse(SupersedeRequeue.applies(null, true, false))
    }

    @Test fun enqueueFrontPutsTheItemFirstOnce() {
        ProcessingQueue.enqueue(ctx, listOf("b", "c", "a"))
        ProcessingQueue.enqueueFront(ctx, "a")
        assertEquals(listOf("a", "b", "c"), ProcessingQueue.ids(ctx))
    }

    @Test fun aFinishedOrDeletedEntryIsNotRequeued() {
        assertFalse(SupersedeRequeue.requeue(ctx, null))
        val e = SessionLibrary.promoteRecording(ctx, testWav(File(ctx.cacheDir, "c.wav"), 2.0), 2)!!
        e.dir.deleteRecursively()
        assertFalse("deleted meanwhile", SupersedeRequeue.requeue(ctx, e))
        assertTrue(ProcessingQueue.ids(ctx).isEmpty())
    }

    /**
     * Field bug (A53, 2026-10-30): import B while import A transcribes → A was banked "not processed"
     * and never resumed. Superseded (cancelled without a Stop), A's entry is now the run's
     * [ImportPipeline.unfinished] and goes back to the head of the queue, ahead of what was queued.
     */
    @Test fun aSupersededImportIsRequeuedAtTheHead() = runBlocking {
        ProcessingQueue.enqueue(ctx, listOf("older"))
        val host = FakePipelineHost(ctx, scope, ScriptedEngine(blockDelayMs = 30), durationSec = 6.0)
        val p = ImportPipeline(host)
        val src = importCopy(6.0)
        val a = scope.async(RunGen(3)) { p.run(Uri.fromFile(src).toString()) }
        withTimeout(20_000) { while (host.of<TranscriptEvent.UtteranceSnapshot>().size < 3) delay(10) }
        a.cancel()   // superseded by the new import: no ACTION_STOP bank
        withTimeout(20_000) { runCatching { a.await() } }
        val banked = SessionLibrary.list(ctx).single()
        assertEquals(banked.id, p.unfinished?.id)
        assertTrue(SupersedeRequeue.requeue(ctx, p.unfinished))
        assertEquals(listOf(banked.id, "older"), ProcessingQueue.ids(ctx))
    }

    @Test fun aFinishedImportLeavesNoImportCopy() = runBlocking {
        val host = FakePipelineHost(ctx, scope, ScriptedEngine(), durationSec = 3.0)
        val p = ImportPipeline(host)
        val src = importCopy(3.0)
        val a = scope.async(RunGen(3)) { p.run(Uri.fromFile(src).toString()) }
        val task = withTimeout(20_000) {
            while (host.reader.readTasks.firstOrNull()?.final?.isCompleted != true) delay(20)
            host.reader.readTasks.single()
        }
        task.result.complete(ReaderWorker.SummaryResult("Title", "Summary"))
        assertNotNull(withTimeout(20_000) { a.await() })
        assertFalse("the import copy is gone once the meeting is banked", src.exists())
        assertTrue(SessionLibrary.list(ctx).single().wavFile.exists())
    }
}
