package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.LlmSpec
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.reader.AgentEvent
import studio.primeminutes.core.reader.AgentState
import studio.primeminutes.core.reader.ReaderCheckpoint
import studio.primeminutes.core.reader.ReaderLane
import studio.primeminutes.core.reader.ReaderProtocol
import studio.primeminutes.core.reader.mapText
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File

/** Total RAM from which ASR + diarization and the reader run concurrently (integration note §6
 *  gates live mode at "8 GB"). An 8 GB phone reports ~7.4 GiB of totalMem (the OPPO Reno7:
 *  7,728,400 kB), so the gate is 7 GiB: every 8 GB phone passes, 6 GB phones do not. */
private const val LIVE_READER_MIN_RAM = 7L * 1024 * 1024 * 1024
/** How often a live reading picks up the newest transcript snapshot. */
private const val LIVE_FEED_MS = 400L

/**
 * The AI notes worker (core/reader), split out of [TranscriptionService]: ONE reader for every
 * meeting, in order.
 *
 * The reader is not bound to a run. Meetings to read (a live recording, a queued talk, an import,
 * a re-summarize) are tasks in one ordered queue; the worker reads ONE meeting to the end — notes,
 * summary, title, library save — before moving to the next, and only an abort cuts it short. A new
 * recording therefore no longer kills the previous meeting's reading: "Next talk" / "Stop & save"
 * hand their final transcript over and the worker finishes it while the next talk is recorded,
 * then catches up on that one with the model still loaded. Below the live-RAM gate the worker
 * never holds the model while a recording runs (it waits, and gives way if one starts).
 */
internal class ReaderWorker(private val host: Host) {
    /** What the worker needs from the service: its context and scope, the status line,
     *  notifications, download progress and the queue's fingerprint. */
    interface Host {
        val context: Context
        val scope: CoroutineScope
        suspend fun emitEvent(e: TranscriptEvent)
        suspend fun emitDownloadStatus(text: String)
        fun updateNotification(text: String)
        fun notifyItemFailed(title: String)
        fun notifySessionReady(title: String)
        fun dlBegin(key: String, totalBytes: Long)
        fun dlEnd(key: String)
        fun reportDownload(gen: Int, key: String, msgRes: Int, frac: Float)
        fun downloadsDone(gen: Int)
        fun transcriptFingerprint(cfg: TranscriptionConfig): String
        fun outputConverter(cfg: TranscriptionConfig): OpenCcConverter?
        fun threads(): Int
        /** The worker ran out of tasks: the service may stop if nothing else runs. */
        fun onReaderIdle()
    }

    private val ctx get() = host.context

    /** What the summary phase produced — captured so the recording pipeline can auto-save the
     *  finished session into the library ([SessionLibrary.attachResults]). */
    data class SummaryResult(
        val title: String?, val summary: String?, val notes: String? = null, val actions: String? = null,
    )

    /** The loaded model, shared by every task's lane (one lane at a time). */
    private class ReaderModel(
        /** The LlmRegistry id it was loaded for: a changed setting must not keep reading with it. */
        val specId: String,
        val llm: studio.primeminutes.core.reader.ReaderLlm,
        val system: String,
        /** Its card: protocol, budgets, sampler and prompts (ReaderLane). */
        val card: studio.primeminutes.core.models.ModelCard,
        private val cancelFn: () -> Unit,
        private val resumeFn: () -> Unit,
        private val closeFn: () -> Unit,
    ) : AutoCloseable {
        fun cancel() = cancelFn()
        /** Before each reading: a cancel stays in force until then. */
        fun resume() = resumeFn()
        override fun close() = closeFn()
    }

    /** One meeting for the worker. A live task is fed snapshots while its talk is recorded and
     *  finalized when capture ends; a static task carries its transcript (or formatted text) up front. */
    inner class ReadTask(
        val gen: Int,
        val converter: OpenCcConverter?,
        val withTitle: Boolean,
        /** Library entry the worker saves the results into (null: the caller takes the result). */
        @Volatile var entry: SessionLibrary.Entry? = null,
        val text: String? = null,
    ) {
        @Volatile var latest: TranscriptEvent.UtteranceSnapshot? = null
        /** The reader's last whole-window state; persisted as it moves once an entry is known. */
        @Volatile var latestCp: ReaderCheckpoint? = null
        /** Live resume: the entry whose saved notes seed the reader, over [seedUtts] (the transcript so far). */
        @Volatile var notesEntry: SessionLibrary.Entry? = null
        @Volatile var seedUtts: List<TranscriptEvent.Utterance>? = null
        @Volatile var resumedNotes = false
        private val cpLock = Any()

        /** Record a window's checkpoint; written to the entry's sidecar once there is one. */
        fun checkpoint(cp: ReaderCheckpoint, llmModelId: String) = synchronized(cpLock) {
            latestCp = cp
            (notesEntry ?: entry)?.let { SessionLibrary.saveNotes(it, llmModelId, cp) }
        }

        /** The live capture just got its library entry: windows already read are persisted now,
         *  not only at the next one (a kill right after Stop must not lose them). */
        fun attachEntry(e: SessionLibrary.Entry) = synchronized(cpLock) {
            entry = e
            latestCp?.let { SessionLibrary.saveNotes(e, readerModelId(), it) }
        }
        val final = CompletableDeferred<List<TranscriptEvent.Utterance>>()
        val result = CompletableDeferred<SummaryResult>()
        @Volatile var aborted = false
        /** Small devices: a recording started — stop now, read again from the start later. */
        @Volatile var preempted = false

        /** Hand over the whole transcript at once (post-hoc reading). */
        fun complete(utterances: List<TranscriptEvent.Utterance>) {
            // A live task keeps its last live snapshot: the reader appends the final transcript's
            // tail after what it already read (index-feeding the re-attributed list would repeat lines).
            if (latest == null) latest = TranscriptEvent.UtteranceSnapshot(utterances, stable = utterances.size)
            final.complete(utterances)
        }
    }

    val readLock = Any()
    val readTasks = ArrayList<ReadTask>()          // guarded by readLock, in reading order
    @Volatile var readCurrent: ReadTask? = null
    @Volatile private var readModel: ReaderModel? = null
    private var readJob: Job? = null                        // guarded by readLock

    fun readerBusy(): Boolean = synchronized(readLock) { readTasks.isNotEmpty() }

    /** Publish the held tasks' generations to [RunRegistry] (call under [readLock], after a change). */
    private fun publishTasks() = RunRegistry.readerTasks(readTasks.map { it.gen })

    /** Queue [task] (a task for an entry already queued or being read is not read twice). */
    fun submitRead(task: ReadTask): ReadTask = synchronized(readLock) {
        val queued = task.entry?.id?.let { id -> readTasks.firstOrNull { it.entry?.id == id } }
        if (queued == null) { readTasks += task; publishTasks() }
        // Whether the task is new or already listed, the loop must be running: a listed task whose
        // loop had ended was returned here and awaited forever ("Process now" did nothing, the row
        // stayed "Queued").
        if (readJob?.isActive != true) {
            readJob = host.scope.launch(Dispatchers.IO) { readLoop() }
        }
        queued ?: task
    }

    /** Abort every task (the service is going away). */
    fun abortAll() = synchronized(readLock) { readTasks.toList() }.forEach(::abortRead)

    /** Abort [task]: dropped if waiting, cut short if being read. Its entry stays as it was. */
    fun abortRead(task: ReadTask) {
        task.aborted = true
        task.final.complete(emptyList())
        // Decided under readLock, where the loop also claims its task: checked outside it, the loop
        // could take the task between the check and the removal and read an aborted meeting.
        val current = synchronized(readLock) { (readCurrent === task).also { if (!it && readTasks.remove(task)) publishTasks() } }
        if (current) readModel?.cancel()
        task.result.completeExceptionally(CancellationException("reading aborted"))
    }

    /** A recording is starting on a device that cannot hold both models: give way. */
    fun readerYieldForRecording() {
        if (liveReaderCapable()) return
        readCurrent?.let { it.preempted = true; readModel?.cancel() }
    }

    private suspend fun readLoop() {
        val me = kotlinx.coroutines.currentCoroutineContext()[Job]
        // The loop hands over under readLock: once it finds no task it stops counting as running
        // (readJob = null) in the same critical section, so a task submitted while it winds down
        // starts a new loop instead of waiting forever on this one (seen: the queue's second item
        // stayed queued with nothing reading it). The model goes with the loop that owned it.
        var model: ReaderModel? = null
        try {
            while (true) {
                val task = synchronized(readLock) {
                    readTasks.firstOrNull() ?: run {
                        if (readJob === me) readJob = null
                        model = readModel; readModel = null
                        null
                    }
                } ?: break
                // Small devices never hold the reader beside a running speech engine: wait for the
                // capture to end, and for a live task, for its transcript to be complete.
                if (!liveReaderCapable() && (RunRegistry.recordingActive || (task.text == null && !task.final.isCompleted))) {
                    // Wait for the capture to end; never hold the reader beside it on small devices.
                    readModel?.close(); readModel = null
                    delay(1000); continue
                }
                val claimed = synchronized(readLock) {
                    if (task.aborted) { readTasks.remove(task); publishTasks(); false } else { readCurrent = task; true }
                }
                if (!claimed) continue
                RunRegistry.readerReading(task.gen)
                val res = runCatching { withContext(RunGen(task.gen)) { readOne(task) } }
                readCurrent = null; RunRegistry.readerReading(null)
                if (task.preempted && !task.aborted) { task.preempted = false; continue }   // read again later
                // Save BEFORE leaving the task list: while it is listed the worker counts as busy, so
                // the service does not tear itself down (cancelling this scope) mid-save — that left a
                // finished meeting unsaved, still queued, and read a second time by the queue.
                withContext(NonCancellable) {
                    res.fold(
                        { r ->
                            // Saved first, then released: whoever awaits the task (the queue drain,
                            // which then clears its row's 'processing' state) must not see it done
                            // during the seconds the .m4a takes to write.
                            runCatching { readDone(task, r) }.onFailure { Log.w("primeminutes-reader", "read done failed", it) }
                            task.result.complete(r)
                        },
                        { e -> task.result.completeExceptionally(e); runCatching { readFailed(task, e) } },
                    )
                }
                synchronized(readLock) { readTasks.remove(task); publishTasks() }
            }
        } finally {
            synchronized(readLock) {
                if (readJob === me) { readJob = null; if (model == null) { model = readModel; readModel = null } }
            }
            model?.close()
            withContext(NonCancellable + Dispatchers.Main) {
                // The run that started this reading may be long over: the worker brings the service
                // down when it is the last thing running.
                if (!readerBusy()) host.onReaderIdle()
            }
        }
    }

    /** Load (downloading first if needed) the reader model. */
    private suspend fun loadReaderModel(): ReaderModel {
        val models = ModelManager(ctx)
        val spec = LlmRegistry.byId(TranscriptionConfig.Holder.config.llmModelId)
        ensureLlm(spec, models)
        val dir = models.llmDir(spec)
        val system = File(dir, spec.systemPromptFile).readText()
        // The mobile graphs on the forked LiteRT engine (integration note §13): CPU only, 4k,
        // the XNNPACK weight cache built on the first load next to the weights.
        val main = File(dir, spec.mainFile)
        val engine = studio.primeminutes.core.llm.MfaEngine.load(
            main.parentFile!!.path, ctx = spec.maxCtx, threads = host.threads(),
            weightCache = File(dir, ModelManager.WEIGHT_CACHE).path, stopIds = spec.template.stopIds,
        )
        val tok = studio.primeminutes.core.llm.SpTokenizer.load(File(dir, spec.tokenizerFile).path)
        val session = studio.primeminutes.core.llm.MfaSession(engine, tok, spec.sampler.topK, spec.sampler.topP, template = spec.template)
        return ReaderModel(
            spec.id, session, system, spec.card,
            cancelFn = session::cancel, resumeFn = session::resume, closeFn = { engine.close(); tok.close() },
        )
    }

    /** Read one meeting to the end (runs under the task's RunGen, so its events reach its session). */
    fun readerModelId() = LlmRegistry.byId(TranscriptionConfig.Holder.config.llmModelId).id

    /** A reading that continued from saved notes and failed is redone from the start: a bad
     *  checkpoint must never cost the summary. */
    private suspend fun readOne(task: ReadTask): SummaryResult {
        try {
            return readOnce(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!task.resumedNotes) throw e
            Log.w("primeminutes-reader", "resumed reading failed, reading from the start", e)
            task.resumedNotes = false
            (task.notesEntry ?: task.entry)?.let(SessionLibrary::clearNotes)
            task.seedUtts = null
            return readOnce(task)
        }
    }

    private suspend fun readOnce(task: ReadTask): SummaryResult {
        val gen = task.gen
        TranscriptionService.events.tryEmit(gen to TranscriptEvent.Agent(AgentEvent.State(AgentState.STARTING)))
        // The model setting changed since this one was loaded (E2B ↔ E4B): load the chosen one.
        val wanted = LlmRegistry.byId(TranscriptionConfig.Holder.config.llmModelId).id
        readModel?.takeIf { it.specId != wanted }?.let { it.close(); readModel = null }
        Log.i("primeminutes-reader", "reader model: wanted=$wanted loaded=${readModel?.specId}")
        val model = readModel ?: loadReaderModel().also { readModel = it }
        model.resume()
        // The notes are model-written Chinese: they follow the same script as the summary.
        val conv = host.outputConverter(TranscriptionConfig.Holder.config)
        val emit: (AgentEvent) -> Unit = { ev ->
            TranscriptionService.events.tryEmit(gen to TranscriptEvent.Agent(if (conv == null) ev else ev.mapText(conv::convert)))
        }
        val lane = ReaderLane(model.llm, model.system, model.card, emit)
        if (task.text == null) {
            // Checkpoint the notes after every window; a saved one lets an interrupted reading (Stop,
            // crash, kill) continue instead of reading the whole transcript again.
            lane.onCheckpoint { cp ->
                task.checkpoint(cp, wanted)
            }
        }
        val saved = if (task.text == null) (task.notesEntry ?: task.entry)?.let { SessionLibrary.loadNotes(it, wanted) } else null
        val base = task.seedUtts ?: task.latest?.utterances
        var resumed = false
        if (saved != null && base != null) {
            Log.i("primeminutes-reader", "resume reader: window=${saved.window} notes=${saved.journal.size} lines=${saved.offered}")
            resumed = lane.resume(saved, base)
            if (!resumed) Log.w("primeminutes-reader", "saved notes do not match the transcript: reading from the start")
        }
        task.resumedNotes = resumed
        if (!resumed) lane.start()
        try {
            if (task.text != null) {
                lane.feedText(task.text)
                return finishLane(lane, emptyList(), task)
            }
            // Live: keep up with the recording until its talk ends (conflated: only the newest
            // snapshot matters, feeding resumes from the stable index).
            while (!task.final.isCompleted) {
                if (task.aborted || task.preempted) throw CancellationException("reading stopped")
                task.latest?.let(lane::feed)
                delay(LIVE_FEED_MS)
            }
            task.latest?.let(lane::feed)
            val fin = task.final.await()
            if (task.aborted || task.preempted) throw CancellationException("reading stopped")
            if (fin.isEmpty()) return SummaryResult(null, null)
            return finishLane(lane, fin, task)
        } finally {
            lane.close()
        }
    }

    /** Final transcript -> reader finish -> summary prose, action items, title (converted), emitted and returned. */
    private suspend fun finishLane(lane: ReaderLane, final: List<TranscriptEvent.Utterance>, task: ReadTask): SummaryResult {
        host.updateNotification(ctx.getString(R.string.svc_summarizing))
        host.emitEvent(TranscriptEvent.Status(ctx.getString(R.string.svc_summarizing)))
        val res = lane.finish(final)
        if (task.aborted || task.preempted) throw CancellationException("reading stopped")
        val conv: (String) -> String = { task.converter?.convert(it) ?: it }
        // The summary is prose written from the notes; the grouped minutes are the fallback.
        val prose = runCatching { lane.prose(res.journal) }
            .onFailure { Log.w("primeminutes-reader", "prose summary failed; using minutes", it) }.getOrNull()
        // No notes at all (a short or off-topic recording): one plain line, not five empty sections.
        val minutes = if (res.journal.isEmpty()) ctx.getString(R.string.summary_no_notes) else conv(prose ?: res.minutes)
        val actions = conv(res.actions.joinToString("\n") { "- ${it.text.trimEnd('。')} [${it.ts}]" }.ifEmpty { "-" })
        host.emitEvent(TranscriptEvent.SummaryComplete(minutes))
        host.emitEvent(TranscriptEvent.ActionItemsComplete(actions))
        val title = if (task.withTitle) runCatching { lane.title(res.journal) }.getOrNull()?.let(conv) else null
        title?.let { host.emitEvent(TranscriptEvent.Title(it)) }
        TranscriptionService.events.emit(task.gen to TranscriptEvent.Agent(AgentEvent.State(AgentState.DONE, notes = res.journal.size)))   // the task's gen, like the lane's own agent events
        // The AI notes themselves are saved with the meeting (one journal line each), so reopening
        // it shows them again with their tap-to-check times; they used to be dropped here.
        val notes = res.journal.takeIf { it.isNotEmpty() }?.joinToString("\n") { conv(ReaderProtocol.render(it)) }
        return SummaryResult(title, minutes, notes, actions)
    }

    /** A task read to the end: save it into its library entry (when it has one). */
    private suspend fun readDone(task: ReadTask, r: SummaryResult) {
        val entry = task.entry ?: run { Log.i("primeminutes-reader", "read done: no library entry (gen ${task.gen})"); return }
        val fin = task.final.getCompleted()
        // A talk with no live speech stays as recorded; the queue's own pass decides about it —
        // unless that pass already ran: its read was merged into this task (one task per entry),
        // so if its own transcript of the audio is empty too, this is the queue's answer. Without
        // this the item stayed queued until the next app start.
        if (fin.isEmpty() && task.gen != TranscriptionService.QUEUE_GEN) {
            val queueSaw = SessionLibrary.loadPendingTranscript(entry, host.transcriptFingerprint(TranscriptionConfig.Holder.config))
            if (queueSaw == null || queueSaw.isNotEmpty()) { Log.i("primeminutes-reader", "read done: ${entry.id} has no transcript"); return }
            Log.i("primeminutes-reader", "read done: ${entry.id} has no speech (confirmed by the queue)")
        }
        if (SessionLibrary.byId(ctx, entry.id) == null) { ProcessingQueue.remove(ctx, entry.id); return }   // deleted meanwhile
        val cfg = TranscriptionConfig.Holder.config
        val updated = SessionLibrary.attachResults(
            ctx, entry, fin, emptyMap(), r.summary, r.actions, r.title, r.notes,
            cfg.asrModelId, cfg.asrBackend, cfg.llmModelId,
        )
        if (updated == null) {
            Log.w("primeminutes-reader", "read done: could not save ${entry.id} (gen ${task.gen})")
            // Could not be written (unreadable/truncated audio, disk full): say so, don't spin on it.
            if (task.gen == TranscriptionService.QUEUE_GEN) {
                host.notifyItemFailed(entry.title ?: SessionLibrary.defaultTitle(ctx, entry.createdAt))
                ProcessingQueue.remove(ctx, entry.id)
            }
            return
        }
        SessionLibrary.clearPendingTranscript(entry)
        ProcessingQueue.remove(ctx, entry.id)
        Log.i("primeminutes-reader", "read done: saved ${entry.id} (gen ${task.gen})")
        studio.primeminutes.core.embed.LibraryIndexer.request(ctx, entry.id)   // semantic index, after the worker is free
        val saved = TranscriptEvent.LibrarySaved(Uri.fromFile(updated.sessionFile).toString(), updated.title)
        TranscriptionService.events.emit(task.gen to saved)
        if (task.gen != TranscriptionService.UNTAGGED) TranscriptionService.events.emit(TranscriptionService.UNTAGGED to saved)   // the library list, whatever is open
        if (task.gen == TranscriptionService.QUEUE_GEN) host.notifySessionReady(updated.title ?: SessionLibrary.defaultTitle(ctx, updated.createdAt))
    }

    private fun readFailed(task: ReadTask, e: Throwable) {
        if (task.aborted) return
        Log.w("primeminutes-reader", "reading failed", e)
        studio.primeminutes.core.hw.HwInfo.reportReadFailure(ctx)
        // The cause rides on the status line (screenshots are what users send with a bug report).
        val why = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(120)
        TranscriptionService.events.tryEmit(task.gen to TranscriptEvent.Status("${ctx.getString(R.string.svc_summary_failed)} [$why]"))
        val entry = task.entry ?: return
        if (task.gen == TranscriptionService.QUEUE_GEN) {
            host.notifyItemFailed(entry.title ?: SessionLibrary.defaultTitle(ctx, entry.createdAt))
            ProcessingQueue.remove(ctx, entry.id)
        }
        // A recording keeps its queued safety-net item: the next drain retries it.
    }

    /**
     * Live mode: ASR + diarization and the reader run at the same time, so the minutes are ready
     * about a minute after the meeting ends (integration note §2). It holds both models resident,
     * so it needs the RAM: below ~8 GB the reader runs after transcription instead (same protocol,
     * faster than real time since nothing waits for speech).
     */
    fun liveReaderCapable(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return mi.totalMem >= LIVE_READER_MIN_RAM && studio.primeminutes.core.hw.HwInfo.liveCapable()
    }

    /** The current run's live reading task: queued now, fed as the transcript grows. */
    suspend fun startLiveReader(): ReadTask =
        submitRead(ReadTask(currentGen(), host.outputConverter(TranscriptionConfig.Holder.config), withTitle = true))
            .also { liveReader = it }

    /** The live reading task of the current run, if any. */
    @Volatile var liveReader: ReadTask? = null

    fun feedLive(live: ReadTask?, snap: TranscriptEvent.UtteranceSnapshot) { live?.latest = snap }

    /** A run ended without handing its talk over: drop its reading. */
    fun closeLive(live: ReadTask?) {
        if (live == null) return
        if (liveReader === live) liveReader = null
        if (!live.final.isCompleted) abortRead(live)
    }

    /** Wait for [task]'s result. A caller that is cancelled aborts a task it owns (no library entry);
     *  a task with an entry is finished by the worker whatever happens to the caller. */
    suspend fun awaitRead(task: ReadTask): SummaryResult = try {
        task.result.await()
    } catch (ce: CancellationException) {
        if (task.entry == null && !task.result.isCompleted) abortRead(task)
        throw ce
    }

    /** Download the reader model if needed (progress → notification/UI, tagged with the run gen). */
    private suspend fun ensureLlm(spec: LlmSpec, models: ModelManager) {
        if (!models.llmReady(spec)) {
            host.emitDownloadStatus(ctx.getString(R.string.svc_downloading_named, spec.displayName))
            val gen = currentGen()
            host.dlBegin("llm", spec.totalBytes)
            try {
                models.ensureLlmModel(spec) { frac -> host.reportDownload(gen, "llm", R.string.svc_summarization_model_pct, frac) }
            } finally { host.dlEnd("llm"); host.downloadsDone(gen) }
        }
        prepareWeights(spec, models)
    }

    /**
     * Build the XNNPACK weight cache once, right after the download and on its own (integration
     * note §13.2): the first load of a mobile graph repacks every weight — seconds for E2B, longer
     * for E4B, with a memory peak — so it is not left to the load that starts the reading. A
     * failed build leaves no partial cache behind.
     */
    private suspend fun prepareWeights(spec: LlmSpec, models: ModelManager) {
        val dir = models.llmDir(spec)
        val cache = File(dir, ModelManager.WEIGHT_CACHE)
        if (cache.exists()) return
        host.emitDownloadStatus(ctx.getString(R.string.svc_preparing_named, spec.displayName))
        try {
            withContext(Dispatchers.Default) {
                studio.primeminutes.core.llm.MfaEngine.load(
                    File(dir, spec.mainFile).parentFile!!.path, ctx = spec.maxCtx, threads = host.threads(),
                    weightCache = cache.path, stopIds = spec.template.stopIds,
                ).close()
            }
        } catch (e: Throwable) {
            cache.delete(); throw e
        } finally { host.downloadsDone(currentGen()) }
    }

    /** Re-summarize an existing transcript (no re-decode / re-ASR). Keeps the existing title unless
     *  asked — a re-run shouldn't churn a title the user likes. The reader produces the
     *  summary, title and action items in the same pass. */
    suspend fun runSummarizeOnly(transcript: String, withTitle: Boolean = false) {
        val cfg = TranscriptionConfig.Holder.config
        if (transcript.isBlank()) { host.emitEvent(TranscriptEvent.ActionItemsComplete("-")); return }
        val task = ReadTask(currentGen(), host.outputConverter(cfg), withTitle, text = transcript)
        task.final.complete(emptyList())
        awaitRead(submitRead(task))
    }
}
