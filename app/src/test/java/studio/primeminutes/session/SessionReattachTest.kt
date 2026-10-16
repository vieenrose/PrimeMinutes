package studio.primeminutes.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.reader.AgentEvent
import studio.primeminutes.core.reader.AgentState
import studio.primeminutes.service.RunRegistry
import studio.primeminutes.service.TranscriptionService

/** A screen created while the service works picks the run up where it is. */
@RunWith(RobolectricTestRunner::class)
class SessionReattachTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = RunRegistry.resetForTest()
    @After fun tearDown() = RunRegistry.resetForTest()

    private val strings = object : SessionStrings {
        override fun str(id: Int, vararg args: Any) = "#$id"
        override fun plural(id: Int, n: Int, vararg args: Any) = "#$id:$n"
    }
    private fun newScreen() = SessionViewModel(app, strings)

    private fun utt(i: Int) = TranscriptEvent.Utterance(i, "l$i", i.toDouble(), i + 0.5, 0)

    private fun importRunning(gen: Int) {
        RunRegistry.runStarting(recording = false, queue = false)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.IMPORT, gen, "content://x/a.mp3", 1L))
    }

    @Test fun aNewScreenReattachesToTheImportInProgress() {
        importRunning(gen = 5)
        RunRegistry.tryEmit(5, TranscriptEvent.Status("transcribing"))
        RunRegistry.tryEmit(5, TranscriptEvent.Utterance(0, "l0", 0.0, 0.5, 0))
        RunRegistry.tryEmit(5, TranscriptEvent.Progress(0.4f))
        RunRegistry.tryEmit(5, TranscriptEvent.Agent(AgentEvent.State(AgentState.LISTENING, window = 1)))

        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        with(vm.now) {
            assertEquals(5, sessionGen)
            assertTrue(running)
            assertEquals(Screen.Studio, screen)   // the Studio banner leads back to it
            assertEquals("transcribing", status)
            assertEquals(listOf("l0"), utterances.map { it.text })
            assertEquals(0.4f, progress)
            assertEquals("content://x/a.mp3", audioUri.toString())
        }
        assertTrue(vm.agent.active)

        // …and follows it live from there, without replaying anything twice.
        RunRegistry.tryEmit(5, TranscriptEvent.Utterance(1, "l1", 1.0, 1.5, 0))
        RunRegistry.tryEmit(5, TranscriptEvent.Complete(listOf(utt(0), utt(1)), speakerCount = 1))
        RunRegistry.tryEmit(5, TranscriptEvent.SummaryComplete("sum"))
        ShadowLooper.idleMainLooper()
        with(vm.now) {
            assertEquals(listOf("l0", "l1"), utterances.map { it.text })
            assertTrue(transcriptReady)
            assertEquals("sum", summary)
            assertFalse(running)
        }
    }

    @Test fun nothingRunningMeansAFreshScreenNumberedAboveOldRuns() {
        RunRegistry.tryEmit(9, TranscriptEvent.Status("finished long ago"))
        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        assertFalse(vm.now.running)
        assertTrue(vm.now.utterances.isEmpty())
        // Its own first run (gen + 1 on start) can't be mistaken for run 9.
        assertTrue(vm.now.sessionGen >= 10)
        // A late event of run 9 does not reach it.
        RunRegistry.tryEmit(9, TranscriptEvent.Status("late"))
        ShadowLooper.idleMainLooper()
        assertFalse(vm.now.status == "late")
    }

    @Test fun aResummaryOfAnotherScreensSessionIsNotAdopted() {
        // Re-summarize carries no transcript in its events: nothing to show, so nothing is adopted.
        RunRegistry.runStarting(recording = false, queue = false)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.SUMMARIZE, 3))
        RunRegistry.tryEmit(3, TranscriptEvent.Partial("x", reset = true))
        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        assertFalse(vm.now.running)
        assertNull(vm.now.summary)
    }

    @Test fun aResummaryOfALibrarySessionIsAdoptedWithThatSession() {
        val names = mapOf(0 to studio.primeminutes.data.SpeakerName("Ann", confidence = "user"))
        val save = TranscriptionService.PersistRequest(
            entryId = "20261016-090000", audioUri = android.net.Uri.parse("file:///lib/20261016-090000/session.m4a"),
            utterances = listOf(utt(0), utt(1)), speakerNames = names, summary = null, actionItems = "- a",
            title = "Old title", notes = null, asrModelId = null, asrBackend = null, llmModelId = null,
        )
        RunRegistry.runStarting(recording = false, queue = false)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.SUMMARIZE, 6, save.audioUri.toString(), 1L, save, resummaryTitle = true))
        RunRegistry.tryEmit(6, TranscriptEvent.Agent(AgentEvent.State(AgentState.LISTENING, window = 2)))
        RunRegistry.tryEmit(6, TranscriptEvent.Partial("", reset = true))
        RunRegistry.tryEmit(6, TranscriptEvent.Partial("new "))
        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        with(vm.now) {
            assertEquals(6, sessionGen); assertTrue(running); assertTrue(transcriptReady)
            assertEquals(listOf("l0", "l1"), utterances.map { it.text })
            assertEquals(names, speakerNames)
            assertEquals("20261016-090000", libraryDir?.name)
            assertNull(title)   // being rewritten: the new one fills it
            assertEquals("new ", summary)
        }
        assertTrue(vm.agent.active)
        RunRegistry.tryEmit(6, TranscriptEvent.SummaryComplete("new summary"))
        RunRegistry.tryEmit(6, TranscriptEvent.Title("New title"))
        ShadowLooper.idleMainLooper()
        with(vm.now) { assertEquals("new summary", summary); assertEquals("New title", title); assertFalse(running) }
    }

    @Test fun aRecordingAlreadyAskedToStopIsShownWindingDown() {
        RunRegistry.runStarting(recording = true, queue = false)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.RECORDING, 8, null, 1L))
        RunRegistry.micLive(true)
        RunRegistry.captureStopping(nextTalk = true)
        RunRegistry.tryEmit(8, TranscriptEvent.UtteranceSnapshot(listOf(utt(0)), stable = 1))
        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        with(vm.now) { assertEquals(8, sessionGen); assertTrue(running); assertFalse(isRecording); assertTrue(deferStopped) }
        // The deferred capture's terminal event is Complete: the session settles.
        RunRegistry.tryEmit(8, TranscriptEvent.Complete(listOf(utt(0)), speakerCount = null))
        ShadowLooper.idleMainLooper()
        assertFalse(vm.now.running)
    }

    @Test fun aRunStoppedFromTheNotificationSettlesTheScreen() {
        importRunning(gen = 5)
        RunRegistry.tryEmit(5, TranscriptEvent.Utterance(0, "l0", 0.0, 0.5, 0))
        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        assertTrue(vm.now.running)
        // ACTION_STOP from the notification: the job ends, no terminal event is sent.
        RunRegistry.pipelineEnded()
        ShadowLooper.idleMainLooper()
        assertTrue("a grace for the run's last events", vm.now.running)
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofSeconds(2))
        assertFalse(vm.now.running)
        assertEquals(listOf("l0"), vm.now.utterances.map { it.text })   // what it had stays
    }

    @Test fun aRunThatFinishesNormallyIsNotMarkedStopped() {
        importRunning(gen = 5)
        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        RunRegistry.tryEmit(5, TranscriptEvent.Complete(listOf(utt(0)), speakerCount = 1))
        RunRegistry.tryEmit(5, TranscriptEvent.SummaryComplete("sum"))
        RunRegistry.pipelineEnded()
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofSeconds(2))
        assertEquals("sum", vm.now.summary)
        assertEquals("#${studio.primeminutes.R.string.status_done}", vm.now.status)
    }

    @Test fun theQueueItemInProgressIsShownOnItsRow() {
        RunRegistry.runStarting(recording = false, queue = true)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.QUEUE, TranscriptionService.QUEUE_GEN))
        RunRegistry.queueItem("20261014-175615")
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.Status("reading"))
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.Progress(0.7f))
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.Title("Q"))
        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        with(vm.now) {
            assertEquals("20261014-175615", queueItemId)
            assertEquals("reading", queueLabel)
            assertEquals(0.7f, queueFraction)
            assertEquals("Q", queueTitle)
            assertFalse(running)   // not watched: the open session is untouched
        }
    }

    private fun watchedQueueItem(): SessionViewModel {
        RunRegistry.runStarting(recording = false, queue = true)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.QUEUE, TranscriptionService.QUEUE_GEN))
        RunRegistry.queueItem("q1")
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.Status("transcribing"))
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.Utterance(0, "l0", 0.0, 0.5, 0))
        val vm = newScreen()
        ShadowLooper.idleMainLooper()
        val root = SessionLibrary.root(app)
        vm.watchQueueItem(SessionLibrary.Entry("q1", java.io.File(root, "q1"), null, 0L, 0, SessionLibrary.Status.RECORDED))
        ShadowLooper.idleMainLooper()
        assertTrue(vm.now.watchingQueue); assertTrue(vm.now.running)
        return vm
    }

    /** The drain's end as ACTION_STOP leaves it: the item released, the drain's nudge, the teardown. */
    private fun drainStopped() {
        RunRegistry.queueItem(null)
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.Progress(0f))
        RunRegistry.pipelineEnded()
    }

    @Test fun aWatchedQueueItemStoppedFromTheNotificationSettlesTheScreen() {
        val vm = watchedQueueItem()
        drainStopped()
        ShadowLooper.idleMainLooper()
        assertTrue("a grace for the run's last events", vm.now.running)
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofSeconds(2))
        with(vm.now) {
            assertFalse(running); assertFalse(watchingQueue)
            assertEquals("#${studio.primeminutes.R.string.status_stopped}", status)
            assertNull(queueItemId)   // the row is no longer "Processing"
            assertEquals(listOf("l0"), utterances.map { it.text })   // what it had stays
        }
    }

    @Test fun aWatchedQueueItemStillBeingReadAfterTheStopKeepsGoing() {
        val vm = watchedQueueItem()
        // The item was in its reading: a Stop leaves the reader to finish it (it has a library entry).
        RunRegistry.readerTasks(listOf(TranscriptionService.QUEUE_GEN))
        RunRegistry.readerReading(TranscriptionService.QUEUE_GEN)
        drainStopped()
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofSeconds(3))
        assertTrue(vm.now.running); assertTrue(vm.now.watchingQueue)
        // The reading ends with its result, which settles the view itself.
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.SummaryComplete("sum"))
        RunRegistry.readerReading(null); RunRegistry.readerTasks(emptyList())
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofSeconds(2))
        assertFalse(vm.now.running)
        assertEquals("sum", vm.now.summary)
        assertEquals("#${studio.primeminutes.R.string.status_done}", vm.now.status)
    }
}
