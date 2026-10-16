package studio.primeminutes.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the service is running right now, process-wide: the pipeline job (a recording, an import, a
 * re-summarize, the queue drain), the live microphone, the AI notes worker's tasks, the queue item
 * being processed and the exports in flight.
 *
 * The service publishes here; everything else reads. It replaces the static flags the service used
 * to expose one by one (`recordingJobActive`, `pipelineActive`, `readerActive`, …) with one value
 * that changes atomically and can be observed. The flags keep their exact old meaning, including
 * when each is set and cleared — the comments at each publish site in [TranscriptionService] say why.
 */
object RunRegistry {

    /** What a pipeline job was started to do (its intent action). */
    enum class Kind { RECORDING, IMPORT, SUMMARIZE, QUEUE }

    /** The pipeline job: its kind, the session generation its events are tagged with, its source. */
    data class Run(val kind: Kind, val gen: Int, val audioUri: String? = null, val startedAtMs: Long = 0L)

    data class Jobs(
        /** A pipeline job is active in this process (set once launched, cleared by its teardown). */
        val pipelineActive: Boolean = false,
        /** From ACTION_RECORD's start command until that job's teardown — set on MAIN before the
         *  job runs, so main-thread guards cannot race the recording's startup. */
        val recordingJobActive: Boolean = false,
        /** A queue drain loop is running (idle between items included), started or resumed. */
        val queueDraining: Boolean = false,
        /** The microphone capture itself is live (set by the recording job once it opens the mic). */
        val recordingActive: Boolean = false,
        /** The pipeline job the flags above describe, for the UI to re-attach to. */
        val pipeline: Run? = null,
        /** Library entry the queue drain is processing right now. */
        val queueItemId: String? = null,
        /** Generation of the task the AI notes worker is reading now (null: not reading). */
        val readerGen: Int? = null,
        /** Generations of every task the worker holds (waiting or being read), in reading order. */
        val readerGens: List<Int> = emptyList(),
        /** Session exports / edit persists / import downloads running in the service. */
        val exportsInFlight: Int = 0,
    ) {
        val readerActive: Boolean get() = readerGen != null

        /** The engines are taken (Ask refuses, the library indexer waits). */
        val busy: Boolean get() = recordingJobActive || pipelineActive || readerActive

        /** Session generations (the UI's own runs, not the queue) that still have work going on:
         *  the pipeline job's, and every reading the worker holds. */
        val activeSessionGens: Set<Int>
            get() = buildSet {
                pipeline?.takeIf { pipelineActive }?.gen?.let(::add)
                addAll(readerGens)
                readerGen?.let(::add)
            }.filterTo(LinkedHashSet()) { it >= 0 }
    }

    private val _jobs = MutableStateFlow(Jobs())
    val jobs: StateFlow<Jobs> = _jobs.asStateFlow()

    // --- Reads (the old static flags) ---
    val pipelineActive: Boolean get() = _jobs.value.pipelineActive
    val recordingJobActive: Boolean get() = _jobs.value.recordingJobActive
    val queueDraining: Boolean get() = _jobs.value.queueDraining
    val recordingActive: Boolean get() = _jobs.value.recordingActive
    val readerActive: Boolean get() = _jobs.value.readerActive
    val currentQueueItemId: String? get() = _jobs.value.queueItemId
    val exportsInFlight: Int get() = _jobs.value.exportsInFlight
    val busy: Boolean get() = _jobs.value.busy

    // --- Publishing (the service and its reader worker) ---

    /** A start command is about to launch a new job: it supersedes whatever ran before. */
    fun runStarting(recording: Boolean, queue: Boolean) =
        _jobs.update { it.copy(recordingJobActive = recording, queueDraining = queue) }

    /** The new job is launched and is now the service's active one. */
    fun pipelineStarted(run: Run) = _jobs.update { it.copy(pipelineActive = true, pipeline = run) }

    /** A finished foreground run goes on draining the queue in the same job. */
    fun queueResumed() = _jobs.update {
        it.copy(queueDraining = true, pipeline = it.pipeline?.copy(gen = TranscriptionService.QUEUE_GEN))
    }

    /** The active job's teardown (or the system's time limit): nothing runs any more. */
    fun pipelineEnded() = _jobs.update {
        it.copy(pipelineActive = false, recordingJobActive = false, queueDraining = false, pipeline = null)
    }

    fun micLive(live: Boolean) = _jobs.update { it.copy(recordingActive = live) }

    fun queueItem(id: String?) = _jobs.update { it.copy(queueItemId = id) }

    fun readerTasks(gens: List<Int>) = _jobs.update { it.copy(readerGens = gens) }

    fun readerReading(gen: Int?) = _jobs.update { it.copy(readerGen = gen) }

    fun exports(count: Int) = _jobs.update { it.copy(exportsInFlight = count) }

    /** Tests only: a clean registry. */
    internal fun resetForTest() {
        _jobs.value = Jobs()
    }
}
