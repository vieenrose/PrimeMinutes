package studio.primeminutes.core.embed

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.service.TranscriptionService

/**
 * The one loaded [Embedder] of the process, shared by the indexer and the search box. Loads on
 * first use, serializes calls, and unloads itself after [IDLE_MS] without a call. It is never
 * resident beside a live recording on a small device ([mayLoad]): the search then stays lexical
 * and the indexer waits (T4).
 */
object EmbedHost {
    private const val TAG = "primeminutes-embed"
    const val IDLE_MS = 60_000L
    /** Below this much RAM the embedder never shares the device with a live capture. */
    private const val SMALL_DEVICE_RAM = 7L * 1024 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var embedder: Embedder? = null
    @Volatile private var lastUse = 0L
    private var idleJob: Job? = null

    /** Dev/test hook: how many native embed calls ran (T3/T5 counters). */
    val calls = java.util.concurrent.atomic.AtomicInteger(0)

    val loaded: Boolean get() = embedder != null

    fun ready(context: Context): Boolean = ModelManager(context).embedReady(EmbedRegistry.byId(EmbedRegistry.DEFAULT_ID))

    /** Bumped each time the search model is provisioned, wherever the download was started
     *  (Studio or Settings), so every screen re-reads [ready]. */
    val provisioned = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** False while a live recording runs on a device with less than [SMALL_DEVICE_RAM]. */
    fun mayLoad(context: Context): Boolean {
        if (!TranscriptionService.recordingJobActive) return true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return mi.totalMem >= SMALL_DEVICE_RAM
    }

    /**
     * Runs [block] with the loaded embedder (loading it with [threads] if needed). Returns null
     * when the model is not downloaded or [mayLoad] is false. Serialized: one call at a time.
     */
    suspend fun <T> use(context: Context, threads: Int, block: (Embedder) -> T): T? {
        val spec = EmbedRegistry.byId(EmbedRegistry.DEFAULT_ID)
        val mm = ModelManager(context)
        if (!mm.embedReady(spec) || !mayLoad(context)) return null
        return mutex.withLock {
            val e = embedder ?: run {
                val t0 = System.nanoTime()
                val loaded = Embedder.load(mm.embedDir(spec), threads)
                Log.i(TAG, "embedder loaded in ${(System.nanoTime() - t0) / 1_000_000} ms, threads=$threads")
                embedder = loaded; loaded
            }
            try { block(e).also { calls.incrementAndGet() } } finally { touch() }
        }
    }

    /** Loads now (search focus) so the first query does not pay the load. */
    fun preload(context: Context, threads: Int) { scope.launch { use(context, threads) { } } }

    private fun touch() {
        lastUse = System.currentTimeMillis()
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            mutex.withLock {
                if (System.currentTimeMillis() - lastUse >= IDLE_MS - 50) { embedder?.close(); embedder = null; Log.i(TAG, "embedder unloaded (idle)") }
            }
        }
    }

    /** Frees the model now (a recording starts on a small device, tests). Waits for a running call. */
    suspend fun unload() { mutex.withLock { embedder?.close(); embedder = null; idleJob?.cancel() } }
    fun unloadAsync() { scope.launch { unload() } }
}
