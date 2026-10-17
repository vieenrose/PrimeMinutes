package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves the open meeting's edits into its library entry: first to the entry's edit journal
 * ([studio.primeminutes.core.library.EditJournal], durable within a few ms of the edit), then into its
 * session.m4a once the edits settle ([FOLD_DELAY_MS] without a new one, or the app leaving the
 * foreground) — the rebuild that takes 15–25 s on a long meeting no longer stands between an edit and
 * its being saved.
 *
 * Journal writes run one at a time, in edit order; a burst of edits to the same meeting collapses into
 * one write of the latest state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object EditSaver {
    private const val TAG = "EditSaver"
    /** Quiet time after the last edit before the m4a is rebuilt. */
    const val FOLD_DELAY_MS = 5_000L
    /** Edits left unfolded by a kill are folded this long after the next launch (not during start-up). */
    const val LEFTOVER_DELAY_MS = 15_000L

    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val latest = ConcurrentHashMap<String, TranscriptionService.PersistRequest>()
    private val audio = ConcurrentHashMap<String, Uri>()
    private val inFlight = HashMap<String, Pair<Int, CompletableDeferred<Unit>>>()
    private val folds = HashMap<String, Job>()

    /** Dev/test hook: journal writes and folds done since process start. */
    val written = java.util.concurrent.atomic.AtomicInteger(0)
    val folded = java.util.concurrent.atomic.AtomicInteger(0)

    /** Saves [req] (the latest state of its meeting) now; the m4a follows after [FOLD_DELAY_MS]. */
    fun submit(context: Context, req: TranscriptionService.PersistRequest) {
        val app = context.applicationContext
        latest[req.entryId] = req
        req.audioUri?.let { audio[req.entryId] = it }
        queued(req.entryId)
        scope.launch(writer) {
            try {
                // A later submit for the same meeting may already have written this one's successor.
                val r = latest.remove(req.entryId) ?: return@launch
                write(app, r)
                scheduleFold(app, r.entryId, FOLD_DELAY_MS)
            } finally { done(req.entryId) }
        }
    }

    /** Saves [req] and folds it into the m4a at once (the service's own writes: a finished re-summarize). */
    suspend fun saveAndFold(context: Context, req: TranscriptionService.PersistRequest) {
        val app = context.applicationContext
        req.audioUri?.let { audio[req.entryId] = it }
        queued(req.entryId)
        try { withContext(writer) { write(app, req) } } finally { done(req.entryId) }
        synchronized(folds) { folds.remove(req.entryId)?.cancel() }
        fold(app, req.entryId)
    }

    /** Folds every meeting with settled-or-not edits now (the app is leaving the foreground). */
    fun foldSoon(context: Context) {
        val app = context.applicationContext
        val ids = synchronized(folds) { folds.keys.toList() }
        for (id in ids) scheduleFold(app, id, 0L)
    }

    /** Edits a kill left unfolded (found on disk at launch): folded a little later. */
    fun foldLeftovers(context: Context) {
        val app = context.applicationContext
        scope.launch {
            for (id in SessionLibrary.pendingEdits(app)) {
                if (synchronized(folds) { id in folds }) continue
                Log.i(TAG, "edits of $id were not folded before the app stopped: folding them")
                scheduleFold(app, id, LEFTOVER_DELAY_MS)
            }
        }
    }

    /** Suspends until the edits submitted for [entryId] are on disk (bounded). An open waits on this. */
    suspend fun awaitWritten(entryId: String) {
        val d = synchronized(inFlight) { inFlight[entryId]?.second } ?: return
        withTimeoutOrNull(10_000) { d.await() }
    }

    private fun queued(id: String) = synchronized(inFlight) {
        val cur = inFlight[id]
        inFlight[id] = ((cur?.first ?: 0) + 1) to (cur?.second ?: CompletableDeferred())
    }

    private fun done(id: String) = synchronized(inFlight) {
        val cur = inFlight[id] ?: return@synchronized
        if (cur.first <= 1) { inFlight.remove(id); cur.second.complete(Unit) } else inFlight[id] = (cur.first - 1) to cur.second
    }

    private fun write(app: Context, r: TranscriptionService.PersistRequest) {
        val t0 = System.nanoTime()
        val ok = SessionLibrary.saveEdits(app, r.entryId, SessionLibrary.Content(
            r.utterances, r.speakerNames, r.summary, r.actionItems, r.title, r.notes, r.asrModelId, r.asrBackend, r.llmModelId,
        ))
        if (!ok) return
        written.incrementAndGet()
        Log.i(TAG, "edits of ${r.entryId} saved in ${(System.nanoTime() - t0) / 1_000_000} ms")
        // Search and Ask read the journal: their index follows the edit now, not after the fold.
        studio.primeminutes.core.embed.LibraryIndexer.request(app, r.entryId)
    }

    private fun scheduleFold(app: Context, id: String, delayMs: Long) = synchronized(folds) {
        folds[id]?.cancel()   // only ever cancels the wait: a fold under way runs NonCancellable
        lateinit var job: Job
        job = scope.launch {
            delay(delayMs)
            withContext(NonCancellable) {
                synchronized(folds) { if (folds[id] === job) folds.remove(id) }
                fold(app, id)
            }
        }
        folds[id] = job
    }

    private suspend fun fold(app: Context, id: String) {
        val updated = runCatching { SessionLibrary.foldEdits(app, id, audio[id]) }
            .onFailure { Log.w(TAG, "could not fold the edits of $id (kept, retried at the next launch)", it) }
            .getOrNull() ?: return
        folded.incrementAndGet()
        RunRegistry.emit(TranscriptionService.UNTAGGED, TranscriptEvent.LibrarySaved(Uri.fromFile(updated.sessionFile).toString(), updated.title))
        studio.primeminutes.core.embed.LibraryIndexer.request(app, updated.id)
    }
}
