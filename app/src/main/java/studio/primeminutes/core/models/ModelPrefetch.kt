package studio.primeminutes.core.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import studio.primeminutes.core.config.ConfigStore

/**
 * The first-use model downloads, with the user's say: how much is left to fetch, on which kind of
 * network the user agreed to fetch it ([DownloadPlan.Consent], persisted), and a background prefetch
 * that runs once the user said "download now" (or "Wi-Fi only" and the phone is on Wi-Fi). The
 * pipelines still download what they need themselves (same files, same per-file lock in
 * [ModelManager]), so a prefetch cut short only means they resume it.
 */
object ModelPrefetch {
    private const val TAG = "primeminutes-prefetch"
    private const val PREFS = "primeminutes_downloads"
    private const val KEY_CONSENT = "consent"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** A running prefetch: the fraction done and the bytes it fetches in all. */
    data class Status(val fraction: Float, val totalBytes: Long, val paused: Boolean = false) {
        val doneBytes: Long get() = (totalBytes * fraction.coerceIn(0f, 1f).toDouble()).toLong()
    }
    private val _status = MutableStateFlow<Status?>(null)
    /** The prefetch while it runs (shown on the library and in Settings, with Cancel), else null. */
    val status: StateFlow<Status?> = _status
    private val _progress = MutableStateFlow<Float?>(null)
    /** Fraction of the prefetch done while it runs, else null. */
    val progress: StateFlow<Float?> = _progress
    private fun report(f: Float?, total: Long, paused: Boolean = false) { _progress.value = f; _status.value = f?.let { Status(it, total, paused) } }
    /** Wakes a paused prefetch: network back, the app back in front, or the Resume button. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** Resume button (and app/network events): a paused prefetch tries again now. */
    fun resumeNow() { wake.trySend(Unit) }
    /** Cancel on the progress card: no automatic restart (network change) until the app restarts or the
     *  user agrees again; a recording or an import still fetches what it needs. */
    @Volatile private var cancelledThisRun = false
    /** "Later" on the first-launch prompt: not asked again until the app restarts (a recording or an
     *  import that needs the models still asks). */
    @Volatile var laterThisRun = false

    fun consent(context: Context): DownloadPlan.Consent =
        runCatching {
            DownloadPlan.Consent.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONSENT, null) ?: "UNSET")
        }.getOrDefault(DownloadPlan.Consent.UNSET)

    fun setConsent(context: Context, c: DownloadPlan.Consent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CONSENT, c.name).apply()
    }

    /** On Wi-Fi (or Ethernet): an unmetered default network. False when offline. */
    fun unmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /** Some network with internet access (metered or not). */
    private fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /** What the speech engine and the chosen AI notes model still need (file checks only, call off the main thread). */
    fun pending(context: Context): DownloadPlan.Pending {
        val mm = ModelManager(context)
        val llm = LlmRegistry.byId(ConfigStore.load(context).llmModelId)
        val ready = mm.llmReady(llm)
        // A replaced model still installed (E4B mobile-v1 while mobile-v2 is fetched) keeps the reader working.
        val replacedReady = !ready && mm.readerSpec(llm.id).id != llm.id
        return DownloadPlan.pending(mm.asrReady(), mm.asrDownloadBytes(), ready, mm.llmDownloadBytes(llm), replacedReady)
    }

    /** Starts the background prefetch when the user's consent covers the current network. [userAsked]:
     *  the user just agreed (download prompt), which lifts an earlier Cancel. */
    fun maybeStart(context: Context, userAsked: Boolean = false) {
        val app = context.applicationContext
        if (userAsked) cancelledThisRun = false
        if (cancelledThisRun) return
        scope.launch {
            val p = pending(app)
            if (!DownloadPlan.mayPrefetch(consent(app), unmetered(app), p)) return@launch
            start(app, p)
        }
    }

    @Synchronized private fun start(app: Context, p: DownloadPlan.Pending) {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.i(TAG, "prefetch ${SizeFormat.bytes(p.total)} (speech ${SizeFormat.bytes(p.speechBytes)}, AI notes ${SizeFormat.bytes(p.readerBytes)})")
            val agg = DownloadAggregate()
            report(0f, p.total)
            val mm = ModelManager(app)
            if (p.speechBytes > 0) agg.begin("asr", p.speechBytes)
            if (p.readerBytes > 0) agg.begin("llm", p.readerBytes)
            var last = 0f
            var attempt = 0
            var asrDone = p.speechBytes <= 0
            try {
                // A lost network, the phone asleep, a server hiccup: the prefetch PAUSES (the card says so, with
                // Resume) and continues from the partial files when the network is back, the app returns, Resume
                // is tapped, or after a backoff. Only Cancel or a permanent error (gone, disk full) ends it.
                while (true) {
                    try {
                        if (!asrDone) {
                            mm.ensureAsrModels { f -> last = agg.update("asr", f).first; report(last, p.total) }
                            agg.end("asr"); asrDone = true
                        }
                        if (p.readerBytes > 0) {
                            val llm = LlmRegistry.byId(ConfigStore.load(app).llmModelId)
                            mm.ensureLlmModel(llm) { f -> last = agg.update("llm", f).first; report(last, p.total) }
                            agg.end("llm")
                        }
                        Log.i(TAG, "prefetch done")
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (mm.isPermanent(e)) { Log.w(TAG, "prefetch stopped: ${e.message}"); break }
                        attempt++
                        Log.w(TAG, "prefetch paused ($attempt): ${e.message}")
                        report(last, p.total, paused = true)
                        while (wake.tryReceive().isSuccess) { /* drop a stale wake */ }
                        // Offline, it stays paused until a network comes back (onAvailable wakes it).
                        do {
                            kotlinx.coroutines.withTimeoutOrNull(minOf(60_000L, 2_000L shl minOf(attempt - 1, 5))) { wake.receive() }
                        } while (!online(app))
                        report(last, p.total)
                        Log.i(TAG, "prefetch resumes")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "prefetch cancelled")
                throw e
            } finally {
                report(null, 0L)
            }
        }
    }

    /** Cancel button: stops the prefetch (the file being fetched starts over next time). */
    @Synchronized fun cancel() {
        cancelledThisRun = true
        job?.cancel()
    }

    /** Re-checks on every network change: "Wi-Fi only" starts as soon as the phone joins Wi-Fi.
     *  Returns the unregister action. */
    fun watchNetwork(context: Context): () -> Unit {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { maybeStart(app) }
            override fun onAvailable(network: Network) { resumeNow() }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        return { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
}
