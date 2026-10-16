package studio.primeminutes.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.reader.AgentEvent
import studio.primeminutes.core.reader.AgentState

/**
 * What the service is running right now, process-wide: the pipeline job (a recording, an import, a
 * re-summarize, the queue drain), the live microphone, the AI notes worker's tasks, the queue item
 * being processed and the exports in flight.
 *
 * The service publishes here; everything else reads. It replaces the static flags the service used
 * to expose one by one (`recordingJobActive`, `pipelineActive`, `readerActive`, …) with one value
 * that changes atomically and can be observed. The flags keep their exact old meaning, including
 * when each is set and cleared — the comments at each publish site in [TranscriptionService] say why.
 *
 * It also carries the service's event bus ([events]) and keeps, per run, the events it emitted so
 * far (compacted: a snapshot replaces the lines before it, a status the previous one…). A screen
 * that opens while a run is going — the app reopened, the Activity recreated — reads them with
 * [attach] and replays them, then follows the live events after that point ([Stamped.seq]).
 */
object RunRegistry {

    /** What a pipeline job was started to do (its intent action). */
    enum class Kind { RECORDING, IMPORT, SUMMARIZE, QUEUE }

    /** The pipeline job: its kind, the session generation its events are tagged with, its source. */
    data class Run(
        val kind: Kind, val gen: Int, val audioUri: String? = null, val startedAtMs: Long = 0L,
        /** A re-summarize of a library session: that session as it was when the run started. */
        val resummary: TranscriptionService.PersistRequest? = null,
        /** That re-summarize also writes a new title. */
        val resummaryTitle: Boolean = false,
    )

    /** The recording [gen] was asked to stop (⏹ Stop & save, or ⏭ Next talk: [nextTalk]). */
    data class CaptureStop(val gen: Int, val nextTalk: Boolean)

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
        /** The recording's capture is winding down after a stop request (until the job ends). */
        val captureStop: CaptureStop? = null,
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

        /** The processing queue still has work going on: its drain loop, or a reading of one of its
         *  items (a Stop leaves the item being read to finish). */
        val queueWorking: Boolean
            get() = queueDraining || readerGen == TranscriptionService.QUEUE_GEN || TranscriptionService.QUEUE_GEN in readerGens

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
        _jobs.update { it.copy(recordingJobActive = recording, queueDraining = queue, captureStop = null) }

    /** A stop request reached the running recording (ignored when no recording runs). */
    fun captureStopping(nextTalk: Boolean) = _jobs.update { j ->
        val run = j.pipeline?.takeIf { j.pipelineActive && it.kind == Kind.RECORDING } ?: return@update j
        j.copy(captureStop = CaptureStop(run.gen, nextTalk || j.captureStop?.takeIf { it.gen == run.gen }?.nextTalk == true))
    }

    /** The new job is launched and is now the service's active one. */
    fun pipelineStarted(run: Run) = _jobs.update { it.copy(pipelineActive = true, pipeline = run) }

    /** A finished foreground run goes on draining the queue in the same job. */
    fun queueResumed() = _jobs.update {
        it.copy(queueDraining = true, captureStop = null, pipeline = it.pipeline?.copy(gen = TranscriptionService.QUEUE_GEN, resummary = null))
    }

    /** The active job's teardown (or the system's time limit): nothing runs any more. */
    fun pipelineEnded() = _jobs.update {
        it.copy(pipelineActive = false, recordingJobActive = false, queueDraining = false, pipeline = null, captureStop = null)
    }

    fun micLive(live: Boolean) = _jobs.update { it.copy(recordingActive = live) }

    fun queueItem(id: String?) = _jobs.update { it.copy(queueItemId = id) }

    fun readerTasks(gens: List<Int>) = _jobs.update { it.copy(readerGens = gens) }

    fun readerReading(gen: Int?) = _jobs.update { it.copy(readerGen = gen) }

    fun exports(count: Int) = _jobs.update { it.copy(exportsInFlight = count) }

    // ---------------------------------------------------------------------------------------------
    // Events: the bus, and what each run emitted so far
    // ---------------------------------------------------------------------------------------------

    /** One service event, numbered in emission order. [gen]: the run it is tagged with. */
    data class Stamped(val seq: Long, val gen: Int, val event: TranscriptEvent)

    /**
     * What a screen attaching now must replay: [sessions] — the events of the latest session runs
     * (gen ≥ 0), [queue] those of the queue item being processed ([queueItemId]), all up to [seq].
     */
    data class Snapshot(
        val seq: Long,
        val jobs: Jobs,
        val sessions: Map<Int, List<TranscriptEvent>>,
        val queueItemId: String?,
        val queue: List<TranscriptEvent>,
        /** The highest run generation the service has seen: a new screen numbers its runs above it. */
        val highestGen: Int,
    )

    private val eventLock = Any()
    private var seq = 0L
    private val logs = LinkedHashMap<Int, MutableList<TranscriptEvent>>()
    private var queueLog = ArrayList<TranscriptEvent>()
    private var queueLogItem: String? = null
    private var highestGen = -1

    // replay = 0: a screen must be collecting (it catches up through [attach] instead).
    private val _events = MutableSharedFlow<Stamped>(extraBufferCapacity = 256)
    val events: SharedFlow<Stamped> = _events.asSharedFlow()

    /** Emit without suspending (dropped when the buffer is full, like the bus always did). */
    fun tryEmit(gen: Int, e: TranscriptEvent): Boolean = synchronized(eventLock) { _events.tryEmit(record(gen, e)) }

    suspend fun emit(gen: Int, e: TranscriptEvent) {
        // Numbered and sent under one lock, so the bus order is the log order; only a full buffer
        // waits outside it.
        var pending: Stamped? = null
        synchronized(eventLock) {
            val st = record(gen, e)
            if (!_events.tryEmit(st)) pending = st
        }
        pending?.let { _events.emit(it) }
    }

    /**
     * Everything emitted so far, for a screen subscribing now. Call it from the subscription
     * itself (`events.onSubscription { attach() }`): an event is then either in the snapshot
     * (`seq <= snapshot.seq`) or delivered to the new subscriber, never neither.
     */
    fun attach(): Snapshot = synchronized(eventLock) {
        val j = _jobs.value
        val top = maxOf(highestGen, j.pipeline?.gen ?: -1, j.readerGen ?: -1, j.readerGens.maxOrNull() ?: -1)
        Snapshot(seq, j, logs.mapValues { it.value.toList() }, queueLogItem, queueLog.toList(), top)
    }

    private fun record(gen: Int, e: TranscriptEvent): Stamped {
        val st = Stamped(++seq, gen, e)
        when {
            gen >= 0 -> {
                if (gen > highestGen) highestGen = gen
                val log = logs.getOrPut(gen) {
                    // The latest few runs are enough: a screen re-attaches to the one still going.
                    while (logs.size >= MAX_RUN_LOGS) logs.remove(logs.keys.first())
                    ArrayList()
                }
                fold(log, e)
            }
            gen == TranscriptionService.QUEUE_GEN -> {
                // The queue moved on to another item: what the screen replays starts over with it.
                val item = _jobs.value.queueItemId
                if (item != queueLogItem) { queueLog = ArrayList(); queueLogItem = item }
                fold(queueLog, e)
            }
        }
        return st
    }

    /** How many runs keep their events. */
    private const val MAX_RUN_LOGS = 4

    /**
     * Append [e] to [log], dropping what it makes irrelevant to a replay: a status line replaces
     * the previous one, a snapshot or the final transcript every line before, a fresh summary
     * buffer the partial text before; streamed text joins the previous piece; a new reading starts
     * the AI notes trace over.
     */
    internal fun fold(log: MutableList<TranscriptEvent>, e: TranscriptEvent) {
        val last = log.lastOrNull()
        when (e) {
            is TranscriptEvent.Status, is TranscriptEvent.Progress, is TranscriptEvent.MicLevel,
            is TranscriptEvent.DownloadProgress -> log.removeAll { it::class == e::class }
            is TranscriptEvent.UtteranceSnapshot, is TranscriptEvent.Complete -> log.removeAll {
                it is TranscriptEvent.Utterance || it is TranscriptEvent.UtteranceSnapshot || it is TranscriptEvent.Complete
            }
            is TranscriptEvent.SummaryComplete -> log.removeAll { it is TranscriptEvent.Partial || it is TranscriptEvent.SummaryComplete }
            is TranscriptEvent.Partial ->
                if (e.reset) log.removeAll { it is TranscriptEvent.Partial }
                // (not onto a reset, whose own text a replay ignores)
                else if (last is TranscriptEvent.Partial && !last.reset) {
                    log[log.lastIndex] = last.copy(chunk = last.chunk + e.chunk); return
                }
            is TranscriptEvent.Agent -> {
                val a = e.event
                if (a is AgentEvent.State && a.state == AgentState.STARTING) log.removeAll { it is TranscriptEvent.Agent }
                val prev = (last as? TranscriptEvent.Agent)?.event
                if (a is AgentEvent.TurnToken && prev is AgentEvent.TurnToken && prev.window == a.window) {
                    log[log.lastIndex] = TranscriptEvent.Agent(prev.copy(piece = prev.piece + a.piece)); return
                }
            }
            else -> Unit
        }
        log += e
    }

    /** Tests only: a clean registry. */
    internal fun resetForTest() {
        _jobs.value = Jobs()
        synchronized(eventLock) {
            seq = 0; logs.clear(); queueLog = ArrayList(); queueLogItem = null; highestGen = -1
        }
    }
}
