package studio.primeminutes.online

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.service.TranscriptionService

/**
 * Podcast / YouTube items and their downloads. Submitting a link or an episode creates the library
 * item FIRST ([SessionLibrary.createDownloadEntry]); its download then runs in [TranscriptionService]
 * (foreground, with a notification), one at a time in submission order, into the item's own
 * directory. The item shows the download's state in the library and on its screen, and stays there
 * when the download pauses (lost network), fails (with the reason) or the app is killed (it resumes
 * at the next launch). Once downloaded, the item is processed like an import ([ready]).
 */
object ImportDownloads {
    sealed interface State {
        data object Idle : State
        /**
         * The item [entryId] downloading: [stageRes] is the phase label (resolving / downloading /
         * paused), [progress] null = indeterminate, [bytes] / [total] so far (total 0 = unknown).
         */
        data class Running(
            val entryId: String, val title: String?, val stageRes: Int, val progress: Float?,
            val bytes: Long = 0, val total: Long = 0,
        ) : State {
            val paused: Boolean get() = stageRes == studio.primeminutes.R.string.dl_paused || stageRes == studio.primeminutes.R.string.sub_no_space
        }
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Items whose download just finished, oldest first: processed as soon as nothing else runs. */
    private val _ready = MutableStateFlow<List<String>>(emptyList())
    val ready: StateFlow<List<String>> = _ready

    /** Bumped whenever an item's download state changes on disk (the library list re-reads it). */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /** The last download that failed for good (its reason), for a one-off message; null once shown. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure
    fun consumeFailure() { _failure.value = null }
    internal fun failed(why: String) { _failure.value = why }

    @Volatile internal var job: Job? = null
    @Volatile internal var current: String? = null
    /** The running item's own download job (cancelled alone when the item is deleted). */
    @Volatile internal var itemJob: Job? = null

    val running: Boolean get() = _state.value is State.Running

    /**
     * A podcast episode ([kind] podcast, [url] its audio) or a YouTube video ([kind] youtube, [url]
     * its page) was submitted: create its item, then start (or queue) its download. Null when the
     * item could not be created.
     */
    fun submit(ctx: Context, title: String?, kind: String, url: String, durationSec: Int = 0, seriesId: String? = null, guid: String? = null, onCreated: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        io.launch {
            val e = SessionLibrary.createDownloadEntry(app, title, SessionLibrary.PendingDownload(kind, url, seriesId = seriesId, guid = guid), durationSec) ?: return@launch
            onCreated(e.id)
            changed()
            kickNow(app)
        }
    }

    /** Retry a failed item: its error is cleared and it downloads again (from its partial file). */
    fun retry(ctx: Context, e: SessionLibrary.Entry) {
        val d = e.download ?: return
        val app = ctx.applicationContext
        io.launch {
            SessionLibrary.saveDownload(e, d.copy(error = null))
            changed()
            kickNow(app)
        }
    }

    /** File IO and the service start off the caller's thread, and outliving the sheet that asked. */
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Start the download loop if it is not running: it takes every item with a pending (not failed)
     * download, oldest first. Called on submit, on Retry, and at launch (items cut by a kill).
     */
    fun kick(ctx: Context) { val app = ctx.applicationContext; io.launch { kickNow(app) } }

    private fun kickNow(ctx: Context) {
        if (job?.isActive == true) { resumeNow(); return }
        val hasWork = SessionLibrary.list(ctx).any { it.download != null && it.download.error == null }
        if (!hasWork) return
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_IMPORT_DOWNLOAD),
        )
    }

    /** The item [entryId] was deleted: stop its download if it is the one running (the loop goes on). */
    fun cancel(entryId: String) {
        if (current == entryId) itemJob?.cancel()
        _ready.update { it - entryId }
    }

    /** Wakes a download paused by a lost network (Resume button, the app back in front). */
    internal val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    fun resumeNow() { wake.trySend(Unit) }

    /** A failure waiting can fix: the network (an [java.io.IOException]), not a 404 or a refused item. */
    /** Disk full: the download waits for space (kept .part), it neither fails nor counts as a network pause. */
    fun noSpace(e: Throwable): Boolean = SubscriptionLogic.isNoSpace(e)

    fun transient(e: Throwable): Boolean = e is java.io.IOException && e !is java.io.FileNotFoundException

    /** The UI took the ready item [entryId] (processing it, or it is gone). */
    fun consumeReady(entryId: String) = _ready.update { it - entryId }

    internal fun set(s: State) { _state.value = s }
    internal fun markReady(entryId: String) = _ready.update { if (entryId in it) it else it + entryId }
    internal fun changed() = _changes.update { it + 1 }

    /** Tests only. */
    internal fun resetForTest() { _state.value = State.Idle; _ready.value = emptyList(); _changes.value = 0; job = null; current = null; itemJob = null }
}
