package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

/** The file pipeline over one of our own work WAVs (no MediaCodec), a scripted engine, an inert reader. */
@RunWith(RobolectricTestRunner::class)
class ImportPipelineTest {
    private lateinit var ctx: Context
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        ctx = stringlessContext(ApplicationProvider.getApplicationContext())
        SessionLibrary.root(ctx).deleteRecursively()
        RunRegistry.resetForTest()
        TranscriptionService.resumeRequested = false
        TranscriptionService.importResumeRequested = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun tearDown() { scope.cancel(); SessionLibrary.root(ctx).deleteRecursively() }

    /** A decoded work WAV under files/audio: streamed as is, not decoded again. */
    private fun workWav(seconds: Double) =
        testWav(File(File(ctx.filesDir, "audio"), "decoded_${System.nanoTime()}.wav"), seconds)

    @Test fun queuePassOneTranscribesWithoutTheReaderOrTheLibrary() = runBlocking {
        val host = FakePipelineHost(ctx, scope, durationSec = 3.0)
        val wav = workWav(3.0)
        val res = scope.async(RunGen(TranscriptionService.QUEUE_GEN)) {
            ImportPipeline(host).run(Uri.fromFile(wav).toString(), summarizeAfter = false)
        }.await()!!
        val complete = host.of<TranscriptEvent.Complete>().single()
        assertEquals(2, complete.speakerCount)
        assertEquals(complete.utterances, res.first)
        assertNull(res.second.summary)
        // The queue owns its row: nothing promoted, the work WAV stays the player source.
        assertTrue(SessionLibrary.list(ctx).isEmpty())
        assertEquals(Uri.fromFile(wav).toString(), host.of<TranscriptEvent.RecordingSaved>().single().uri)
        assertTrue(host.reader.readTasks.isEmpty())
        assertTrue(host.of<TranscriptEvent.Progress>().last().fraction > 0.9f)
        assertTrue((host.engine as ScriptedEngine).closed)
    }

    @Test fun anImportIsSavedReadAndAttached() = runBlocking {
        val host = FakePipelineHost(ctx, scope, durationSec = 3.0)
        val p = ImportPipeline(host)
        val wav = workWav(3.0)
        val run = scope.async(RunGen(3)) { p.run(Uri.fromFile(wav).toString()) }
        // The live reading task gets the final transcript; the worker's result finishes the run.
        val task = withTimeout(20_000) {
            while (host.reader.readTasks.firstOrNull()?.final?.isCompleted != true) delay(20)
            host.reader.readTasks.single()
        }
        val entry = SessionLibrary.list(ctx).single()
        assertEquals("promoted from the ASR phase's finally", Uri.fromFile(entry.wavFile).toString(),
            host.of<TranscriptEvent.RecordingSaved>().single().uri)
        assertEquals(2, host.of<TranscriptEvent.Complete>().single().speakerCount)
        task.result.complete(ReaderWorker.SummaryResult("Title", "Summary"))
        val res = withTimeout(20_000) { run.await() }!!
        assertEquals("Summary", res.second.summary)
        assertEquals(host.of<TranscriptEvent.Complete>().single().utterances, res.first)
        assertTrue("the whole transcript is the checkpoint", SessionLibrary.loadProgress(entry)!!.asrComplete)
        assertNull(p.inFlight.wav)
        assertNull(host.reader.liveReader)
    }

    @Test fun stopMidTranscriptionBanksTheAudioAndTheFrozenPrefixOnce() = runBlocking {
        val host = FakePipelineHost(ctx, scope, ScriptedEngine(blockDelayMs = 15), durationSec = 6.0)
        val p = ImportPipeline(host)
        val wav = workWav(6.0)
        val run = scope.async(RunGen(3)) { p.run(Uri.fromFile(wav).toString()) }
        withTimeout(20_000) { while (p.inFlight.checkpoint == null || host.of<TranscriptEvent.UtteranceSnapshot>().size < 3) delay(10) }
        // ACTION_STOP: bank first (the run may never unwind), then cancel and wait.
        p.inFlight.bankOnStop { "model" }
        val entry = SessionLibrary.list(ctx).single()
        val banked = SessionLibrary.loadProgress(entry)
        assertNotNull(banked)
        assertFalse(banked!!.asrComplete)
        run.cancelAndJoin()
        assertEquals("the pipeline's finally does not promote twice", 1, SessionLibrary.list(ctx).size)
        val kept = SessionLibrary.loadProgress(entry)!!
        assertFalse(kept.asrComplete)
        assertTrue(kept.utterances.isNotEmpty())
        assertTrue(host.of<TranscriptEvent.Complete>().isEmpty())
        assertFalse(wav.exists())
    }
}
