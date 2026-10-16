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
    private val _progress = MutableStateFlow<Float?>(null)
    /** Fraction of the prefetch done while it runs, else null. */
    val progress: StateFlow<Float?> = _progress
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

    /** What the speech engine and the chosen AI notes model still need (file checks only, call off the main thread). */
    fun pending(context: Context): DownloadPlan.Pending {
        val mm = ModelManager(context)
        val llm = LlmRegistry.byId(ConfigStore.load(context).llmModelId)
        return DownloadPlan.pending(mm.asrReady(), mm.asrDownloadBytes(), mm.llmReady(llm), llm.totalBytes)
    }

    /** Starts the background prefetch when the user's consent covers the current network. */
    fun maybeStart(context: Context) {
        val app = context.applicationContext
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
            _progress.value = 0f
            try {
                val mm = ModelManager(app)
                if (p.speechBytes > 0) agg.begin("asr", p.speechBytes)
                if (p.readerBytes > 0) agg.begin("llm", p.readerBytes)
                if (p.speechBytes > 0) {
                    mm.ensureAsrModels { f -> _progress.value = agg.update("asr", f).first }
                    agg.end("asr")
                }
                if (p.readerBytes > 0) {
                    val llm = LlmRegistry.byId(ConfigStore.load(app).llmModelId)
                    mm.ensureLlmModel(llm) { f -> _progress.value = agg.update("llm", f).first }
                    agg.end("llm")
                }
                Log.i(TAG, "prefetch done")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A lost network or a pipeline fetching the same files: they resume from the partial files.
                Log.w(TAG, "prefetch stopped: ${e.message}")
            } finally {
                _progress.value = null
            }
        }
    }

    /** Re-checks on every network change: "Wi-Fi only" starts as soon as the phone joins Wi-Fi.
     *  Returns the unregister action. */
    fun watchNetwork(context: Context): () -> Unit {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { maybeStart(app) }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        return { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
}
