package studio.primeminutes.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary
import java.io.File
import java.util.Collections

/**
 * The queue drain over library captures: pass 1 through the real [ImportPipeline] (scripted engine),
 * pass 2 through an inert reader whose "worker" is played by the test (it saves and dequeues each
 * task the way [ReaderWorker]'s readDone does).
 */
@RunWith(RobolectricTestRunner::class)
class QueueRunnerTest {
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

    private var n = 0
    /** A RECORDED library capture, as "Next talk" leaves it. */
    private fun capture(seconds: Double): SessionLibrary.Entry =
        SessionLibrary.promoteRecording(ctx, testWav(File(ctx.cacheDir, "cap_${n++}.wav"), seconds), seconds.toInt())!!

    private fun host(blockDelayMs: Long = 0) =
        FakePipelineHost(ctx, scope, durationSec = 2.0, nextEngine = { ScriptedEngine(blockDelayMs = blockDelayMs) })

    private fun drain(host: FakePipelineHost) =
        scope.async(RunGen(TranscriptionService.QUEUE_GEN)) { QueueRunner(host, ImportPipeline(host)).run() }

    /** Plays the AI notes worker: reads tasks in order, saves (here: clears the sidecar) and dequeues. */
    private class Worker(val read: MutableList<String>, val completesAtFirstRead: IntArray, val job: Job)
    private fun worker(host: FakePipelineHost): Worker {
        val read = Collections.synchronizedList(ArrayList<String>())
        val atFirst = intArrayOf(-1)
        val job = scope.launch {
            while (isActive) {
                val t = synchronized(host.reader.readLock) { host.reader.readTasks.firstOrNull() }
                if (t == null) { delay(10); continue }
                val e = t.entry!!
                assertEquals(TranscriptionService.QUEUE_GEN, t.gen)
                assertTrue("pass 2 gets the whole transcript", t.final.isCompleted && t.final.getCompleted().isNotEmpty())
                if (read.isEmpty()) atFirst[0] = host.of<TranscriptEvent.Complete>().size
                read += e.id
                SessionLibrary.clearPendingTranscript(e)
                ProcessingQueue.remove(ctx, e.id)
                synchronized(host.reader.readLock) { host.reader.readTasks.remove(t) }
                t.result.complete(ReaderWorker.SummaryResult("Title ${e.id}", "Summary"))
            }
        }
        return Worker(read, atFirst, job)
    }

    /** The drain ends with a QUEUE_GEN nudge so the Studio row's "Processing" chip clears. */
    private fun assertDrainNudged() {
        val snap = RunRegistry.attach()
        assertNull(snap.queueItemId)
        assertEquals(listOf<TranscriptEvent>(TranscriptEvent.Progress(0f)), snap.queue)
    }

    @Test fun everyItemIsTranscribedBeforeAnyIsReadInQueueOrder() = runBlocking {
        val items = (0 until 3).map { capture(2.0) }
        ProcessingQueue.enqueue(ctx, items.map { it.id })
        val host = host()
        val w = worker(host)
        withTimeout(30_000) { drain(host).await() }
        w.job.cancel()
        assertEquals(items.map { it.id }, w.read)
        assertEquals("pass 1 ran over every item before pass 2 read the first", 3, w.completesAtFirstRead[0])
        assertEquals(3, host.enginesCreated)
        assertTrue(ProcessingQueue.ids(ctx).isEmpty())
        assertTrue(host.failedItems.isEmpty())
        assertDrainNudged()
    }

    @Test fun aFailedItemIsDroppedAndTheQueueGoesOn() = runBlocking {
        val items = (0 until 3).map { capture(2.0) }
        ProcessingQueue.enqueue(ctx, items.map { it.id })
        val host = host()
        var runs = 0
        host.onEnsureModels = { if (++runs == 2) throw IllegalStateException("corrupt model") }
        val w = worker(host)
        withTimeout(30_000) { drain(host).await() }
        w.job.cancel()
        assertEquals(listOf(items[0].id, items[2].id), w.read)
        assertEquals(listOf(SessionLibrary.defaultTitle(ctx, items[1].createdAt)), host.failedItems)
        assertTrue(ProcessingQueue.ids(ctx).isEmpty())
        // Its capture stays in the library, RECORDED, for a manual retry.
        assertEquals(SessionLibrary.Status.RECORDED, SessionLibrary.byId(ctx, items[1].id)!!.status)
        assertDrainNudged()
    }

    @Test fun stopMidQueueKeepsEveryItemQueued() = runBlocking {
        val items = (0 until 2).map { capture(4.0) }
        ProcessingQueue.enqueue(ctx, items.map { it.id })
        val host = host(blockDelayMs = 20)
        val run = drain(host)
        withTimeout(20_000) {
            while (RunRegistry.currentQueueItemId == null || host.of<TranscriptEvent.UtteranceSnapshot>().size < 2) delay(10)
        }
        assertEquals(items[0].id, RunRegistry.currentQueueItemId)
        run.cancelAndJoin()
        assertEquals("a cancelled drain dequeues nothing", items.map { it.id }, ProcessingQueue.ids(ctx))
        assertNull(SessionLibrary.loadPendingTranscript(items[0], FakePipelineHost.FP))
        assertTrue(host.failedItems.isEmpty())
        assertTrue(host.reader.readTasks.isEmpty())
        assertDrainNudged()
        // The next drain picks them all up.
        val host2 = host()
        val w = worker(host2)
        withTimeout(30_000) { drain(host2).await() }
        w.job.cancel()
        assertEquals(items.map { it.id }, w.read)
        assertTrue(ProcessingQueue.ids(ctx).isEmpty())
    }

    @Test fun aPersistedQueueResumesAfterAKill() = runBlocking {
        val a = capture(2.0)
        val b = capture(2.0)
        // Killed between the passes for a: its transcript is in the sidecar, the queue file is on disk.
        val kept = listOf(TranscriptEvent.Utterance(0, "kept", 0.0, 1.0, 0))
        SessionLibrary.savePendingTranscript(a, kept, FakePipelineHost.FP)
        File(ctx.filesDir, "process_queue.json").writeText("""{"ids":["gone","${a.id}","${b.id}"]}""")
        val host = host()
        val w = worker(host)
        withTimeout(30_000) { drain(host).await() }
        w.job.cancel()
        assertEquals(listOf(a.id, b.id), w.read)
        assertEquals("a's ASR is not redone", 1, host.enginesCreated)
        assertTrue(ProcessingQueue.ids(ctx).isEmpty())
        assertNotNull(SessionLibrary.byId(ctx, a.id))
        assertDrainNudged()
    }
}
