package studio.primeminutes.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
