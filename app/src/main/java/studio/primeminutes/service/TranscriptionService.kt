package studio.primeminutes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.annotation.SuppressLint
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import studio.primeminutes.core.asr.LiveAsrEngine
import studio.primeminutes.core.asr.NemoStreamEngine
import studio.primeminutes.core.audio.AudioDecoder
import studio.primeminutes.core.audio.ImportRecovery
import studio.primeminutes.core.config.SummaryScript
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import studio.primeminutes.data.SpeakerName
import studio.primeminutes.MainActivity
import studio.primeminutes.R
import java.io.File

/**
 * Long-running pipeline host. Transcription + diarization + summarization can take
 * minutes on-device, so they run in a foreground service (survives screen-off / app
 * backgrounding) and stream [TranscriptEvent]s out via [events] — the on-device stand-in
 * for Prime Minutes's StreamingResponse. The UI collects [events] instead of reading NDJSON.
 *
 * Memory discipline lives here: run ASR+diarization first and release those models
 * before loading the LLM for summarization (small devices; with ~8 GB the reader runs alongside).
 */
class TranscriptionService : LifecycleService(), ReaderWorker.Host {

    /**
     * Notifications and status texts in the language chosen in Settings — read on every call, so a
     * service that outlives a language switch (a long queue drain) does not keep the old one.
     */
    private var langCode: String? = null
    private var langRes: android.content.res.Resources? = null
    override fun getResources(): android.content.res.Resources {
        val base = baseContext ?: return super.getResources()
        val code = studio.primeminutes.core.config.AppLanguage.load(base)
        if (langRes == null || code != langCode) {
            langRes = studio.primeminutes.core.config.AppLanguage.resourcesFor(base, code)
            langCode = code
        }
        return langRes!!
    }

    companion object {

        private const val CHANNEL_ID = "primeminutes_pipeline"
        private const val NOTIF_ID = 1
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_WITH_TITLE = "with_title"   // ACTION_SUMMARIZE: also regenerate the title
        const val EXTRA_RUN_GEN = "run_gen"         // the UI sessionGen that owns this run (event tagging)
        const val EXTRA_REQUEST = "request"         // the Mailbox ticket of an export / persist / summarize request
        /** ACTION_STOP_RECORDING(_DEFER) from the session screen: queue the capture once it is saved
         *  (the service does it, so it happens even if that screen is gone by then). */
        const val EXTRA_QUEUE_ON_SAVE = "queue_on_save"
        const val ACTION_STOP = "studio.primeminutes.STOP"

        /** How long a Stop waits for the pipeline's teardown (the library save) before stopping the
         *  service regardless. Generous: the save is a file rename, but the coroutine first has to
         *  unwind out of a native ASR call that only checks cancellation between chunks. */
        private const val STOP_DRAIN_MS = 15_000L

        const val ACTION_RECORD = "studio.primeminutes.RECORD"
        const val ACTION_SUMMARIZE = "studio.primeminutes.SUMMARIZE"
        // Gracefully end live recording and continue into diarization/summary (vs ACTION_STOP,
        // which cancels the whole job).
        const val ACTION_STOP_RECORDING = "studio.primeminutes.STOP_RECORDING"
        // "Next talk": gracefully end live recording but DEFER the heavy processing (no diarize,
        // no summary) — the capture is auto-saved to the library as RECORDED and the mic frees up
        // for the next back-to-back session. Processed later via ACTION_PROCESS_QUEUE.
        const val ACTION_STOP_RECORDING_DEFER = "studio.primeminutes.STOP_RECORDING_DEFER"
        // Drain the ProcessingQueue: serially run the full pipeline over each queued library
        // entry and embed the results. Events are tagged QUEUE_GEN so they never touch the UI's
        // current session; progress lives in the notification + the library rows.
        const val ACTION_PROCESS_QUEUE = "studio.primeminutes.PROCESS_QUEUE"
        // Build/write a session .ogg in the foreground service so it completes even if the user
        // leaves/closes the app mid-export (the SAF document is created empty up front; a UI-scoped
        // build that got interrupted left a 0-byte file). Request passed via [pendingExport] —
        // utterances can be large, so it rides an in-memory holder, not Intent extras.
        const val ACTION_EXPORT = "studio.primeminutes.EXPORT"

        // Persist the open session's EDITS back into its library entry (rebuild session.m4a +
        // meta): the review loop (fix speaker names, correct text, re-summarize) otherwise lived
        // only in Compose state and was silently lost on Back — reopening the entry reloaded the
        // stale session.m4a. Request rides [pendingPersist] (transcripts are too big for Intent
        // extras); runs in the foreground service like an export so closing the app can't
        // truncate the file mid-write.
        const val ACTION_PERSIST_LIBRARY = "studio.primeminutes.PERSIST_LIBRARY"

        // Podcast/YouTube import download. Runs here (foreground, notification) rather than in the
        // importer sheet's coroutine scope, so dismissing the sheet can't cancel it. Request rides
        // [studio.primeminutes.online.ImportDownloads.pending]; state is published through its StateFlow.
        const val ACTION_IMPORT_DOWNLOAD = "studio.primeminutes.IMPORT_DOWNLOAD"

        /** Set by the UI's "Resume" just before it re-runs a library capture: the next [ImportPipeline.run]
         *  continues from the entry's saved progress instead of transcribing from zero. One-shot. */
        @Volatile var resumeRequested = false

        /** Set by "finish the interrupted import" just before it re-runs the import copy: the next
         *  [ImportPipeline.run] continues from [ImportRecovery]'s saved progress, if it matches. One-shot. */
        @Volatile var importResumeRequested = false

        // The run flags (live capture, pipeline job, reader, queue item, exports) live in
        // [RunRegistry] now: one observable value the service publishes and the UI re-attaches from.

        /** Pending session exports (ACTION_EXPORT), by ticket. */
        val pendingExport = Mailbox<ExportRequest>()

        /** Pending edits-persists into a library entry (ACTION_PERSIST_LIBRARY), by ticket. */
        val pendingPersist = Mailbox<PersistRequest>()

        // Entries whose edits-persist is queued or running. Rebuilding session.m4a takes seconds
        // (the audio is re-muxed), and an open of the same row meanwhile read the OLD file — then,
        // marked dirty by its own script re-render, wrote that stale state back over the edits
        // (seen: a renamed speaker and an edited line vanished on reopen). Opens wait on this.
        /** Serializes the library-entry rebuilds [runPersist] makes. */
        private val persistLock = kotlinx.coroutines.sync.Mutex()
        private val persistInFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
        fun persistQueued(entryId: String) { persistInFlight.putIfAbsent(entryId, kotlinx.coroutines.CompletableDeferred()) }
        private fun persistDone(entryId: String) { persistInFlight.remove(entryId)?.complete(Unit) }
        /** Suspends until no edits-persist is in flight for [entryId] (bounded: a service that never ran must not hang an open). */
        suspend fun awaitPersist(entryId: String) {
            persistInFlight[entryId]?.let { kotlinx.coroutines.withTimeoutOrNull(120_000) { it.await() } }
        }

        /** ACTION_SUMMARIZE's request, by ticket. Rides a holder rather than an Intent extra: a
         *  long meeting's transcript exceeds the ~1 MB Binder transaction limit →
         *  TransactionTooLargeException crash. Consumed in onStartCommand. */
        val pendingSummarize = Mailbox<SummarizeRequest>()


        // Process-wide event bus the UI subscribes to. replay=0: UI must be collecting. Each event is
        // tagged with the run generation (the UI's sessionGen, via EXTRA_RUN_GEN) so the collector can
        // drop a superseded run's still-buffered events instead of letting them mutate the new session.
        // UNTAGGED (-1) = not from a gen'd pipeline job (e.g. export) → always accepted.
        // The bus itself lives in RunRegistry, which also keeps each run's events for a screen that
        // attaches mid-run; this keeps the (gen to event) API the pipeline and the tests use.
        val events = EventSink
        const val UNTAGGED = -1

        // Queue-drain runs are tagged with this generation: never equal to UNTAGGED (always
        // accepted) nor to any UI sessionGen (starts at 0, only increments), so the UI collector
        // routes queue events to the Studio list's per-row progress instead of the open session.
        const val QUEUE_GEN = -2

    }

    /** Where the service's events go: [RunRegistry.events], numbered and kept per run. */
    object EventSink {
        fun tryEmit(e: Pair<Int, TranscriptEvent>): Boolean = RunRegistry.tryEmit(e.first, e.second)
        suspend fun emit(e: Pair<Int, TranscriptEvent>) = RunRegistry.emit(e.first, e.second)
    }

    /** A pending session export, handed to the service via [pendingExport] (utterances can be large,
     *  so it rides an in-memory holder rather than Intent extras). */
    /** The open session's current state, to be written back into its library entry. */
    data class PersistRequest(
        val entryId: String,
        val audioUri: Uri?,
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val summary: String?,
        val actionItems: String?,
        val title: String?,
        val notes: String?,
        val asrModelId: String?,
        val asrBackend: String?,
        val llmModelId: String?,
    )

    /**
     * Re-summarize [text] (the formatted transcript). [save]: the library entry the result belongs
     * to, as it stood when the re-summarize started — the service writes the new summary, notes,
     * actions and title into it when the reading ends, so the result is kept even if the screen
     * that asked is gone by then. It is also what a screen opened mid-run shows.
     */
    data class SummarizeRequest(val text: String, val save: PersistRequest? = null)

    data class ExportRequest(
        val share: Boolean,           // true → build to cache for sharing; false → write to [saveUri]
        val saveUri: Uri?,
        val audioUri: Uri?,
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val summary: String?,
        val actionItems: String?,
        val title: String?,
        val notes: String?,
        val asrModelId: String?,
        val asrBackend: String?,
        val llmModelId: String?,
        val coverEnabled: Boolean,
        val fileName: String,
        // No OGG default: it needs an Opus encoder many devices lack. Callers state the format.
        val format: PrimeMinutesSession.Format,
    )

    private var pipelineJob: Job? = null

    /** The AI notes worker: one reader for every meeting, in order. */
    private val reader = ReaderWorker(this)

    /** What the pipelines see of the service (see [PipelineHost]). */
    private val pipelineHost = object : PipelineHost {
        override val context: Context get() = this@TranscriptionService
        override val scope: kotlinx.coroutines.CoroutineScope get() = lifecycleScope
        override val reader: ReaderWorker get() = this@TranscriptionService.reader
        override suspend fun emitEvent(e: TranscriptEvent) = this@TranscriptionService.emitEvent(e)
        override fun updateNotification(text: String) = this@TranscriptionService.updateNotification(text)
        override fun startForegroundTyped(recording: Boolean, text: String) =
            this@TranscriptionService.startForegroundTyped(recording, text)
        override fun transcriptFingerprint(cfg: TranscriptionConfig) = this@TranscriptionService.transcriptFingerprint(cfg)
        override fun outputConverter(cfg: TranscriptionConfig) = this@TranscriptionService.outputConverter(cfg)
        override fun transcriptConverter() = this@TranscriptionService.transcriptConverter()
        override suspend fun ensureEngineModels(models: ModelManager) = this@TranscriptionService.ensureEngineModels(models)
        override fun createEngine(models: ModelManager) = this@TranscriptionService.createEngine(models)
        override fun probeDurationSec(uri: Uri) = this@TranscriptionService.probeDurationSec(uri)
        override fun notifyItemFailed(title: String) = this@TranscriptionService.notifyItemFailed(title)
    }

    /** Live capture → transcript → hand-over to the reader; owns the graceful-stop flags. */
    private val recordingPipeline = RecordingPipeline(pipelineHost)

    /** File import → transcript → notes; owns the in-flight import the stop handler banks. */
    private val importPipeline = ImportPipeline(pipelineHost)

    /** The processing-queue drain: pass 1 through [importPipeline], pass 2 through the reader. */
    private val queueRunner = QueueRunner(pipelineHost, importPipeline)
    override val context: Context get() = this
    override val scope: kotlinx.coroutines.CoroutineScope get() = lifecycleScope
    override fun threads(): Int = asrThreads()
    override fun onReaderIdle() {
        // The run that started this reading may be long over: the worker brings the service
        // down when it is the last thing running.
        if (pipelineJob?.isActive != true && activeExports == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    /** Emit a UI event stamped with the current coroutine's run generation ([UNTAGGED] outside a job). */
    override suspend fun emitEvent(e: TranscriptEvent) {
        if (e is TranscriptEvent.Status) phaseStatus = e.message
        // The notification follows the work (it stayed on "Preparing…" through a whole import with the
        // screen off), with the percentage; a download in flight keeps its own line.
        if (!notifRecording && downloads.isIdle()) when (e) {
            is TranscriptEvent.Status -> { lastNotifPct = -1; updateNotification(e.message) }
            is TranscriptEvent.Progress -> {
                val pct = (e.fraction * 100).toInt().coerceIn(0, 100)
                val base = phaseStatus
                if (pct != lastNotifPct && base != null && pct > 0) {
                    lastNotifPct = pct; updateNotification("$base $pct%")
                }
            }
            else -> {}
        }
        events.emit((kotlin.coroutines.coroutineContext[RunGen]?.gen ?: UNTAGGED) to e)
    }

    /** The last status of the actual work (transcribing, summarizing…), never a download's. */
    @Volatile private var phaseStatus: String? = null
    @Volatile private var lastNotifPct = -1

    /** A download's own status line; it must not become the one we return to afterwards. */
    override suspend fun emitDownloadStatus(text: String) {
        events.emit(currentGen() to TranscriptEvent.Status(text))
    }

    /**
     * After the last download of a burst: give the status line (and the notification) back to the
     * work that is going on. Without it "Summary model… 100 %" stayed up while the app was already
     * transcribing and summarizing — nothing else re-emits a status in that phase.
     */
    override fun downloadsDone(gen: Int) {
        if (!downloads.isIdle()) return
        val text = phaseStatus ?: getString(R.string.svc_processing)
        updateNotification(text)
        events.tryEmit(gen to TranscriptEvent.Status(text))
    }

    // Whether the current foreground notification should show the "Finish recording" action.
    @Volatile private var notifRecording = false
    // Last text shown on the foreground notification, so an early-return that must re-assert
    // startForeground() (see satisfyForegroundContract) can do so without clobbering a running
    // job's live progress text.
    @Volatile private var notifText = ""
    // Last reported download percent, so reportDownload() throttles to integer-percent changes.
    @Volatile private var lastDlPct = -1

    // Held for the lifetime of the foreground service (acquired at every startForegroundTyped, released
    // in onDestroy). A foreground service keeps the PROCESS alive but does NOT keep the CPU awake with
    // the screen off — on battery-aggressive OEMs the SoC dozes and the CPU-bound ASR/LLM work stalls.
    // A partial wake lock keeps the CPU running; the screen is free to turn off. No timeout: onDestroy
    // always runs when the started service calls stopSelf(), and if the process is killed the OS
    // reclaims the lock anyway — so it can't leak, and a long ASR phase can't hit a timeout mid-run.
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val wl = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "primeminutes:pipeline")
            .also { it.setReferenceCounted(false); wakeLock = it }
        if (!wl.isHeld) wl.acquire()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        studio.primeminutes.core.hw.MemoryPressure.onTrim(level)
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /**
     * Android 15+ (API 35) hard-caps a dataSync foreground service at ~6 h/day: when it elapses the
     * system calls this, and NOT calling stopSelf here escalates to an ANR-style kill mid-run. A
     * multi-hour batch drain (or one enormous import) can reach it. Wind down gracefully like
     * ACTION_STOP — cancel the LLM + pipeline job — but keep any remaining QUEUE items intact so the
     * cold-start kick resumes them next time the app opens, and post a dismissible notification so a
     * backgrounded user learns processing paused (and how to resume). Recording (microphone type) is
     * far below the cap, so this realistically only trims a very long processing session.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        reader.abortAll()
        pipelineJob?.cancel()
        notifyPaused()
        RunRegistry.pipelineEnded()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    /** Dismissible heads-up that a long run was paused by the system's FGS time limit. */
    private fun notifyPaused() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Prime Minutes pipeline", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID + 3,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.svc_paused_time_limit))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Surface a model-download fraction to BOTH the notification AND the UI (a [TranscriptEvent.DownloadProgress]
     * that drives the same progress bar + status). Throttled to whole-percent changes; uses tryEmit because
     * the download callback is not a suspend context and the events buffer is bounded. [msgRes] takes one %d.
     */
    // Downloads can overlap (the speech engine's and the reader's start together on 8 GB phones):
    // one combined bar, see [DownloadAggregate].
    private val downloads = studio.primeminutes.core.models.DownloadAggregate()
    override fun dlBegin(key: String, totalBytes: Long) { downloads.begin(key, totalBytes); lastDlPct = -1 }
    override fun dlEnd(key: String) = downloads.end(key)

    override fun reportDownload(gen: Int, key: String, msgRes: Int, frac: Float) {
        val (overall, shared) = downloads.update(key, frac)
        val pct = (overall * 100).toInt().coerceIn(0, 100)
        if (pct == lastDlPct) return
        lastDlPct = pct
        // One line for several downloads, the specific one when it is alone.
        val text = getString(if (shared) R.string.svc_downloading_models_pct else msgRes, pct)
        updateNotification(text)
        // Tagged with the run's gen (callers capture it via currentGen()): QUEUE_GEN downloads
        // drive the Studio row's bar/label, and a superseded run's late events get dropped.
        events.tryEmit(gen to TranscriptEvent.DownloadProgress(overall.coerceIn(0f, 1f), text))
    }

    /** Total media duration in seconds via a cheap metadata read; 0 if unknown/unreadable. */
    private fun probeDurationSec(uri: Uri): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (t: Throwable) { 0.0 } finally { runCatching { mmr.release() } }
    }

    // startId of the most recent start command (main-written). Every teardown must use
    // stopSelf(lastStartId), never stopSelf(): AMS ignores a startId-stop when a NEWER start has
    // already been accepted, whereas the no-arg form stops unconditionally — it could bring the
    // service down under a start that was accepted but not yet delivered, killing the fresh run.
    private var lastStartId = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId

        when (intent?.action) {
            ACTION_STOP -> {
                // Stop in a session aborts that session's own reading (a re-summarize, an import);
                // a background queue item or a finished recording being read keeps going.
                reader.readCurrent?.takeIf { it.entry == null }?.let(reader::abortRead)
                // CANCEL, THEN WAIT, THEN STOP. `cancel()` only REQUESTS cancellation and returns
                // immediately; calling stopSelf() straight after destroyed the service — and with it
                // lifecycleScope — before the cancelled pipeline coroutine was ever scheduled again,
                // so its `finally` blocks never ran. That is why aborting an import lost the audio
                // even after the promote was moved into a finally and wrapped in NonCancellable:
                // the continuation holding them never executed at all. Measured on a Boox against
                // v0.40.2, which logged nothing on abort while logging correctly on completion.
                //
                // The timeout is the safety valve: a native ASR/decode call that does not observe
                // cancellation promptly must not wedge the service, so stop anyway after it.
                val job = pipelineJob
                Log.i(STOP_TAG, "ACTION_STOP received, job=${job != null} active=${job?.isActive}")
                // SAVE BEFORE CANCELLING. The pipeline coroutine cannot be counted on to unwind —
                // see InFlightImport — so the audio is banked here, synchronously, while the service
                // is still alive. The decoded WAV is already on disk and its writer is flushed by
                // AudioDecoder's `use`, so what is saved is every second transcribed so far.
                importPipeline.inFlight.bankOnStop(reader::readerModelId)
                lifecycleScope.launch {
                    val t0 = System.currentTimeMillis()
                    val drained = runCatching { withTimeout(STOP_DRAIN_MS) { job?.cancelAndJoin() } }
                    val ms = System.currentTimeMillis() - t0
                    // The number that decides the design: if this reports a TIMEOUT, the pipeline
                    // coroutine never unwound, so no amount of finally/NonCancellable in it can save
                    // anything and the save must move into this handler instead.
                    Log.i(STOP_TAG, "drain finished in ${ms}ms drained=${drained.isSuccess} " +
                        "err=${drained.exceptionOrNull()?.let { it::class.simpleName }} " +
                        "jobActive=${job?.isActive} jobCompleted=${job?.isCompleted}")
                    // The AI notes worker may still be finishing an earlier meeting: it stops the
                    // service itself when done.
                    if (!reader.readerBusy()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                        Log.i(STOP_TAG, "service stopped")
                    }
                }
                return START_NOT_STICKY
            }
            // End recording but let the job carry on into diarization/summary; "Next talk"
            // (DEFER): end recording, auto-save, skip processing — the mic frees up fast. The
            // request is published, so a screen opened while the capture winds down shows it.
            ACTION_STOP_RECORDING, ACTION_STOP_RECORDING_DEFER -> {
                val defer = intent.action == ACTION_STOP_RECORDING_DEFER
                recordingPipeline.requestStop(defer, queueOnSave = intent.getBooleanExtra(EXTRA_QUEUE_ON_SAVE, false))
                RunRegistry.captureStopping(nextTalk = defer)
                return START_NOT_STICKY
            }
            ACTION_EXPORT -> {
                runExport(pendingExport.take(intent))
                return START_NOT_STICKY
            }
            ACTION_IMPORT_DOWNLOAD -> {
                runImportDownload()
                return START_NOT_STICKY
            }
            ACTION_PERSIST_LIBRARY -> {
                runPersist(pendingPersist.take(intent))
                return START_NOT_STICKY
            }
        }

        val recording = intent?.action == ACTION_RECORD
        val summarizeOnly = intent?.action == ACTION_SUMMARIZE
        val processQueue = intent?.action == ACTION_PROCESS_QUEUE
        // A drain is already running → the new ids just enqueued will be picked up by its loop;
        // restarting would cancel and redo the item currently in progress. And recordings are
        // SACRED: a queue kick (a Stop&save auto-process coroutine resuming late, or "Process
        // pending" tapped while a backgrounded capture runs) must never supersede a live mic
        // capture — the queue auto-resumes after the recording via the post-run resume below.
        // Either way this request still arrived via startForegroundService(), so we MUST call
        // startForeground() before returning or Android kills the process (RemoteServiceException).
        // Both flags are written on MAIN (here and in the teardown's main hop), so this main-thread
        // guard cannot race them.
        // Only while that run is really alive: a stopped run whose job never finished unwinding left
        // these flags up, and every later "Process now" was ignored — the row stayed "Queued".
        if (processQueue && (RunRegistry.queueDraining || RunRegistry.recordingJobActive) && pipelineJob?.isActive == true) {
            satisfyForegroundContract()
            return START_NOT_STICKY
        }
        recordingPipeline.resetStopFlags()
        val previousJob = pipelineJob
        // The reader is no longer superseded with the run: it finishes its meeting. Only a device too
        // small for both models makes it give way to a recording.
        if (recording) reader.readerYieldForRecording()
        // Main-owned run-type flags for the guard above (and the UI's recovery check): a new start
        // of ANY kind supersedes whatever ran before, so overwrite rather than accumulate.
        RunRegistry.runStarting(recording = recording, queue = processQueue)
        // Snapshot the config on MAIN at start time: a drain re-loads the persisted config into the
        // shared Holder from its own thread, and without a per-run snapshot it could clobber the
        // config the UI just staged for this run. The job re-asserts the snapshot after the join.
        val cfgSnapshot = TranscriptionConfig.Holder.config
        startForegroundTyped(recording, getString(R.string.svc_preparing))
        val uri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        // The re-summarize request rides pendingSummarize (Binder-limit safe), under this intent's ticket.
        val summarizeReq = if (summarizeOnly) pendingSummarize.take(intent) else null
        val transcript = summarizeReq?.text ?: intent?.getStringExtra(EXTRA_TRANSCRIPT)
        val summarizeWithTitle = intent?.getBooleanExtra(EXTRA_WITH_TITLE, false) ?: false
        // Queue drains are tagged QUEUE_GEN so their events never reach the UI's open session.
        val runGen = if (processQueue) QUEUE_GEN else intent?.getIntExtra(EXTRA_RUN_GEN, UNTAGGED) ?: UNTAGGED
        // Run the whole pipeline off the main thread — the MediaCodec decode is a long
        // blocking call that would otherwise ANR the UI (lifecycleScope defaults to Main). RunGen tags
        // every event this job emits with the owning session generation.
        var job: Job? = null
        job = lifecycleScope.launch(Dispatchers.Default + RunGen(runGen)) {
            // SERIALIZE with the superseded job: wait for its unwinding (cancellation lands only at
            // a suspension point — under a native ASR/LLM loop that is seconds away) to fully
            // finish, finally blocks included, before doing ANY work. Those finallys otherwise run
            // concurrently with this job and trash its resources: the drain's per-item temp cleanup
            // deleted the WAV a superseding recording was actively writing (silently lost talk), a
            // superseded recording's finally cleared the NEW recording's crash-recovery marker and
            // recordingActive, its `activeReader = null` deregistered the new run's engine (Stop
            // stopped working), and two multi-GB GGUF models resident at once invited a low-memory
            // process kill. join() is cancellable: if THIS job is itself superseded while waiting,
            // it unwinds normally.
            previousJob?.let { runCatching { it.join() } }
            // Re-assert this run's config after the join (a dying drain may have overwritten the
            // shared Holder); a drain loads its own persisted config inside QueueRunner.run.
            if (!processQueue) TranscriptionConfig.Holder.config = cfgSnapshot
            runCatching {
                when {
                    summarizeOnly -> {
                        val r = reader.runSummarizeOnly(transcript.orEmpty(), summarizeWithTitle)
                        // Read to the end (a Stop or a superseding run throws before this): write it
                        // into its entry here, not only from the screen, which may be gone by now.
                        val save = summarizeReq?.save
                        if (save != null && r.summary != null) withContext(NonCancellable + Dispatchers.Main) {
                            persistQueued(save.entryId)
                            runPersist(save.copy(
                                summary = r.summary, actionItems = r.actions ?: save.actionItems,
                                title = r.title ?: save.title, notes = r.notes,
                            ))
                        }
                    }
                    processQueue -> queueRunner.run()
                    recording -> recordingPipeline.run()
                    else -> importPipeline.run(uri)
                }
            }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        emitEvent(TranscriptEvent.Failed(e.message ?: "pipeline error"))
                    }
                }
            // A run that ended early (stop, failure, cancellation during ASR) must not leave the
            // live reader's multi-GB model resident. Normal ends already closed it.
            reader.closeLive(reader.liveReader)
            // A pending queue resumes after the run that blocked it — a drain superseded by a
            // recording/import, or items enqueued DURING a foreground import (the UI defers the
            // drain start to protect the unsaved import; without this resume those rows would sit
            // "Queued" forever). Never after a plain queue start — that IS the drain. Enqueued
            // means the user asked: ⏭ deferral enqueues nothing, so back-to-back recording days
            // stay processing-free until the user asks. A user-cancelled job (Stop) skips this
            // naturally: QueueRunner.run aborts at its first suspension inside the cancelled coroutine.
            //
            // BOTH end-of-job decisions below hop to the MAIN thread (NonCancellable: they must
            // also run for a superseded/cancelled job): onStartCommand runs on main, so checking
            // pipelineJob and calling stopSelf() from this Default-dispatcher thread RACED it —
            // the check could read the stale old job, pass, and stopSelf() would then destroy the
            // service AFTER a new run (the ⏭ next-talk ACTION_RECORD) had already started on it.
            // lifecycleScope died with the service and silently cancelled the new recording's job
            // mid-model-load: mic never captured, no RecordingSaved, no Failed (cancellation is
            // deliberately not reported) — the Capture screen waited forever and the talk was
            // LOST. Serializing on main makes check-then-stop atomic w.r.t. new starts.
            val resumeQueue = withContext(NonCancellable + Dispatchers.Main) {
                val r = !processQueue && pipelineJob === job && ProcessingQueue.size(this@TranscriptionService) > 0
                // The resumed drain must be guarded like a plain one — flag flips on MAIN.
                if (r) RunRegistry.queueResumed()
                r
            }
            if (resumeQueue) {
                runCatching { withContext(RunGen(QUEUE_GEN)) { queueRunner.run() } }
            }
            // Only tear down if still the active job — a newer run may have superseded this one.
            withContext(NonCancellable + Dispatchers.Main) {
                if (pipelineJob === job) {
                    RunRegistry.pipelineEnded()
                    // Leave the service (and its foreground notification) up for an in-flight
                    // export — the last export's own tail stops it. stopSelf(lastStartId): a stop
                    // must never bring the service down under a newer, already-accepted start.
                    // …and for the AI notes worker, which outlives runs: it stops the service itself.
                    if (activeExports == 0 && !reader.readerBusy()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }
        RunRegistry.pipelineStarted(RunRegistry.Run(
            kind = when {
                recording -> RunRegistry.Kind.RECORDING
                summarizeOnly -> RunRegistry.Kind.SUMMARIZE
                processQueue -> RunRegistry.Kind.QUEUE
                else -> RunRegistry.Kind.IMPORT
            },
            gen = runGen, audioUri = uri ?: summarizeReq?.save?.audioUri?.toString(),
            startedAtMs = System.currentTimeMillis(), resummary = summarizeReq?.save, resummaryTitle = summarizeWithTitle,
        ))
        pipelineJob = job
        // Now that the new job is the active one, supersede any in-flight run (e.g. Re-summarize
        // while the first summary is still streaming). Done after the reassignment so the old job's
        // teardown sees it is no longer current and leaves the new run's foreground alone.
        previousJob?.cancel()
        return START_NOT_STICKY
    }

    /**
     * Build + write a session .ogg here in the foreground service, so leaving/closing the app
     * (or Android killing it under model-memory pressure) can no longer truncate the SAF document
     * to 0 bytes. Emits [TranscriptEvent.ExportDone] for the UI (snackbar / share chooser) and a
     * result notification so a backgrounded user still sees the outcome. Runs independently of the
     * transcription [pipelineJob] (shared foreground notification; common case is export-when-idle).
     */
    // In-flight export jobs (main-confined: incremented here on main, decremented in each export's
    // main teardown hop). Exports run OUTSIDE pipelineJob, so both teardowns must consult BOTH: a
    // pipeline finishing must not stopSelf under a live export, and one export must not stop the
    // service under another.
    private var activeExports = 0
        set(v) { field = v; RunRegistry.exports(v) }

    private fun runImportDownload() {
        val req = studio.primeminutes.online.ImportDownloads.pending.also { studio.primeminutes.online.ImportDownloads.pending = null }
        if (req == null) {
            satisfyForegroundContract()
            if (pipelineJob?.isActive != true && activeExports == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStartId)
            }
            return
        }
        val dl = studio.primeminutes.online.ImportDownloads
        startForegroundTyped(recording = notifRecording, getString(R.string.dl_downloading))
        activeExports++
        dl.job = lifecycleScope.launch(Dispatchers.IO) {
            var stageRes = R.string.dl_resolving
            var lastPct = -1
            val result = runCatching {
                req.work(
                    { res -> stageRes = res; dl.set(studio.primeminutes.online.ImportDownloads.State.Running(req.title, res, null)) },
                    { p ->
                        val pct = (p * 100).toInt()
                        if (pct != lastPct) {   // whole-percent throttle, like reportDownload
                            lastPct = pct
                            dl.set(studio.primeminutes.online.ImportDownloads.State.Running(req.title, stageRes, p))
                            updateNotification("${getString(stageRes)} $pct %")
                        }
                    },
                )
            }
            val e = result.exceptionOrNull()
            dl.set(
                when {
                    e is kotlinx.coroutines.CancellationException -> studio.primeminutes.online.ImportDownloads.State.Idle
                    e != null -> studio.primeminutes.online.ImportDownloads.State.Failed(
                        e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.youtube_fetch_failed))
                    else -> result.getOrThrow().let { (uri, t) -> studio.primeminutes.online.ImportDownloads.State.Ready(uri, t) }
                },
            )
            withContext(NonCancellable + Dispatchers.Main) {
                activeExports--
                if (pipelineJob?.isActive != true && activeExports == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
    }

    /**
     * Write the open session's edits back into its library entry (rebuild session.m4a + meta via
     * [SessionLibrary.attachResults]). Counted in [activeExports] and shaped like [runExport]: an
     * out-of-pipeline IO job under the shared foreground, so quitting the app can't truncate the
     * session file mid-write. Emits [TranscriptEvent.LibrarySaved] so the Studio list refreshes.
     */
    private fun runPersist(req: PersistRequest?) {
        if (req == null) {
            satisfyForegroundContract()
            if (pipelineJob?.isActive != true && activeExports == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStartId)
            }
            return
        }
        startForegroundTyped(recording = notifRecording, getString(R.string.exporting))
        activeExports++
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // Entry may have been deleted while the session was open — attachResults would
                // mkdirs() the dir back and resurrect a ghost row, so bail if it is gone.
                val entry = SessionLibrary.byId(this@TranscriptionService, req.entryId) ?: return@runCatching
                // Prefer the raw capture; older entries have it pruned, so fall back to the open
                // session's audio (safe even when that IS the entry's session.m4a: buildSession
                // decodes the input to a temp before it overwrites the output).
                val audio = if (entry.wavFile.exists()) Uri.fromFile(entry.wavFile) else req.audioUri
                // One rebuild of an entry at a time: a finished re-summarize's own save and the
                // screen's edits-persist can land together.
                val updated = persistLock.withLock { SessionLibrary.attachResults(
                    this@TranscriptionService, entry, req.utterances, req.speakerNames,
                    req.summary, req.actionItems, req.title, req.notes, req.asrModelId, req.asrBackend, req.llmModelId,
                    audio = audio,
                ) }
                if (updated != null) {
                    emitEvent(TranscriptEvent.LibrarySaved(Uri.fromFile(updated.sessionFile).toString(), updated.title))
                    studio.primeminutes.core.embed.LibraryIndexer.request(this@TranscriptionService, updated.id)
                }
            }
            withContext(NonCancellable + Dispatchers.Main) {
                persistDone(req.entryId)
                activeExports--
                if (pipelineJob?.isActive != true && activeExports == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
    }

    private fun runExport(req: ExportRequest?) {
        if (req == null) {
            // The request was already consumed (a rare double ACTION_EXPORT dispatch). This still
            // arrived via startForegroundService(), so satisfy the foreground contract, then stop
            // the service only if nothing else is running (don't kill a live job or export).
            satisfyForegroundContract()
            if (pipelineJob?.isActive != true && activeExports == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStartId)
            }
            return
        }
        // Preserve the current foreground-service TYPE: an export during a live recording must not
        // swap MICROPHONE for DATA_SYNC — Android would cut the mic once the app backgrounds,
        // silencing the rest of the capture.
        startForegroundTyped(recording = notifRecording, getString(R.string.exporting))
        activeExports++
        lifecycleScope.launch(Dispatchers.IO) {
            val done = runCatching {
                if (req.share) {
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val built = PrimeMinutesSession.buildSession(
                        this@TranscriptionService, dir, req.audioUri, req.utterances, req.speakerNames,
                        req.summary, req.actionItems, req.title, req.notes, req.asrModelId, req.asrBackend,
                        req.llmModelId, req.coverEnabled, req.fileName, req.format,
                    )
                    if (built != null)
                        TranscriptEvent.ExportDone(true, if (built.transcriptEmbedded) "FULL" else "PARTIAL", built.file.absolutePath)
                    else TranscriptEvent.ExportDone(true, "FAILED")
                } else {
                    val outcome = req.saveUri?.let { uri ->
                        // "wt" = write + TRUNCATE: overwriting an existing (possibly larger) session file
                        // must not leave trailing bytes from the old content. Plain "w" doesn't truncate.
                        contentResolver.openOutputStream(uri, "wt")?.let { os ->
                            PrimeMinutesSession.save(
                                this@TranscriptionService, os, req.audioUri, req.utterances, req.speakerNames,
                                req.summary, req.actionItems, req.title, req.notes, req.asrModelId, req.asrBackend,
                                req.llmModelId, req.coverEnabled, req.format,
                            )
                        }
                    } ?: PrimeMinutesSession.SaveOutcome.FAILED
                    TranscriptEvent.ExportDone(false, outcome.name)
                }
            }.getOrElse {
                // On cancellation (e.g. the pipeline's teardown stopSelf, or ACTION_STOP, while an
                // export is in flight) still deliver a terminal event so the UI's "exporting" overlay
                // clears — emitted under NonCancellable since a cancelled coroutine's plain emit isn't
                // guaranteed — then propagate the cancellation.
                if (it is CancellationException) {
                    withContext(NonCancellable) {
                        val failed = TranscriptEvent.ExportDone(req.share, "FAILED")
                        emitEvent(failed)
                        notifyExportResult(failed)
                    }
                    throw it
                }
                Log.w("primeminutes-export", "export failed", it)
                TranscriptEvent.ExportDone(req.share, "FAILED", reason = it.message ?: it.javaClass.simpleName)
            }
            emitEvent(done)
            notifyExportResult(done)
            // Leave a running transcription's foreground intact; otherwise we're done. On MAIN
            // (like the pipeline teardown): checking from this IO thread raced onStartCommand —
            // an export finishing exactly as a new run starts could stopSelf() the service under
            // that run's freshly-launched job. Also count ourselves out first, so concurrent
            // exports don't stop the service under each other; stopSelf(lastStartId) so a stop
            // can never land under a newer, already-accepted start.
            withContext(NonCancellable + Dispatchers.Main) {
                activeExports--
                if (pipelineJob?.isActive != true && activeExports == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
    }

    /** A dismissable "session ready" notification for background queue completions — the LLM's
     *  recognized title is the payload, so the user learns what finished without opening the app. */
    /** Dismissible notification that a background queue item failed to process (its capture stays
     *  RECORDED for a manual retry) — so the failure isn't silent when the user is elsewhere. */
    override fun notifyItemFailed(title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Prime Minutes pipeline", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID + 4,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.svc_queue_item_failed, title))
                .setContentText(getString(R.string.svc_queue_item_failed_hint))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun notifySessionReady(title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Prime Minutes pipeline", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID + 2,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_session_ready))
                .setContentText(title.ifBlank { getString(R.string.app_name) })
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A dismissable result notification (separate id from the foreground one) so a user who left
     *  the app still learns the save finished. Share fires an in-app chooser, so it needs none. */
    private fun notifyExportResult(done: TranscriptEvent.ExportDone) {
        if (done.share) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Prime Minutes pipeline", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val text = if (done.outcome == "FAILED") getString(R.string.session_save_failed) else getString(R.string.session_saved)
        nm.notify(
            NOTIF_ID + 1,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_primeminutes)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Download the engine's two GGUFs when missing (progress on the current run's bar). */
    private suspend fun ensureEngineModels(models: ModelManager) {
        // Ready → ensureAsrModels only reclaims retired engines' files (cheap, no download).
        if (models.asrReady()) { models.ensureAsrModels { }; return }
        emitDownloadStatus(getString(R.string.svc_downloading_models))
        val gen = currentGen()
        dlBegin("asr", models.asrDownloadBytes())
        try {
            models.ensureAsrModels { frac -> reportDownload(gen, "asr", R.string.svc_downloading_models_pct, frac) }
        } finally { dlEnd("asr"); downloadsDone(gen) }
    }

    /** Anything transcription-affecting: a pending-transcript sidecar written under other settings
     *  is ignored (re-transcribed). Shared by the queue and the live capture that pre-fills it. */
    override fun transcriptFingerprint(cfg: TranscriptionConfig): String =
        listOf(cfg.asrBackend, cfg.asrModelId, cfg.summaryScript).joinToString("|")

    /** The streaming ASR + diarization engine (nemo-x-asr-diarizer). */
    private fun createEngine(models: ModelManager): LiveAsrEngine {
        // audio.cpp caches converted GGUF tensors under std::filesystem::temp_directory_path(),
        // which is $TMPDIR or /data/local/tmp — writable on some phones (OPPO, the emulator) but
        // not on others (Galaxy Note 10+, Android 12: "create_directories: Permission denied"),
        // where the engine never loaded and nothing was transcribed. Point it at our own cache.
        runCatching { android.system.Os.setenv("TMPDIR", cacheDir.absolutePath, true) }
        val lit = models.asrLitertFiles() ?: error("LiteRT ASR graphs missing")
        return NemoStreamEngine(models.asrFiles(), asrThreads(), TranscriptionConfig.Holder.config.speakerDelaySec,
            backend = "litert_host", xasrTflite = lit.xasr, diarTflite = lit.diar, diarPreF32 = lit.preEncode)
    }

    // ---- AI notes worker: ReaderWorker (one reader for every meeting, in order) ----------------

    /**
     * The single OpenCC converter applied to ALL output text — transcript, summary, title, action items,
     * and (in MainActivity) detected speaker names — so everything stays in one consistent script. The
     * target script comes from the Chinese-script setting ([SummaryScript.scriptFor]):
     * Traditional → s2tw, Simplified → t2s, otherwise null (skip). Built once per script and cached.
     */
    override fun outputConverter(cfg: TranscriptionConfig): OpenCcConverter? =
        SummaryScript.scriptFor(cfg.summaryScript, this)?.let { OpenCcConverter.get(this, it) }

    /**
     * OpenCC for the TRANSCRIPT. The split from [outputConverter] is phonetic vs semantic: a
     * transcript records what was SAID, so Simplified→Traditional may only re-spell the same
     * word (conservative `s2t`); `s2twp` additionally substitutes vocabulary (信息→資訊), a
     * semantic edit that belongs to generated text — summary, title, action items, speaker
     * names — which keep using [outputConverter]. This holds for EVERY backend, so a
     * Traditional transcript reads the same whichever one produced it.
     *
     * No backend has a spoken-language picker (Nemotron, the one that did, is gone), so every
     * model here is always in auto mode and always emits Simplified — converting to Traditional
     * is what makes that usable for a zh-TW user, and is a no-op on non-Chinese output (the
     * converter leaves Latin/kana/hangul alone).
     *
     * The Chinese-script setting decides the direction: with Simplified chosen the transcript
     * goes through `t2s` (a no-op on text that is already Simplified, a guard against the
     * model's occasional Traditional forms). It used to ignore the setting and always produce
     * Traditional (issue #3), unlike the in-place switch in MainActivity.applyChineseScript.
     */
    private fun transcriptConverter(): OpenCcConverter? =
        when (SummaryScript.scriptFor(TranscriptionConfig.Holder.config.summaryScript, this)) {
            ChineseScript.TRADITIONAL -> OpenCcConverter.getTranscriptTraditional(this)
            ChineseScript.SIMPLIFIED -> OpenCcConverter.getTranscriptSimplified(this)
        }

    /**
     * Thread budget of the native engines (ASR/diarization, reader): the hardware profile's pick —
     * a first-launch benchmark, or the topology heuristic until it has run — unless the user forced
     * a count in Settings. See [studio.primeminutes.core.hw.HwInfo].
     */
    private fun asrThreads(): Int = studio.primeminutes.core.hw.HwInfo.threads(this)

    /** Start/refresh the FGS with the right type: microphone while recording, else data-sync. */
    private fun startForegroundTyped(recording: Boolean, text: String) {
        acquireWakeLock()   // keep the CPU awake for this run even if the screen turns off
        notifRecording = recording
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    /**
     * Honor the startForegroundService() → startForeground() contract on an early return that arrived
     * via startForegroundService() but has no fresh work to start (a redundant queue kick while a drain
     * is already running, a consumed export). Android kills the process with RemoteServiceException
     * (the app just vanishes to the home screen) if startForeground() isn't called within ~5s of
     * startForegroundService() — even when we're about to bow out. Re-asserts the CURRENT notification
     * (same FGS type + last-shown text) so a running job's live progress isn't disturbed.
     */
    private fun satisfyForegroundContract() {
        startForegroundTyped(recording = notifRecording, text = notifText.ifEmpty { getString(R.string.exporting) })
    }

    private fun buildNotification(text: String): Notification {
        notifText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Prime Minutes pipeline", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags,
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP), flags,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Prime Minutes")
            .setContentText(text)
            // A microphone while capturing, the app's mark while processing (it was the system
            // "download" arrow for both).
            .setSmallIcon(if (notifRecording) R.drawable.ic_stat_mic else R.drawable.ic_stat_primeminutes)
            .setOngoing(true)
            .setContentIntent(openPi)   // tap the notification to reopen the app
        // While recording, one action, the capture screen's own "Stop & save": capture ends, the
        // talk is saved and processed. (A cancelling "Stop" here ended the run behind the app's
        // back: the capture screen kept counting with nothing recording.) Once processing, "Stop".
        if (notifRecording) {
            val finishPi = PendingIntent.getService(
                this, 2, Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP_RECORDING), flags,
            )
            b.addAction(android.R.drawable.ic_media_pause, getString(R.string.capture_stop), finishPi)
        } else {
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop_resumable), stopPi)
        }
        return b.build()
    }
}
