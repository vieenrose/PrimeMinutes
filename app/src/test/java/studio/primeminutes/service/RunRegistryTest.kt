package studio.primeminutes.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.reader.AgentEvent
import studio.primeminutes.core.reader.AgentState

/** The run flags keep the meaning of the static fields they replace. */
class RunRegistryTest {

    @Before fun reset() = RunRegistry.resetForTest()

    @Test fun recordingFlagIsUpBeforeTheJobIsLaunched() {
        RunRegistry.runStarting(recording = true, queue = false)
        // The recovery check and the queue-kick guard read it on MAIN before the job ever runs.
        assertTrue(RunRegistry.recordingJobActive)
        assertFalse(RunRegistry.pipelineActive)
        assertTrue(RunRegistry.busy)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.RECORDING, gen = 7))
        assertTrue(RunRegistry.pipelineActive)
        assertEquals(setOf(7), RunRegistry.jobs.value.activeSessionGens)
    }

    @Test fun aStopRequestIsPublishedForTheRecordingUntilItsJobEnds() {
        RunRegistry.captureStopping(nextTalk = true)
        assertNull("no recording: nothing to stop", RunRegistry.jobs.value.captureStop)
        RunRegistry.runStarting(recording = true, queue = false)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.RECORDING, gen = 4))
        RunRegistry.captureStopping(nextTalk = false)
        assertEquals(RunRegistry.CaptureStop(4, nextTalk = false), RunRegistry.jobs.value.captureStop)
        RunRegistry.captureStopping(nextTalk = true)
        assertEquals(RunRegistry.CaptureStop(4, nextTalk = true), RunRegistry.jobs.value.captureStop)
        RunRegistry.pipelineEnded()
        assertNull(RunRegistry.jobs.value.captureStop)
    }

    @Test fun aNewStartOverwritesTheRunTypeInsteadOfAccumulating() {
        RunRegistry.runStarting(recording = true, queue = false)
        RunRegistry.runStarting(recording = false, queue = true)
        assertFalse(RunRegistry.recordingJobActive)
        assertTrue(RunRegistry.queueDraining)
    }

    @Test fun resumingTheQueueKeepsTheRecordingFlagButLeavesTheSession() {
        RunRegistry.runStarting(recording = true, queue = false)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.RECORDING, gen = 3))
        RunRegistry.queueResumed()
        assertTrue(RunRegistry.queueDraining)
        assertTrue(RunRegistry.recordingJobActive)   // as before: cleared only by the teardown
        // The drain's events are QUEUE_GEN: the session that started the job is no longer running.
        assertTrue(RunRegistry.jobs.value.activeSessionGens.isEmpty())
    }

    @Test fun teardownClearsEveryPipelineFlag() {
        RunRegistry.runStarting(recording = false, queue = true)
        RunRegistry.pipelineStarted(RunRegistry.Run(RunRegistry.Kind.QUEUE, gen = TranscriptionService.QUEUE_GEN))
        RunRegistry.pipelineEnded()
        val j = RunRegistry.jobs.value
        assertFalse(j.pipelineActive || j.recordingJobActive || j.queueDraining)
        assertNull(j.pipeline)
        assertFalse(RunRegistry.busy)
    }

    @Test fun theReaderKeepsTheEnginesBusyAndItsSessionsAlive() {
        RunRegistry.readerTasks(listOf(4, TranscriptionService.QUEUE_GEN, 5))
        assertFalse(RunRegistry.busy)                        // held, not reading yet
        RunRegistry.readerReading(4)
        assertTrue(RunRegistry.readerActive)
        assertTrue(RunRegistry.busy)                         // Ask refuses while the reader reads
        assertEquals(setOf(4, 5), RunRegistry.jobs.value.activeSessionGens)
        RunRegistry.readerReading(null)
        RunRegistry.readerTasks(emptyList())
        assertFalse(RunRegistry.busy)
        assertTrue(RunRegistry.jobs.value.activeSessionGens.isEmpty())
    }

    @Test fun queueItemMicAndExportsAreReportedAsPublished() {
        RunRegistry.queueItem("20261014-175615")
        RunRegistry.micLive(true)
        RunRegistry.exports(2)
        assertEquals("20261014-175615", RunRegistry.currentQueueItemId)
        assertTrue(RunRegistry.recordingActive)
        assertEquals(2, RunRegistry.exportsInFlight)
        assertFalse(RunRegistry.busy)                        // a live mic alone is not a job flag
    }

    // --- The run logs a screen re-attaches from ---

    private fun utt(i: Int) = TranscriptEvent.Utterance(i, "l$i", i.toDouble(), i + 0.5)

    @Test fun eventsAreNumberedAndKeptPerRun() {
        RunRegistry.tryEmit(3, TranscriptEvent.Status("a"))
        RunRegistry.tryEmit(TranscriptionService.UNTAGGED, TranscriptEvent.Status("export"))
        RunRegistry.tryEmit(4, TranscriptEvent.Status("b"))
        val snap = RunRegistry.attach()
        assertEquals(3L, snap.seq)
        assertEquals(listOf(TranscriptEvent.Status("a")), snap.sessions[3])
        assertEquals(setOf(3, 4), snap.sessions.keys)       // untagged events belong to no run
        assertEquals(4, snap.highestGen)
    }

    @Test fun onlyTheLatestRunsKeepALog() {
        for (g in 0..9) RunRegistry.tryEmit(g, TranscriptEvent.Status("s$g"))
        assertEquals(setOf(6, 7, 8, 9), RunRegistry.attach().sessions.keys)
    }

    @Test fun theLogIsCompactedToWhatAReplayNeeds() {
        val log = ArrayList<TranscriptEvent>()
        RunRegistry.fold(log, TranscriptEvent.Status("one"))
        RunRegistry.fold(log, TranscriptEvent.Utterance(0, "x", 0.0, 1.0))
        RunRegistry.fold(log, TranscriptEvent.Utterance(1, "y", 1.0, 2.0))
        RunRegistry.fold(log, TranscriptEvent.Status("two"))
        RunRegistry.fold(log, TranscriptEvent.Progress(0.1f))
        RunRegistry.fold(log, TranscriptEvent.Progress(0.2f))
        assertEquals(listOf(utt0(), utt1(), TranscriptEvent.Status("two"), TranscriptEvent.Progress(0.2f)), log)
        RunRegistry.fold(log, TranscriptEvent.Complete(listOf(utt(0))))
        assertEquals(listOf(TranscriptEvent.Status("two"), TranscriptEvent.Progress(0.2f), TranscriptEvent.Complete(listOf(utt(0)))), log)
        // Streamed text joins, but not onto a reset (whose own text the screen ignores).
        RunRegistry.fold(log, TranscriptEvent.Partial("", reset = true))
        RunRegistry.fold(log, TranscriptEvent.Partial("Sum"))
        RunRegistry.fold(log, TranscriptEvent.Partial("mary"))
        assertEquals(listOf(TranscriptEvent.Partial("", reset = true), TranscriptEvent.Partial("Summary")), log.filterIsInstance<TranscriptEvent.Partial>())
        RunRegistry.fold(log, TranscriptEvent.SummaryComplete("Summary."))
        assertTrue(log.none { it is TranscriptEvent.Partial })
        // The AI notes trace: streamed tokens join, a new reading starts it over.
        RunRegistry.fold(log, TranscriptEvent.Agent(AgentEvent.TurnToken(1, "a")))
        RunRegistry.fold(log, TranscriptEvent.Agent(AgentEvent.TurnToken(1, "b")))
        assertEquals(TranscriptEvent.Agent(AgentEvent.TurnToken(1, "ab")), log.last())
        RunRegistry.fold(log, TranscriptEvent.Agent(AgentEvent.State(AgentState.STARTING)))
        assertEquals(1, log.count { it is TranscriptEvent.Agent })
    }

    private fun utt0() = TranscriptEvent.Utterance(0, "x", 0.0, 1.0)
    private fun utt1() = TranscriptEvent.Utterance(1, "y", 1.0, 2.0)

    @Test fun theQueueLogFollowsTheCurrentItem() {
        RunRegistry.queueItem("a")
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.Title("A"))
        RunRegistry.queueItem("b")
        RunRegistry.tryEmit(TranscriptionService.QUEUE_GEN, TranscriptEvent.Title("B"))
        val snap = RunRegistry.attach()
        assertEquals("b", snap.queueItemId)
        assertEquals(listOf(TranscriptEvent.Title("B")), snap.queue)
    }

    @Test fun aSubscriberAttachingMidRunMissesNothingAndSeesNothingTwice() = kotlinx.coroutines.runBlocking {
        RunRegistry.tryEmit(1, TranscriptEvent.Utterance(0, "before", 0.0, 1.0))
        val seen = java.util.Collections.synchronizedList(ArrayList<TranscriptEvent>())
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            var floor = Long.MAX_VALUE
            RunRegistry.events.onSubscription {
                val snap = RunRegistry.attach()
                floor = snap.seq; seen += snap.sessions[1].orEmpty(); ready.complete(Unit)
            }.collect { if (it.seq > floor) seen += it.event }
        }
        // Emitted from another thread while the subscriber attaches.
        val emitter = launch(kotlinx.coroutines.Dispatchers.Default) {
            for (i in 1..200) RunRegistry.emit(1, TranscriptEvent.Utterance(i, "u$i", i.toDouble(), i + 0.5))
        }
        ready.await(); emitter.join()
        kotlinx.coroutines.withTimeout(5_000) { while (seen.size < 201) kotlinx.coroutines.delay(10) }
        job.cancel()
        assertEquals((0..200).toList(), seen.map { (it as TranscriptEvent.Utterance).index })
    }

    @Test fun theQueueWorksWhileItDrainsOrOneOfItsItemsIsRead() {
        assertFalse(RunRegistry.jobs.value.queueWorking)
        RunRegistry.runStarting(recording = false, queue = true)
        assertTrue(RunRegistry.jobs.value.queueWorking)
        RunRegistry.readerTasks(listOf(TranscriptionService.QUEUE_GEN))
        RunRegistry.pipelineEnded()
        assertTrue("its item is still being read", RunRegistry.jobs.value.queueWorking)
        RunRegistry.readerTasks(listOf(3)); RunRegistry.readerReading(3)
        assertFalse("a session's reading is not the queue's", RunRegistry.jobs.value.queueWorking)
    }
}
