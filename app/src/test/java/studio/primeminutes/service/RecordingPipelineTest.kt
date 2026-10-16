package studio.primeminutes.service

import android.content.Context
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.audio.AudioRecorder
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

/** The recording pipeline against a file-fed recorder, a scripted engine and an inert reader. */
@RunWith(RobolectricTestRunner::class)
class RecordingPipelineTest {
    private lateinit var ctx: Context
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        ctx = stringlessContext(ApplicationProvider.getApplicationContext())
        SessionLibrary.root(ctx).deleteRecursively()
        RunRegistry.resetForTest()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun tearDown() { scope.cancel(); SessionLibrary.root(ctx).deleteRecursively() }

    private fun pipeline(host: FakePipelineHost) =
        RecordingPipeline(host) { AudioRecorder(feed = testWav(File(ctx.cacheDir, "feed.wav"), 2.5)) }

    /** Run until [until] holds, then ask for a graceful stop; returns the host. */
    private fun record(defer: Boolean, until: (FakePipelineHost) -> Boolean = { it.of<TranscriptEvent.UtteranceSnapshot>().size >= 3 }) = runBlocking {
        val host = FakePipelineHost(ctx, scope)
        val p = pipeline(host)
        val run = scope.async(RunGen(7)) { p.run() }
        withTimeout(20_000) {
            while (!until(host)) delay(20)
            assertTrue("the mic is live while recording", RunRegistry.recordingActive)
            p.requestStop(defer)
            run.await()
        }
        host
    }

    @Test fun stopAndSaveSavesTheCaptureAndHandsTheTalkToTheReader() {
        val host = record(defer = false)
        val entry = SessionLibrary.list(ctx).single()
        assertEquals(SessionLibrary.Status.RECORDED, entry.status)
        assertTrue(entry.wavFile.exists())
        // Player repointed to the library copy, then the full tagged transcript.
        assertEquals(android.net.Uri.fromFile(entry.wavFile).toString(), host.of<TranscriptEvent.RecordingSaved>().single().uri)
        val complete = host.of<TranscriptEvent.Complete>().single()
        assertEquals(2, complete.speakerCount)
        assertTrue(complete.utterances.isNotEmpty())
        // The live transcript is kept as the queue's pending transcript (no re-recognition).
        assertEquals(complete.utterances, SessionLibrary.loadPendingTranscript(entry, FakePipelineHost.FP))
        // The reader got the talk, with its entry, and no longer counts as this run's live task.
        val task = host.reader.readTasks.single()
        assertTrue(task.final.isCompleted)
        assertEquals(entry.id, task.entry?.id)
        assertNull(host.reader.liveReader)
        // Mic released, foreground back to data sync for the finish, engine closed.
        assertFalse(RunRegistry.recordingActive)
        assertEquals(listOf(false), host.foreground)
        assertTrue((host.engine as ScriptedEngine).closed)
        assertNull(SessionLibrary.loadProgress(entry))
    }

    @Test fun nextTalkDefersTheSpeakerCount() {
        val host = record(defer = true)
        val complete = host.of<TranscriptEvent.Complete>().single()
        assertNull("deferred: no diarization result", complete.speakerCount)
        assertEquals(1, SessionLibrary.list(ctx).size)
    }

    @Test fun aNewStartClearsTheStopFlags() {
        val p = RecordingPipeline(FakePipelineHost(ctx, scope))
        p.requestStop(defer = true)
        assertTrue(p.stopRequested && p.deferRequested)
        p.resetStopFlags()
        assertFalse(p.stopRequested || p.deferRequested)
    }

    @Test fun cancellingKeepsTheCaptureAndTheFrozenTranscript() = runBlocking {
        val host = FakePipelineHost(ctx, scope)
        val p = pipeline(host)
        val run = scope.async(RunGen(7)) { p.run() }
        withTimeout(20_000) {
            while (host.of<TranscriptEvent.UtteranceSnapshot>().size < 4) delay(20)
            run.cancelAndJoin()
        }
        val entry = SessionLibrary.list(ctx).single()
        val progress = SessionLibrary.loadProgress(entry)!!   // the frozen prefix, for Resume
        assertFalse(progress.asrComplete)
        assertEquals(FakePipelineHost.FP, progress.fingerprint)
        assertTrue(progress.utterances.isNotEmpty())
        assertTrue("no terminal events after a cancel", host.of<TranscriptEvent.Complete>().isEmpty())
        assertFalse(RunRegistry.recordingActive)
    }
}
