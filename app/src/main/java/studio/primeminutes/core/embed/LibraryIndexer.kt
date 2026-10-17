package studio.primeminutes.core.embed

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import studio.primeminutes.core.hw.HwInfo
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.session.PrimeMinutesSession

/**
 * Keeps every finished meeting's `index.bin` current. Two lanes, one worker coroutine:
 *  - [request]: a meeting that was just saved (summary done, edits persisted) — indexed next;
 *  - [catchUp]: the rest of the library, one at a time, only when the device is idle enough
 *    (battery above [MIN_BATTERY] % or charging), cancellable; a backlog build is cut between two
 *    chunks when a job starts and re-queued for later.
 * The worker never runs beside the speech engine or the reader ([busy]): it waits, so a recording
 * or a summary keeps all the cores (T1). Writes are atomic ([SessionIndex.write]) and the
 * fingerprint is re-checked after the write, so a meeting edited while being indexed is redone.
 */
object LibraryIndexer {
    private const val TAG = "primeminutes-index"
    private const val MIN_BATTERY = 20
    private const val WAIT_MS = 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val priority = ArrayDeque<String>()
    private val backlog = ArrayDeque<String>()
    private var worker: Job? = null
    @Volatile private var catchUpCancelled = false

    private val _indexing = MutableStateFlow<Set<String>>(emptySet())
    /** Ids being indexed right now (the Studio row's chip). */
    val indexing = _indexing.asStateFlow()
    private val _pending = MutableStateFlow(0)
    /** How many meetings wait for the catch-up (Settings). */
    val pending = _pending.asStateFlow()

    /** Dev/test hook: meetings indexed since process start. */
    val done = java.util.concurrent.atomic.AtomicInteger(0)
    /** Dev/test hook: forces indexing even when the device looks busy (tests only). */
    @Volatile var ignoreBusy = false

    fun request(context: Context, entryId: String) {
        synchronized(lock) { if (entryId !in priority) priority.addLast(entryId) }
        start(context.applicationContext)
    }

    /** Ids of the finished meetings whose index is missing or stale (cheap mtime pre-check). */
    fun staleIds(context: Context): List<String> {
        val spec = EmbedRegistry.byId(EmbedRegistry.DEFAULT_ID)
        return SessionLibrary.list(context.applicationContext).filter { e ->
            e.status == SessionLibrary.Status.DONE && e.sessionFile.exists() &&
                !indexFresh(e.dir, maxOf(e.sessionFile.lastModified(), java.io.File(e.dir, "meta.json").lastModified(),
                    java.io.File(e.dir, studio.primeminutes.core.people.PeopleStore.LINKS).lastModified(),
                    studio.primeminutes.core.library.EditJournal.file(e.dir).lastModified()), spec.id)
        }.map { it.id }
    }

    /** Scans the library and queues every finished meeting whose index is missing or stale. */
    fun catchUp(context: Context) {
        val app = context.applicationContext
        catchUpCancelled = false
        scope.launch {
            if (!EmbedHost.ready(app)) return@launch
            val stale = staleIds(app)
            synchronized(lock) { for (id in stale) if (id !in backlog && id !in priority) backlog.addLast(id) }
            _pending.value = synchronized(lock) { backlog.size }
            if (stale.isNotEmpty()) start(app)
        }
    }

    /** Stops the catch-up: the queue is dropped and the meeting being embedded stops at its next chunk (≤ 1 s). */
    fun cancelCatchUp() { catchUpCancelled = true; synchronized(lock) { backlog.clear() }; _pending.value = 0 }

    /** Thrown between two chunks when the catch-up is cancelled: the meeting keeps its old index. */
    private class CatchUpCancelled : RuntimeException()

    /** True when [dir] has a valid index newer than the session file: the cheap pre-check. The
     *  fingerprint is still the authority when the meeting is actually visited. */
    private fun indexFresh(dir: java.io.File, sessionModified: Long, modelId: String): Boolean {
        val f = SessionIndex.file(dir)
        if (!f.exists() || f.lastModified() < sessionModified) return false
        return SessionIndex.read(dir)?.modelId == modelId
    }

    private fun start(app: Context) {
        synchronized(lock) {
            if (worker?.isActive == true) return
            worker = scope.launch { loop(app) }
        }
    }

    private fun busy(): Boolean = !ignoreBusy &&
        studio.primeminutes.service.RunRegistry.busy

    private fun batteryOk(app: Context): Boolean {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return charging || level < 0 || level * 100 / scale > MIN_BATTERY
    }

    private suspend fun loop(app: Context) {
        while (true) {
            val (id, fromBacklog) = synchronized(lock) {
                priority.removeFirstOrNull()?.let { it to false }
                    ?: backlog.removeFirstOrNull()?.let { it to true }
                    ?: run { worker = null; null }
            } ?: break
            _pending.value = synchronized(lock) { backlog.size }
            if (fromBacklog && catchUpCancelled) continue
            // Wait for the cores: never beside a capture, the ASR pipeline or the reader.
            while (busy() || (fromBacklog && !batteryOk(app))) {
                if (fromBacklog && catchUpCancelled) break
                delay(WAIT_MS)
            }
            if (fromBacklog && catchUpCancelled) continue
            val entry = SessionLibrary.byId(app, id) ?: continue
            _indexing.value = _indexing.value + id
            try {
                // A backlog build yields to a job between two chunks and is re-queued (T10).
                index(app, entry) { fromBacklog && (catchUpCancelled || busy()) }
            } catch (e: CatchUpCancelled) {
                if (catchUpCancelled) Log.i(TAG, "catch-up cancelled during ${entry.id}")
                else { synchronized(lock) { backlog.addFirst(id) }; Log.i(TAG, "catch-up of ${entry.id} yields to a job") }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "indexing ${entry.id} failed", e)
            } finally {
                _indexing.value = _indexing.value - id
            }
            yield()
        }
    }

    /**
     * Indexes [entry] if its index is missing or stale. Reuses unchanged vectors, writes
     * atomically under the library's build lock, and re-queues the meeting if its content changed
     * under us. [cancelled], polled between two chunks, aborts the build. Returns true when a write happened.
     */
    suspend fun index(app: Context, entry: SessionLibrary.Entry, cancelled: () -> Boolean = { false }): Boolean {
        val spec = EmbedRegistry.byId(EmbedRegistry.DEFAULT_ID)
        val loaded = load(app, entry) ?: run { Log.w(TAG, "${entry.id}: session file unreadable, not indexed"); return false }
        val fp = SessionIndex.fingerprint(loaded)
        if (SessionIndex.isCurrent(entry.dir, spec.id, fp)) return false
        val old = SessionIndex.read(entry.dir)
        val t0 = System.nanoTime()
        val built = EmbedHost.use(app, indexThreads()) { emb ->
            val units = SessionIndex.units(loaded) { emb.tokens(it) }
            SessionIndex.build(spec.id, fp, units, old, { if (cancelled()) throw CatchUpCancelled(); emb.embed(it) })
        } ?: run { Log.i(TAG, "${entry.id}: embedder unavailable, tried again later"); return false }   // model absent or a live capture on a small device
        SessionLibrary.withBuildLock(entry.id) {
            // The meeting may have been deleted or rewritten meanwhile.
            if (!entry.sessionFile.exists()) return@withBuildLock
            val now = load(app, entry)?.let { SessionIndex.fingerprint(it) }
            if (now != null && !now.contentEquals(fp)) { Log.i(TAG, "${entry.id} changed while indexing, redone"); request(app, entry.id); return@withBuildLock }
            SessionIndex.write(entry.dir, built)
        }
        done.incrementAndGet()
        Log.i(TAG, "indexed ${entry.id}: ${built.vectors.size} vectors in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return true
    }

    /** The session as indexed: its file, with the library title (a rename only rewrites meta.json). */
    private suspend fun load(app: Context, entry: SessionLibrary.Entry): PrimeMinutesSession.Loaded? {
        val s = PrimeMinutesSession.peek(entry.sessionFile) ?: return null
        val title = SessionLibrary.byId(app, entry.id)?.title?.takeIf { it.isNotBlank() } ?: return s
        return if (title == s.title) s else s.copy(title = title)
    }

    /** Background indexing uses at most two threads so a foreground app keeps its frames. */
    fun indexThreads(): Int = minOf(2, HwInfo.cores())
}
