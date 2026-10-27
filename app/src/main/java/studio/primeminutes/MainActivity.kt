package studio.primeminutes

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import studio.primeminutes.session.Screen
import studio.primeminutes.session.SessionUi
import studio.primeminutes.session.SessionViewModel
import studio.primeminutes.service.RunRegistry
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.core.content.IntentCompat
import studio.primeminutes.core.export.ExportFormat
import studio.primeminutes.core.export.TranscriptExport
import java.io.File
import studio.primeminutes.R
import studio.primeminutes.core.asr.AsrBackend
import studio.primeminutes.core.audio.AudioDecoder
import studio.primeminutes.core.audio.ImportRecovery
import studio.primeminutes.core.audio.RecordingRecovery
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.session.SessionAutosave
import studio.primeminutes.core.config.ConfigStore
import studio.primeminutes.core.config.SummaryScript
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.power.BackgroundReliability
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.models.DownloadPlan
import studio.primeminutes.core.models.ModelPrefetch
import studio.primeminutes.ui.DownloadConsentDialog
import studio.primeminutes.core.session.RecentSession
import studio.primeminutes.core.session.RecentSessions
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.core.update.UpdateChecker
import studio.primeminutes.core.update.UpdateInfo
import studio.primeminutes.core.update.UpdateInstaller
import studio.primeminutes.data.SpeakerEdits
import studio.primeminutes.data.SpeakerName
import studio.primeminutes.data.computeDiarizationStats
import androidx.compose.ui.graphics.SolidColor
import studio.primeminutes.data.speakerColor
import studio.primeminutes.data.speakerColorOn
import studio.primeminutes.service.TranscriptionService
import studio.primeminutes.ui.AddSourceSheet
import studio.primeminutes.ui.ExportSheet
import studio.primeminutes.ui.CaptureScreen
import studio.primeminutes.ui.SessionTabs
import studio.primeminutes.ui.SessionTopBar
import studio.primeminutes.ui.StudioScreen
import studio.primeminutes.ui.ConfigSheet
import studio.primeminutes.ui.EmptyState
import studio.primeminutes.ui.PodcastSheet
import studio.primeminutes.ui.UpdateBanner
import studio.primeminutes.ui.renderMarkdown
import studio.primeminutes.ui.SpeakerStatsPanel
import studio.primeminutes.ui.TranscriptSearchBar
import studio.primeminutes.core.embed.EmbedHost
import studio.primeminutes.core.embed.EmbedRegistry
import studio.primeminutes.core.embed.Embedder
import studio.primeminutes.core.embed.LibraryIndexer
import studio.primeminutes.core.embed.LibrarySearch
import studio.primeminutes.core.embed.SessionIndex
import studio.primeminutes.ui.highlightedTranscript
import studio.primeminutes.ui.YouTubeSheet
import studio.primeminutes.ui.theme.LanguageController
import studio.primeminutes.ui.theme.LocalLanguageController
import studio.primeminutes.ui.theme.LocalThemeController
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.ThemeController
import studio.primeminutes.ui.theme.PrimeMinutesTheme
import studio.primeminutes.ui.theme.primeMinutesSliderColors
import studio.primeminutes.ui.theme.primeMinutesTextFieldColors
import studio.primeminutes.ui.theme.Spacing
import studio.primeminutes.core.config.ThemeMode
import studio.primeminutes.core.config.ThemeStore
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Phase 4 shell: pick a local audio file (SAF) → run the foreground pipeline → render the
 * transcript with a synced player (tap an utterance to seek, active line highlighted) and
 * export buttons (SRT/VTT/TXT/JSON via SAF).
 */
// Loudness normalization for playback: bring a recording's RMS toward a target, but never
// past a peak ceiling, and never beyond a max gain (so near-silence isn't amplified to noise).
// Boost-only — already-loud sources are left alone. Applied per-track via LoudnessEnhancer.
private const val NORMALIZE_TARGET_RMS = 0.12   // ≈ -18 dBFS, a comfortable listening level
private const val NORMALIZE_PEAK_CEIL = 0.97    // keep boosted peaks below clipping
private const val NORMALIZE_MAX_GAIN = 16.0     // ≈ +24 dB ceiling

/**
 * Measure a mono 16-bit WAV at [uri] and return the normalization gain in millibels: the gain
 * that lifts its RMS to [NORMALIZE_TARGET_RMS], capped by the peak ceiling and max gain. Returns
 * 0 for non-WAV sources (compressed podcast/YouTube audio) or on any error, so they're untouched.
 * eg. a quiet recording at -34 dBFS RMS → ~+16 dB; an already-loud one → ~0.
 */
private fun computeNormalizeGainMb(context: android.content.Context, uri: Uri): Int = runCatching {
    context.contentResolver.openInputStream(uri)?.use { ins ->
        val header = ByteArray(44)
        if (ins.read(header) < 44) return@use 0
        fun tag(off: Int) = String(header, off, 4, Charsets.US_ASCII)
        val bits = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || bits != 16) return@use 0
        var peak = 0.0; var sumSq = 0.0; var n = 0L
        val buf = ByteArray(1 shl 16)
        while (true) {
            val r = ins.read(buf); if (r < 0) break
            var i = 0
            while (i + 1 < r) {
                val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)   // signed LE16
                val f = s / 32768.0
                val a = if (f < 0) -f else f; if (a > peak) peak = a
                sumSq += f * f; n++; i += 2
            }
        }
        if (n == 0L || peak <= 0.0) return@use 0
        val rms = kotlin.math.sqrt(sumSq / n)
        if (rms <= 1e-6) return@use 0
        val gain = minOf(NORMALIZE_TARGET_RMS / rms, NORMALIZE_PEAK_CEIL / peak, NORMALIZE_MAX_GAIN)
            .coerceAtLeast(1.0)
        (2000.0 * kotlin.math.log10(gain)).toInt()        // mB = 100 × 20·log10(gain)
    } ?: 0
}.getOrDefault(0)

/**
 * Reclaim the transient work files under filesDir/audio (import copies, decode outputs, orphaned
 * captures). Every library session keeps its own durable copy elsewhere, so on a cold start these
 * are all disposable — except the WAV an interrupted recording will be recovered from (named by the
 * recovery marker), which is kept. Call only when no pipeline is running.
 */
private fun reclaimAudioTemps(context: android.content.Context) {
    val dir = File(context.filesDir, "audio")
    if (!dir.isDirectory) return
    val keep = runCatching {
        File(context.filesDir, "recording.inprogress").takeIf { it.exists() }?.readText()?.trim()
    }.getOrNull()
    val keepImport = ImportRecovery.keptPath(context)   // a shared file a kill interrupted
    dir.listFiles()?.forEach { f ->
        if (f.absolutePath != keep && f.absolutePath != keepImport) runCatching { f.delete() }
    }
}

/** Copy a shared/opened content Uri into the app's audio dir, so a run doesn't depend on the
 *  caller's transient read grant (SEND/VIEW grants aren't persistable). Returns the local file. */
private fun copyToAppAudio(context: android.content.Context, uri: Uri): File {
    val dir = File(context.filesDir, "audio").apply { mkdirs() }
    // currentTimeMillis + the Uri hash avoids a name clash if two files are imported in the same ms.
    // No extension needed — AudioDecoder's MediaExtractor sniffs the container by content, not by name.
    val out = File(dir, "shared_${System.currentTimeMillis()}_${kotlin.math.abs(uri.hashCode())}")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "cannot open $uri" }
        out.outputStream().use { input.copyTo(it) }
    }
    return out
}

class MainActivity : ComponentActivity() {

    /** The open session and its run (survives recreation; see [SessionViewModel]). */
    private val sessionVm: studio.primeminutes.session.SessionViewModel by viewModels()

    /** Strings in the language chosen in Settings (the Compose tree re-wraps live, see below). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(studio.primeminutes.core.config.AppLanguage.wrap(newBase))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        studio.primeminutes.core.hw.MemoryPressure.onTrim(level)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotifications()
        // First launch (and after an update / on a new SoC): measure how many threads this phone scales to.
        if (!studio.primeminutes.service.RunRegistry.pipelineActive) lifecycleScope.launch {
            runCatching { studio.primeminutes.core.hw.BackendBench.settleCrash(applicationContext, studio.primeminutes.core.hw.HwInfo.benchKey(applicationContext)) }
            runCatching { studio.primeminutes.core.hw.HwInfo.ensureBench(applicationContext) }
        }
        // Reclaim space from any download the app was killed mid-way through (stale "*.part" temp
        // files) AND the transient audio work files (shared_* import copies, decoded_* decode
        // outputs, orphaned recording_* captures) that filesDir/audio accumulated — the durable
        // copies live under filesDir/library, so these are all reclaimable. Off the main thread —
        // it's file IO. NOT safe while the foreground service is mid-run (an Activity recreation,
        // not a cold start): the sweep would delete the pipeline's in-flight files.
        // Podcast/YouTube downloads go into their library item's directory now; while one runs, leave
        // the sweep for the next cold start anyway (the service is mid-work).
        val downloading = studio.primeminutes.online.ImportDownloads.running
        if (!studio.primeminutes.service.RunRegistry.pipelineActive && studio.primeminutes.service.RunRegistry.exportsInFlight == 0 && !downloading) {
            Thread {
                ModelManager(applicationContext).sweepStalePartFiles()
                studio.primeminutes.online.sweepStaleParts(java.io.File(filesDir, "audio"))
                reclaimAudioTemps(applicationContext)
            }.start()
        }
        handleIncoming(intent)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeStore.load(this)) }
            val controller = ThemeController(themeMode) { mode ->
                themeMode = mode
                ThemeStore.save(this, mode)
            }
            var fontScale by remember { mutableStateOf(ThemeStore.loadFontScale(this)) }
            val fontController = studio.primeminutes.ui.theme.FontScaleController(fontScale) { v ->
                fontScale = v
                ThemeStore.saveFontScale(this, v)
            }
            // The interface language switches live, no recreation: re-provide the context whose
            // resources carry the chosen locale, and Compose's stringResource follows it.
            var langCode by remember { mutableStateOf(studio.primeminutes.core.config.AppLanguage.load(this)) }
            val langController = LanguageController(langCode) { code ->
                langCode = code
                studio.primeminutes.core.config.AppLanguage.save(this, code)
                studio.primeminutes.core.config.AppLanguage.applyTo(this, code)
            }
            // The Activity handles rotation / size changes itself (configChanges), so the wrapped
            // context must be rebuilt when the real configuration changes — else orientation is frozen.
            val realConfig = androidx.compose.ui.platform.LocalConfiguration.current
            val localized = remember(langCode, realConfig) { studio.primeminutes.core.config.AppLanguage.wrap(this, langCode) }
            CompositionLocalProvider(
                LocalThemeController provides controller,
                LocalLanguageController provides langController,
                studio.primeminutes.ui.theme.LocalFontScaleController provides fontController,
                androidx.compose.ui.platform.LocalContext provides localized,
                androidx.compose.ui.platform.LocalConfiguration provides remember(localized, realConfig) {
                    // Sizes/orientation come from Compose's own (always current) configuration; only the
                    // locale list comes from the localized resources.
                    android.content.res.Configuration(realConfig).also { it.setLocales(localized.resources.configuration.locales) }
                },
            ) {
                PrimeMinutesTheme(themeMode, fontScale) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (!studio.primeminutes.core.power.CpuSupport.hasDotProd) {
                            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.cpu_unsupported), style = MaterialTheme.typography.titleMedium)
                            }
                            return@Surface
                        }
                        TranscribeScreen(sessionVm, ::startTranscription, ::stopTranscription, ::startRecording, ::processQueue)
                        BatteryExemptionDialog()
                    }
                }
            }
        }
    }

    /** The long-job battery explanation is up (see [maybeAskBatteryExemption]). */
    private var batteryAsk by mutableStateOf(false)

    /**
     * A long job is starting (an import of 10 min or more, a recording, a queue drain): unless the app
     * is already exempt, explain once why it should run unrestricted, then open the system dialog.
     * Samsung's FreecessController froze a screen-off import for 15 min despite the foreground
     * service, because the app was still battery-optimized. The answer is remembered (Allow or Not
     * now); dismissing without answering asks again on the next long job. Settings keeps the manual way.
     */
    private fun maybeAskBatteryExemption() {
        if (BackgroundReliability.isIgnoringBatteryOptimizations(this)) return
        if (getSharedPreferences("primeminutes", MODE_PRIVATE).contains(BATTERY_ASK_KEY)) return
        batteryAsk = true
    }

    private fun answerBatteryAsk(allow: Boolean) {
        batteryAsk = false
        getSharedPreferences("primeminutes", MODE_PRIVATE).edit().putString(BATTERY_ASK_KEY, if (allow) "allow" else "not_now").apply()
        if (allow) BackgroundReliability.requestIgnoreBatteryOptimizations(this)
    }

    @Composable
    private fun BatteryExemptionDialog() {
        if (!batteryAsk) return
        AlertDialog(
            onDismissRequest = { batteryAsk = false },
            title = { Text(stringResource(R.string.bg_long_job_title)) },
            text = { Text(stringResource(R.string.bg_long_job_message)) },
            confirmButton = { TextButton(onClick = { answerBatteryAsk(true) }) { Text(stringResource(R.string.bg_long_job_allow)) } },
            dismissButton = { TextButton(onClick = { answerBatteryAsk(false) }) { Text(stringResource(R.string.bg_long_job_not_now)) } },
        )
    }

    private fun startRecording(gen: Int) {
        maybeAskBatteryExemption()   // a meeting recording is a long job by nature
        ContextCompat.startForegroundService(
            this,
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_RECORD)
                .putExtra(TranscriptionService.EXTRA_RUN_GEN, gen),
        )
    }

    /** Drain the processing queue over the library's pending recordings. */
    private fun processQueue() {
        maybeAskBatteryExemption()
        ContextCompat.startForegroundService(
            this,
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_PROCESS_QUEUE),
        )
    }

    private fun startTranscription(uri: Uri, gen: Int) {
        lifecycleScope.launch {
            val sec = withContext(Dispatchers.IO) { probeSeconds(uri) }
            if (sec >= LONG_JOB_SEC) maybeAskBatteryExemption()
        }
        // content:// (SAF) needs a persistable grant; file:// from our own filesDir/audio
        // (podcast downloads) is already owned by the app.
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val intent = Intent(this, TranscriptionService::class.java)
            .putExtra(TranscriptionService.EXTRA_AUDIO_URI, uri.toString())
            .putExtra(TranscriptionService.EXTRA_RUN_GEN, gen)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun probeSeconds(uri: Uri): Double {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (t: Throwable) { 0.0 } finally { runCatching { mmr.release() } }
    }

    private fun stopTranscription() {
        startService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_STOP)
        )
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back in front: a model download paused by the interruption tries again at once.
        ModelPrefetch.resumeNow()
        studio.primeminutes.online.ImportDownloads.resumeNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** A "Share to Prime Minutes" or "Open with Prime Minutes" hands us an audio/video Uri — surface it to the
     *  Compose layer (consumed once), which copies it into app storage and starts a run. */
    private fun handleIncoming(intent: Intent?) {
        incomingAudioUri(intent)?.let { sharedAudioUri.value = it }
    }

    private fun incomingAudioUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    companion object {
        /** Latest audio/video Uri received via share / open-with; the UI consumes it once. */
        val sharedAudioUri = MutableStateFlow<Uri?>(null)
        /** An import at least this long asks for the battery exemption ([maybeAskBatteryExemption]). */
        const val LONG_JOB_SEC = 600.0
        /** The remembered answer to the long-job battery question ("allow" / "not_now"). */
        const val BATTERY_ASK_KEY = "bg_long_job_answer"
    }
}

/** On screen a speaker name never breaks across lines ("語者" / "1"). */
private fun unbreakable(name: String) =
    // NBSP for the space, WORD JOINER between characters: Han text breaks between any two characters.
    name.replace(' ', ' ').toList().joinToString("⁠")

/**
 * The app's one screen: Studio (library), Capture (live recording) and Session (a meeting). Its
 * session state lives in [SessionViewModel] ([SessionState], changed through [SessionReducer]);
 * what stays here is view state — scroll, the player, find-in-transcript matches, app-level
 * dialogs (crash recovery, update banner) and the Activity-bound launchers.
 */
@Composable
private fun TranscribeScreen(
    vm: SessionViewModel,
    onPicked: (Uri, Int) -> Unit,
    onStop: () -> Unit,
    onRecord: (Int) -> Unit,
    onProcessQueue: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val s by vm.state.collectAsState()
    // The meeting-reading agent's live state, and the queue drain's (folded even while nobody watches).
    val agent = vm.agent
    val queueAgent = vm.queueAgent
    val snackbarHostState = vm.snackbar
    // Ask (experimental, plan §6): the Studio question being answered; null = sheet closed.
    var askQuestion by remember { mutableStateOf<String?>(null) }
    // A speaker merge (from, into) waiting for its confirmation.
    var mergeAsk by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // The People screen (the directory of named speakers), over whatever screen opened it.
    var peopleOpen by remember { mutableStateOf(false) }
    // "Email participants" sheet (docs/design/speaker-contacts.md §4).
    var mailOpen by remember { mutableStateOf(false) }
    // Names given before the directory existed become its people (once, in the background).
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { studio.primeminutes.core.people.PeopleStore.migrateIfNeeded(context) } } }
    // Speaker-boundary editor: the seam (index of the line that starts the new speaker) or the line to split.
    var boundarySeam by remember { mutableStateOf<Int?>(null) }
    var splitLineAt by remember { mutableStateOf<Int?>(null) }
    // DEV (debug builds only): `am start … --es vox_query "…" --es vox_ask "…"` pre-fills the Studio
    // search or opens Ask with a question, so screenshots and device checks need no CJK typing;
    // `--es vox_type "…"` types the query in, one character at a time (README animations).
    // LocalContext is the localized wrapper, not the Activity: unwrap to reach the launch intent.
    val devExtras = if (BuildConfig.DEBUG) {
        var c: Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        (c as? android.app.Activity)?.intent?.extras
    } else null
    val devQuery = devExtras?.getString("vox_query") ?: ""
    LaunchedEffect(Unit) {
        val q = devExtras?.getString("vox_ask") ?: return@LaunchedEffect
        // `--ez vox_ask_legacy true`: the retrieval before meeting routing, for before/after timings.
        studio.primeminutes.core.ask.AskEngine.devRepeat = devExtras.getInt("vox_ask_repeat")
        if (devExtras.getBoolean("vox_ask_legacy")) studio.primeminutes.core.ask.AskEngine.retrieval = studio.primeminutes.core.ask.AskEngine.Retrieval.LEGACY
        delay(3_000); askQuestion = q.trim()
    }
    // DEV: `--es vox_open <library entry id>` opens that meeting (device checks of a given item).
    LaunchedEffect(Unit) {
        val id = devExtras?.getString("vox_open") ?: return@LaunchedEffect
        val e = withContext(Dispatchers.IO) { SessionLibrary.byId(context, id) } ?: return@LaunchedEffect
        delay(1_000); vm.openSessionUri(Uri.fromFile(e.audioFile))
    }
    // DEV: `--ez vox_export true` shares the open meeting as a session file (built into cache/shared).
    var devExportAsked by remember { mutableStateOf(false) }
    if (devExtras?.getBoolean("vox_export") == true && !devExportAsked) LaunchedEffect(s.utterances.size) {
        if (s.utterances.isEmpty()) return@LaunchedEffect
        delay(3_000); devExportAsked = true
        vm.stageSessionExport(true, null, PrimeMinutesSession.Format.M4A)
    }
    // DEV: `--ez vox_merge true` asks to merge the open meeting's first speaker into its second (the
    // confirmation, then the Undo snackbar) once its transcript is loaded.
    var devMergeAsked by remember { mutableStateOf(false) }
    if (devExtras?.getBoolean("vox_merge") == true && !devMergeAsked) LaunchedEffect(s.utterances.size) {
        val ids = s.utterances.mapNotNull { it.speaker }.distinct()
        if (ids.size >= 2) { delay(2_000); devMergeAsked = true; mergeAsk = ids[0] to ids[1] }
    }
    // DEV: `--ei vox_boundary N` opens the boundary editor on the N-th speaker change of the open meeting;
    // `--ei vox_split N` opens the split editor on line N.
    var devBoundaryAsked by remember { mutableStateOf(false) }
    if (devExtras != null && !devBoundaryAsked && (devExtras.containsKey("vox_boundary") || devExtras.containsKey("vox_split")))
        LaunchedEffect(s.utterances.size) {
            if (s.utterances.size < 2) return@LaunchedEffect
            delay(2_000); devBoundaryAsked = true
            if (devExtras.containsKey("vox_split")) splitLineAt = devExtras.getInt("vox_split").coerceIn(0, s.utterances.size - 1)
            else {
                val seams = (1 until s.utterances.size).filter { s.utterances[it].speaker != s.utterances[it - 1].speaker }
                seams.getOrNull(devExtras.getInt("vox_boundary"))?.let { boundarySeam = it }
            }
        }

    // DEV: `--es vox_podcast "<query>" [--ei vox_podcast_ep n]` submits episode n (default 2, newest
    // first) of the first series found, as the Podcast sheet does (device checks, no typing).
    LaunchedEffect(Unit) {
        val q = devExtras?.getString("vox_podcast") ?: return@LaunchedEffect
        val n = devExtras.getInt("vox_podcast_ep", 2)
        runCatching {
            val feed = studio.primeminutes.online.Podcast.searchSeries(q).first().feedUrl
            val ep = studio.primeminutes.online.Podcast.fetchEpisodes(feed, n + 1)[n]
            android.util.Log.i("primeminutes-dev", "vox_podcast: ${ep.title}")
            studio.primeminutes.online.ImportDownloads.submit(context, ep.title, SessionLibrary.PendingDownload.PODCAST, ep.audioUrl)
        }.onFailure { android.util.Log.w("primeminutes-dev", "vox_podcast failed", it) }
    }

    // --- Update notifier: once/day GitHub release check → dismissible banner → download+install. ---
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }   // non-null while downloading
    var updateApk by remember { mutableStateOf<File?>(null) }         // cached so a perms-retry skips re-download
    LaunchedEffect(Unit) { updateInfo = runCatching { UpdateChecker.check(context) }.getOrNull() }

    // --- Crash recovery: a live recording the OS killed mid-capture (OEM freeze, OOM, swipe-away)
    // is repaired and offered on next launch, so a meeting is never silently lost. ---
    var recoveredRec by remember { mutableStateOf<File?>(null) }
    // A stopped/killed session with a checkpoint that nothing is going to continue by itself.
    var interruptedEntry by remember { mutableStateOf<SessionLibrary.Entry?>(null) }
    var recoveredImport by remember { mutableStateOf<Pair<File, String>?>(null) }
    // A share/open-with import that arrived while a recording or run is active — confirm before it
    // supersedes (a co-installed app firing ACTION_SEND/VIEW must not silently kill a live capture).
    var importConfirm by remember { mutableStateOf<Uri?>(null) }

    val scope = rememberCoroutineScope()
    // The update banner as a reusable slot: rendered on BOTH the Studio home (where the user lands)
    // and the Session screen.
    val updateBannerSlot: @Composable () -> Unit = {
        updateInfo?.takeIf { !updateDismissed }?.let { info ->
            UpdateBanner(
                versionTag = info.tag,
                notes = info.notes,
                progress = updateProgress,
                onUpdate = {
                    scope.launch {
                        val apk = updateApk ?: run {
                            updateProgress = 0f
                            val f = runCatching {
                                UpdateInstaller.download(context, info.apkUrl) { updateProgress = it }
                            }.getOrNull()
                            updateProgress = null
                            f?.also { updateApk = it }
                        }
                        if (apk == null) {
                            val msg = context.getString(R.string.update_download_failed)
                            if (vm.now.screen == Screen.Session) snackbarHostState.showSnackbar(msg)
                            else Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        } else UpdateInstaller.install(context, apk)
                    }
                },
                onDismiss = { updateDismissed = true },
            )
            Spacer(Modifier.height(Spacing.sm))
        }
    }
    // Session tabs (portrait): 0 = Summary, 1 = Transcript. Each session opens on Summary when one
    // exists, else on the (possibly still streaming) Transcript.
    var sessTab by remember { mutableIntStateOf(1) }
    // Title changes — the LLM title arriving OR a user edit — propagate to the library entry's meta +
    // its home-screen row, so the auto-saved name upgrades to the real title automatically.
    LaunchedEffect(s.title, s.libraryDir) {
        val dir = s.libraryDir ?: return@LaunchedEffect
        val t = s.title?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { SessionLibrary.rename(context, dir, t) }
        vm.bumpRecents()
    }

    // --- Launch recovery, in priority order. (1) An interrupted live recording: the repaired WAV is
    // promoted into the app library FIRST — so even if the recovery dialog is never answered (another
    // kill), the audio is already safe — then offered. One effect, so the paths can't race each
    // other's RecordingRecovery.pending() side effects. ---
    LaunchedEffect(Unit) {
        // recordingJobActive is set on MAIN in onStartCommand — before any recreation could run
        // this check — closing the window where recordingActive (set later, on the pipeline
        // thread) was still false and pending() would "recover" (move!) a live capture's WAV.
        if (RunRegistry.recordingActive || RunRegistry.recordingJobActive) return@LaunchedEffect
        val interrupted = withContext(Dispatchers.IO) {
            RecordingRecovery.pending(context)?.let { wav ->
                SessionLibrary.promoteRecording(context, wav, RecordingRecovery.seconds(wav))?.wavFile ?: wav
            }
        }
        if (interrupted != null) { recoveredRec = interrupted; vm.bumpRecents() }
        else if (!RunRegistry.pipelineActive) recoveredImport = withContext(Dispatchers.IO) { ImportRecovery.pending(context) }
        if (interrupted == null && recoveredImport == null && !RunRegistry.pipelineActive) {
            interruptedEntry = withContext(Dispatchers.IO) {
                val queued = ProcessingQueue.ids(context).toSet()
                SessionLibrary.list(context)
                    .filter { it.id !in queued && SessionLibrary.hasProgress(it) && it.wavFile.exists() }
                    .maxByOrNull { it.createdAt }
            }
        }
        // Podcast / YouTube items whose download a kill cut short carry on (from their partial file).
        studio.primeminutes.online.ImportDownloads.kick(context)
        // SessionAutosave is legacy: the library durably holds every session. Discard any old snapshot.
        withContext(Dispatchers.IO) { SessionAutosave.clear(context) }
        // Invariant: a non-empty queue means the user already asked for processing — so if no
        // pipeline is live (process death mid-drain, or a kill before the post-run auto-resume),
        // restart the drain. Runs even when a recording was recovered above: the queue's ⋮ menu has
        // no "start" action, so returning early stranded every queued item.
        if (!RunRegistry.pipelineActive &&
            withContext(Dispatchers.IO) { ProcessingQueue.size(context) } > 0
        ) onProcessQueue()
    }
    // Find-in-transcript: search by meaning inside the open session ("≈" in the find bar) adds the
    // chunk starts whose vector matches the query to the match set.
    var semanticSearch by remember { mutableStateOf(false) }
    var semanticTargets by remember { mutableStateOf<List<Int>>(emptyList()) }
    // ≈ is on and this meeting's index is being built: the results area says so, and the effect
    // re-runs (key: indexingIds) once the index lands, so the empty keyword-only list is not sticky.
    var semanticIndexing by remember { mutableStateOf(false) }
    // In-meeting search (docs/design/in-session-search-ask.md): the open meeting's passages found by
    // meaning (notes and chunks), and whether the results list under the bar is unfolded.
    var densePassages by remember { mutableStateOf<List<studio.primeminutes.core.embed.LibrarySearch.Passage>>(emptyList()) }
    var resultsOpen by remember { mutableStateOf(true) }
    // In-meeting Ask: the question (empty = open on the field); null = sheet closed.
    var askMeeting by remember { mutableStateOf<String?>(null) }
    // The in-meeting Ask needs the AI notes model only (without the search model it uses keywords).
    var readerReady by remember { mutableStateOf(false) }
    // A library search passage was tapped: scroll to that time once the session is loaded.
    var pendingSeekSec by remember { mutableStateOf<Double?>(null) }
    // Semantic search model (EmbeddingGemma): presence, download progress, meetings being indexed.
    var searchModelReady by remember { mutableStateOf(EmbedHost.ready(context)) }
    // Ask's models (the AI notes model, and the search model its excerpts come from): re-checked when
    // a run ends or a prefetch finishes.
    val prefetching by ModelPrefetch.progress.collectAsState()
    var askModelReady by remember { mutableStateOf(false) }
    LaunchedEffect(s.config.llmModelId, s.running, prefetching == null, searchModelReady) {
        askModelReady = withContext(Dispatchers.IO) { studio.primeminutes.core.ask.AskEngine.available(context) }
        readerReady = withContext(Dispatchers.IO) { ModelManager(context).let { it.llmReady(it.readerSpec(s.config.llmModelId)) } }
    }
    var searchDownload by remember { mutableStateOf<Float?>(null) }
    val indexingIds by LibraryIndexer.indexing.collectAsState()
    LaunchedEffect(Unit) { if (searchModelReady) LibraryIndexer.catchUp(context) }
    // Edits saved to their journal but not yet into their session file when the app last stopped.
    LaunchedEffect(Unit) { studio.primeminutes.service.EditSaver.foldLeftovers(context) }
    // A download finished from Settings must flip the Studio's search too (it used to need a restart).
    val searchProvisioned by EmbedHost.provisioned.collectAsState()
    LaunchedEffect(searchProvisioned) { if (searchProvisioned > 0) searchModelReady = EmbedHost.ready(context) }
    fun downloadSearchModel() {
        if (searchDownload != null) return
        searchDownload = 0f
        scope.launch {
            val r = runCatching {
                ModelManager(context).ensureEmbed(EmbedRegistry.byId(EmbedRegistry.DEFAULT_ID)) { f -> searchDownload = f }
            }
            searchDownload = null
            searchModelReady = EmbedHost.ready(context)
            if (r.isFailure) Toast.makeText(context, R.string.search_model_download_failed, Toast.LENGTH_LONG).show()
            else LibraryIndexer.catchUp(context)
        }
    }
    var matchPos by remember { mutableIntStateOf(0) }

    // --- Synced player (android MediaPlayer; no extra dep). ---
    val pc = remember { PlayerController(context, scope) { vm.now.screen == Screen.Session } }
    var player by pc::player
    // Height of the docked player bar, so the import-download banner is laid above it, not over it.
    var playerBarPx by remember { mutableIntStateOf(0) }
    var enhancer by pc::enhancer
    var isPlaying by pc::isPlaying
    var positionMs by pc::positionMs
    var durationMs by pc::durationMs
    var volume by pc::volume
    var muted by pc::muted
    var dragMs by pc::dragMs
    var buffering by pc::buffering
    val listState = rememberLazyListState()
    val audioUri = s.audioUri
    DisposableEffect(audioUri) {
        pc.rebuild(audioUri)
        onDispose { pc.retire() }
    }
    // Measure the loaded track off the main thread and apply its normalization gain.
    LaunchedEffect(audioUri, enhancer) {
        val e = enhancer ?: return@LaunchedEffect
        val uri = audioUri ?: return@LaunchedEffect
        val gainMb = withContext(Dispatchers.IO) { computeNormalizeGainMb(context, uri) }
        runCatching { e.setTargetGain(gainMb) }
    }
    fun resumeOrRecover(seekMs: Int?) = pc.resumeOrRecover(seekMs)

    /**
     * Jump the recording to [ms] and play from there — what tapping a summary bullet's `[m:ss]`
     * anchor does.
     */
    fun seekAndPlay(ms: Int) {
        if (vm.now.audioUri == null) return
        pc.seekAndPlay(ms)
    }

    // Boundary editor preview: play [from, to] once, then pause (a newer preview cancels the older stop).
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun boundaryPreview(from: Double, to: Double) {
        if (vm.now.audioUri == null) return
        previewJob?.cancel()
        pc.seekAndPlay((from * 1000).toInt())
        previewJob = scope.launch {
            delay(((to - from) * 1000).toLong().coerceAtLeast(300))
            runCatching { pc.player?.pause() }; pc.isPlaying = false
        }
    }
    // Closing the editor stops a preview that is still playing (it must not run on into the meeting).
    fun stopBoundaryPreview() {
        if (previewJob?.isActive == true) { runCatching { pc.player?.pause() }; pc.isPlaying = false }
        previewJob?.cancel(); previewJob = null
    }

    // Also keyed on player: a source swap must cancel the old loop — its gentle start() retry
    // otherwise races the NEW player's async prepare.
    LaunchedEffect(isPlaying, player) { pc.poll() }

    // Playback belongs to the Session view only (see PlayerController.pauseOffScreen).
    LaunchedEffect(s.screen) { if (s.screen != Screen.Session) pc.pauseOffScreen() }

    // What the session's transitions ask of the Activity and the player.
    DisposableEffect(vm, pc) {
        val ui = object : SessionUi {
            override fun startImport(uri: Uri, gen: Int) = onPicked(uri, gen)
            override fun startRecording(gen: Int) = onRecord(gen)
            override fun stopRun() = onStop()
            override fun processQueue() = onProcessQueue()
            override fun pausePlayer() {
                runCatching { pc.player?.takeIf { it.isPlaying }?.pause() }
                pc.isPlaying = false; pc.buffering = false
            }
            override fun showPaused() { pc.isPlaying = false }
            // Swap the source to another file of the SAME audio, carrying the playhead and play-state.
            override fun carryPlayhead(uri: Uri) = pc.carryPlayhead(uri)
            override fun startActivity(intent: Intent) { runCatching { context.startActivity(intent) } }
        }
        vm.ui = ui
        onDispose { if (vm.ui === ui) vm.ui = null }
    }

    // Live-recording timer.
    var recSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.isRecording) {
        // A recording this screen re-attached to has been going for a while already.
        recSeconds = vm.recordingStartedAt()?.let { ((System.currentTimeMillis() - it) / 1000).toInt().coerceAtLeast(0) } ?: 0
        while (vm.now.isRecording) { delay(1000); recSeconds++ }
    }

    // Leaving the app (Home, app switch, screen off) must not lose edits: Android may kill the
    // process before the user ever comes back through Back. Flush on ON_PAUSE, while the app is
    // still foreground and allowed to start the service.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                if (vm.now.screen == Screen.Session) runCatching { vm.persistEdits() }
                // The edits are safe in their journal already; folding them into the m4a now, while
                // the app is idle, keeps the session files current for other readers and old versions.
                studio.primeminutes.service.EditSaver.foldSoon(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Offer to finish a recording the OS killed mid-capture. Requires an explicit choice (no
    // outside-tap dismiss) so the recovered meeting can't be lost by a stray tap.
    importConfirm?.let { uri ->
        AlertDialog(
            onDismissRequest = { importConfirm = null },
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { Text(stringResource(if (s.isRecording) R.string.import_confirm_recording else R.string.import_confirm_running)) },
            confirmButton = {
                TextButton(onClick = { importConfirm = null; vm.launchAudio(uri) }) {
                    Text(stringResource(R.string.import_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { importConfirm = null; ImportRecovery.clear(context) }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    // A shared file whose import a kill interrupted (see ImportRecovery): finish or drop it.
    recoveredImport?.let { (copy, name) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recover_import_title)) },
            text = { Text(stringResource(R.string.recover_import_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    recoveredImport = null
                    // Continue after the progress saved while it was transcribed, if any.
                    TranscriptionService.importResumeRequested = true
                    vm.launchAudio(Uri.fromFile(copy))
                }) { Text(stringResource(R.string.recover_finish)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    recoveredImport = null
                    ImportRecovery.clear(context)
                    runCatching { copy.delete() }
                }) { Text(stringResource(R.string.recover_discard)) }
            },
        )
    }
    recoveredRec?.let { wav ->
        val secs = RecordingRecovery.seconds(wav).toInt()
        val dur = fmtSec(secs)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recover_title)) },
            text = { Text(stringResource(R.string.recover_message, dur)) },
            confirmButton = {
                TextButton(onClick = {
                    recoveredRec = null
                    RecordingRecovery.clear(context)
                    vm.launchAudio(Uri.fromFile(wav))
                }) { Text(stringResource(R.string.recover_finish)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    recoveredRec = null
                    RecordingRecovery.clear(context)
                    // Explicit user deletion — removes the promoted library entry (or a bare file).
                    SessionLibrary.discard(context, wav)
                    vm.bumpRecents()
                }) { Text(stringResource(R.string.recover_discard)) }
            },
        )
    }

    // --- First-use model downloads (DownloadPlan): the size, and a warning off Wi-Fi, before any byte. ---
    // Shown once at first launch, and again before a recording or an import that would download.
    var dlAsk by remember { mutableStateOf<Pair<DownloadPlan.Pending, (() -> Unit)?>?>(null) }
    var dlUnmetered by remember { mutableStateOf(true) }
    fun withModels(action: () -> Unit) {
        scope.launch {
            val p = withContext(Dispatchers.IO) { ModelPrefetch.pending(context) }
            val unmetered = ModelPrefetch.unmetered(context)
            if (DownloadPlan.decide(ModelPrefetch.consent(context), unmetered, p) == DownloadPlan.Action.PROCEED) action()
            else { dlUnmetered = unmetered; dlAsk = p to action }
        }
    }
    /** A downloaded podcast / YouTube item, tapped: processed now, or after the job that runs (one at a time). */
    fun processDownloaded(e: SessionLibrary.Entry) {
        if (RunRegistry.busy || vm.now.isRecording) {
            studio.primeminutes.online.ImportDownloads.markReady(e.id)
            vm.notify(context.getString(R.string.dl_will_start))
        } else withModels { vm.processDownloaded(e) }
    }
    LaunchedEffect(Unit) {
        // A replaced reader model whose successor already loads (a migration that finished): its files go.
        val p = withContext(Dispatchers.IO) { runCatching { ModelManager(context).reclaimReplacedLlm() }; ModelPrefetch.pending(context) }
        val unmetered = ModelPrefetch.unmetered(context)
        // DEV (debug builds): `--ez vox_dlgate true` shows the prompt with the full sizes, models present or not.
        if (devExtras?.getBoolean("vox_dlgate") == true) {
            val mm = ModelManager(context); val llm = LlmRegistry.byId(s.config.llmModelId)
            dlUnmetered = devExtras.getBoolean("vox_dlgate_wifi", false)
            dlAsk = DownloadPlan.pending(false, mm.asrDownloadBytes(s.config.asrLatencyMs), false, llm.totalBytes) to null
        } else if (p.any && ModelPrefetch.consent(context) == DownloadPlan.Consent.UNSET && !ModelPrefetch.laterThisRun) {
            dlUnmetered = unmetered; dlAsk = p to null
        } else ModelPrefetch.maybeStart(context)
    }
    DisposableEffect(Unit) {
        val off = ModelPrefetch.watchNetwork(context)
        onDispose { off() }
    }
    dlAsk?.let { (p, action) ->
        DownloadConsentDialog(
            pending = p, unmetered = dlUnmetered,
            onNow = {
                dlAsk = null
                ModelPrefetch.setConsent(context, DownloadPlan.Consent.ANY_NETWORK)
                // With a run waiting, the run fetches what it needs (with its own progress); else prefetch now.
                if (action != null) action() else ModelPrefetch.maybeStart(context, userAsked = true)
            },
            onWifiOnly = {
                dlAsk = null
                ModelPrefetch.setConsent(context, DownloadPlan.Consent.WIFI_ONLY)
                if (dlUnmetered) { if (action != null) action() else ModelPrefetch.maybeStart(context, userAsked = true) }
                else Toast.makeText(context, R.string.dl_consent_waiting_wifi, Toast.LENGTH_LONG).show()
            },
            onLater = { dlAsk = null; ModelPrefetch.laterThisRun = true },
        )
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // Same path as a share: copied into app storage and marked (ImportRecovery), so a kill
        // mid-import can finish it on the next launch.
        if (uri != null) MainActivity.sharedAudioUri.value = uri
    }

    // A share / "open with" from another app lands in MainActivity.sharedAudioUri. Copy the stream
    // into app storage while its transient grant is live, then transcribe it like any other source.
    // A shared file may itself be a saved Prime Minutes session (.ogg/.m4a) — recover it instead.
    var pendingSharedImport by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(Unit) {
        MainActivity.sharedAudioUri.collect { u ->
            if (u == null) return@collect
            // Consume exactly the value we observed (latest-share-wins, no lost update).
            if (!MainActivity.sharedAudioUri.compareAndSet(u, null)) return@collect
            vm.setStatus(context.getString(R.string.status_importing))
            val local = withContext(Dispatchers.IO) { runCatching { copyToAppAudio(context, u) }.getOrNull() }
            if (local != null) {
                val luri = Uri.fromFile(local)
                // Until the service saves it into the library, this copy is the only one: mark it
                // so a kill mid-import offers to finish it on the next launch.
                if (!PrimeMinutesSession.hasEmbeddedSession(local)) ImportRecovery.markStarted(context, local, SessionViewModel.documentLabel(context, u))
                // If it embeds a session, route to recovery; otherwise transcribe it.
                if (PrimeMinutesSession.hasEmbeddedSession(local)) pendingSharedImport = luri
                // Importing supersedes the open session/recording: a share arriving mid-recording
                // must not silently kill a live capture — confirm first.
                else if (vm.now.isRecording || (vm.now.running && !vm.now.watchingQueue)) importConfirm = luri
                else withModels { vm.launchAudio(luri) }
            } else {
                vm.setStatus(context.getString(R.string.empty_status))
                vm.notify(context.getString(R.string.import_failed))
            }
        }
    }

    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.beginRecording()
        else vm.notify(context.getString(R.string.mic_permission_required))
    }
    fun requestRecord() = withModels {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.beginRecording() else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // --- Session as a self-describing .m4a: Save (SAF), Open (SAF → recover), Share. ---
    val sessionSaverM4a = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PrimeMinutesSession.Format.M4A.mime)
    ) { uri: Uri? ->
        if (uri == null) { vm.setExporting(false); return@rememberLauncherForActivityResult }
        vm.stageSessionExport(false, uri, PrimeMinutesSession.Format.M4A)
    }
    val sessionOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) vm.openSessionUri(uri) }
    // A shared file that turned out to embed a session (detected above) is recovered here.
    LaunchedEffect(pendingSharedImport) {
        pendingSharedImport?.let { vm.openSessionUri(it); pendingSharedImport = null }
    }

    // --- Transcript text exports (portable TXT / SRT / VTT / Markdown + copy & share). ---
    val speakerLabel: (Int) -> String = { sid -> s.speakerNames[sid]?.name ?: context.getString(R.string.speaker_n, sid + 1) }
    /** Model text (title, summary, actions, notes) as shown and exported: its S1, S2 become the
     *  transcript's speaker names, in the app's language. The saved text keeps S1, S2. */
    fun refs(text: String?, screen: Boolean = false): String? = text?.let { t ->
        studio.primeminutes.core.reader.SpeakerRefs.resolve(t, speakerLabel, s.utterances.mapNotNull { it.speaker }.toSet().ifEmpty { null },
            wrap = if (screen) ::unbreakable else ({ n -> n }))
    }
    /**
     * Everything below the summary that an export should carry: action items plus the v2 NOTES
     * sections that have no other home (decisions, open questions, topics, and any unknown key a
     * future model adds). Both export paths build their body here (they had drifted).
     */
    fun exportExtras(markdown: Boolean): String? {
        val h: (String) -> String = { if (markdown) "## $it" else it }
        val out = StringBuilder()
        // "-" is the extractor's own "nothing found" marker, not content.
        // Action items are an opt-in experiment: exported only when shown in the app.
        s.actionItems?.trim()?.takeIf { s.config.showActionItems && it.isNotEmpty() && it != "-" }?.let {
            out.append(h(context.getString(R.string.export_heading_actions))).append("\n").append(it).append("\n\n")
        }
        s.meetingNotes?.let { n ->
            fun section(heading: String, items: List<String>) {
                if (items.isEmpty()) return
                out.append(h(heading)).append("\n")
                items.forEach { out.append("- ").append(it).append("\n") }
                out.append("\n")
            }
            section(context.getString(R.string.notes_decisions), n.decisions)
            section(context.getString(R.string.notes_open), n.open)
            section(context.getString(R.string.notes_topics), n.topics)
            n.extra.forEach { (k, v) -> section(k, v) }
        }
        // Reader journal (the "AI notes" panel): one bullet per note, as the iOS export does.
        if (s.meetingNotes == null && agent.notes.isNotEmpty()) {
            out.append(h(context.getString(R.string.agent_title))).append("\n")
            agent.notes.forEach { out.append("- ").append(studio.primeminutes.core.reader.ReaderProtocol.render(it)).append("\n") }
            out.append("\n")
        }
        return refs(out.toString().trim().ifEmpty { null })
    }

    fun transcriptText(): String = TranscriptExport.plainText(s.utterances, speakerLabel, refs(s.title), refs(s.summary), exportExtras(markdown = false), null)
    fun exportBaseName(): String =
        s.title?.take(48)?.replace(Regex("[^\\p{L}\\p{N} _-]"), "_")?.trim()?.ifEmpty { null } ?: "transcript"
    fun writeDoc(uri: Uri?, content: String) {
        if (uri == null) return
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        val msg = if (ok) context.getString(R.string.session_saved_as, SessionViewModel.documentLabel(context, uri))
            else context.getString(R.string.session_save_failed)
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }
    // Each saver regenerates its content in the result callback (from the CURRENT transcript, so edits
    // made while the SAF dialog was open are captured) — no shared pending-content state to race on.
    val txtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { writeDoc(it, transcriptText()) }
    val srtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { writeDoc(it, TranscriptExport.srt(s.utterances, speakerLabel)) }
    val vttSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { writeDoc(it, TranscriptExport.vtt(s.utterances, speakerLabel)) }
    // .lrc has no registered MIME; octet-stream keeps the .lrc extension intact (players match by name).
    val lrcSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { writeDoc(it, TranscriptExport.lrc(s.utterances, speakerLabel, refs(s.title))) }
    val mdSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
        writeDoc(it, TranscriptExport.markdown(s.utterances, speakerLabel, refs(s.title), refs(s.summary),
            context.getString(R.string.export_heading_summary), context.getString(R.string.export_heading_transcript),
            exportExtras(markdown = true), null))
    }
    // PDF is binary, so it bypasses writeDoc() and streams from PdfExport directly to the SAF document.
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val utts = s.utterances; val t = refs(s.title); val sm = refs(s.summary)
        val sumH = context.getString(R.string.export_heading_summary); val txH = context.getString(R.string.export_heading_transcript)
        val extras = exportExtras(markdown = false)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        studio.primeminutes.core.export.PdfExport.write(os, utts, speakerLabel, t, sm, sumH, txH, extras, null, context.resources.configuration.locales[0])
                    } != null
                }.getOrDefault(false)
            }
            vm.notify(context.getString(
                if (ok) R.string.session_saved_as else R.string.session_save_failed,
                SessionViewModel.documentLabel(context, uri),
            ))
        }
    }
    // --- One export entry point for ExportSheet: (format, save|share). Content is regenerated per
    // call from the CURRENT session. ---
    fun exportText(f: ExportFormat): String {
        val utts = s.utterances
        return when (f) {
            ExportFormat.TEXT -> TranscriptExport.plainText(utts, speakerLabel, refs(s.title), refs(s.summary), exportExtras(markdown = false), null)
            ExportFormat.MARKDOWN -> TranscriptExport.markdown(
                utts, speakerLabel, refs(s.title), refs(s.summary),
                context.getString(R.string.export_heading_summary),
                context.getString(R.string.export_heading_transcript),
                exportExtras(markdown = true), null,
            )
            ExportFormat.SRT -> TranscriptExport.srt(utts, speakerLabel)
            ExportFormat.VTT -> TranscriptExport.vtt(utts, speakerLabel)
            ExportFormat.LRC -> TranscriptExport.lrc(utts, speakerLabel, refs(s.title))
            ExportFormat.PDF, ExportFormat.M4A -> ""   // binary — written by their own writers
        }
    }

    // Sharing a document/subtitle format: materialise it in a private cache dir and pass a
    // FileProvider uri (SAF only offers "save to a location").
    fun shareExport(f: ExportFormat) {
        val utts = s.utterances; val t = refs(s.title); val sum = refs(s.summary); val acts = refs(s.actionItems)
        val sumH = context.getString(R.string.export_heading_summary)
        val txH = context.getString(R.string.export_heading_transcript)
        val actH = context.getString(R.string.export_heading_actions)
        val body = if (f == ExportFormat.PDF) null else exportText(f)
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    // Own cache dir, cleared each time: a previous share must not leak a stale file,
                    // and this must not collide with share_audio / the session export dir.
                    val dir = File(context.cacheDir, "share_export").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, "${exportBaseName()}.${f.ext}").also { out ->
                        if (f == ExportFormat.PDF) {
                            out.outputStream().use { os ->
                                studio.primeminutes.core.export.PdfExport.write(os, utts, speakerLabel, t, sum, sumH, txH, acts, actH, context.resources.configuration.locales[0])
                            }
                        } else {
                            out.writeText(body.orEmpty())
                        }
                    }
                }.getOrNull()
            }
            val shareUri = file?.let {
                runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }.getOrNull()
            }
            if (shareUri == null) { vm.notify(context.getString(R.string.export_share_failed)); return@launch }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = f.mime
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, t ?: context.getString(R.string.app_name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.export_action_share))) }
        }
    }

    fun onExport(f: ExportFormat, share: Boolean) {
        if (f == ExportFormat.M4A) {
            if (share) vm.stageSessionExport(true, null, PrimeMinutesSession.Format.M4A)
            // Re-saving an already-saved (or opened-writable) session overwrites the SAME file;
            // only the first save opens the document picker — no more x(1).m4a proliferation.
            else vm.now.lastSaveUri?.let { vm.stageSessionExport(false, it, PrimeMinutesSession.Format.M4A) }
                ?: sessionSaverM4a.launch(PrimeMinutesSession.suggestFileName(vm.now.title, PrimeMinutesSession.Format.M4A.ext))
            return
        }
        if (share) { shareExport(f); return }
        val name = "${exportBaseName()}.${f.ext}"
        when (f) {
            ExportFormat.TEXT -> txtSaver.launch(name)
            ExportFormat.MARKDOWN -> mdSaver.launch(name)
            ExportFormat.PDF -> pdfSaver.launch(name)
            ExportFormat.SRT -> srtSaver.launch(name)
            ExportFormat.VTT -> vttSaver.launch(name)
            ExportFormat.LRC -> lrcSaver.launch(name)
            ExportFormat.M4A -> Unit   // handled above
        }
    }

    fun copyTranscript() {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Prime Minutes transcript", transcriptText()))
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.transcript_copied)) }
    }

    // Active utterance for the synced highlight: the line whose [start, end] holds the playhead.
    // In an inter-utterance gap (silence), switch to the next line at the gap's MIDPOINT so the
    // highlight anticipates upcoming speech instead of staying a line behind during the pause.
    // Derived: the playhead moves every 150 ms while playing; only a change of line recomposes.
    val activeIndex by remember { derivedStateOf {
        val sec = positionMs / 1000.0
        val utts = s.utterances
        val n = utts.size
        var i = -1
        while (i + 1 < n && utts[i + 1].startSec <= sec) i++
        if (i >= 0 && i + 1 < n && sec > utts[i].endSec) {
            val mid = (utts[i].endSec + utts[i + 1].startSec) / 2.0
            if (sec >= mid) i + 1 else i
        } else i
    } }

    LaunchedEffect(s.sessionGen) { sessTab = if (s.summary != null) 0 else 1 }
    val utterances = s.utterances
    val stats = computeDiarizationStats(utterances)
    // Landscape uses a two-pane layout: title/summary/stats move to a left overview pane.
    // Window size, not LocalConfiguration: the latter is wrapped for the language switch and must
    // never be the source of truth for layout.
    val landscape = studio.primeminutes.ui.components.rememberIsLandscape()
    val hasOverview = s.title != null || s.summary != null || s.actionItems != null || stats.perSpeaker.isNotEmpty()
    // Landscape with something to show → side-by-side overview + transcript panes; otherwise a single column.
    val twoPane = landscape && hasOverview
    val headerCount = 0   // tabs (portrait) and the left pane (landscape) own the overview now
    // Matches for find-in-transcript; recompute when the query or transcript length changes.
    val searchMatches = remember(s.searchQuery, utterances.size, semanticTargets) {
        if (s.searchQuery.isBlank()) emptyList()
        else {
            val lex = utterances.indices.filter { utterances[it].text.contains(s.searchQuery, ignoreCase = true) }
            if (semanticTargets.isEmpty()) lex
            else (lex + semanticTargets.filter { it in utterances.indices }).distinct().sorted()
        }
    }
    // Dense pass over the open session's index (index.bin next to its session file). Only for a
    // library session with the model present; the index is requested when it is missing.
    val semanticAvailable = searchModelReady && s.libraryDir != null && !s.running   // a meeting still processing has no index yet
    LaunchedEffect(s.searchQuery, semanticSearch, s.libraryDir, s.searchActive, indexingIds) {
        val wasIndexing = semanticIndexing
        semanticTargets = emptyList(); densePassages = emptyList(); semanticIndexing = false
        val dir = s.libraryDir
        val q = s.searchQuery.trim()
        if (!s.searchActive || !semanticSearch || !semanticAvailable || dir == null || q.isBlank()) return@LaunchedEffect
        delay(300)
        val e = withContext(Dispatchers.IO) { SessionLibrary.byId(context, dir.name) } ?: return@LaunchedEffect
        LibrarySearch.load(context, e)
        if (!withContext(Dispatchers.IO) { LibrarySearch.indexed(e) }) {
            // No index yet: the merge below would be keyword-only and look like "no matches". Say so
            // (results area) and queue the build; indexingIds re-runs this effect when it lands.
            semanticIndexing = true
            android.util.Log.i("primeminutes-search", "≈ on ${dir.name}: index missing, keyword-only for now, index requested")
            LibraryIndexer.request(context, dir.name)
            return@LaunchedEffect
        }
        if (wasIndexing) android.util.Log.i("primeminutes-search", "index ready for ${dir.name}: semantic search re-run")
        val qv = runCatching { EmbedHost.use(context, 1) { it.embed(Embedder.Prompts.query(q)) } }.getOrNull() ?: return@LaunchedEffect
        // One meeting through the library's dense pass: its notes and chunks above the shared threshold.
        val ps = LibrarySearch.dense(context, listOf(e), qv, maxPassages = studio.primeminutes.core.embed.MeetingSearch.DENSE_PASSAGES, snippetChars = 300)
            .flatMap { it.passages }
        densePassages = ps
        semanticTargets = ps.filter { it.kind == SessionIndex.Kind.CHUNK }.map { it.uttIndex }
    }
    // The results list under the bar: AI notes then lines, the meaning-only ones when ≈ is on.
    val meetingResults = remember(s.searchQuery, utterances.size, agent.notes.size, densePassages, s.searchActive) {
        if (!s.searchActive) emptyList()
        else studio.primeminutes.core.embed.MeetingSearch.results(s.searchQuery, utterances, agent.notes.toList(), densePassages)
    }
    LaunchedEffect(s.searchQuery) { if (s.searchQuery.isNotBlank()) resultsOpen = true }
    // DEV: `--es vox_msearch "…"` (with vox_open) opens the in-meeting search with that query; `--ez vox_sem true` adds ≈.
    var devSearchAsked by remember { mutableStateOf(false) }
    if (devExtras?.getString("vox_msearch") != null && !devSearchAsked) LaunchedEffect(s.utterances.size) {
        if (s.utterances.isEmpty()) return@LaunchedEffect
        delay(1_500); devSearchAsked = true; sessTab = 1
        semanticSearch = devExtras.getBoolean("vox_sem"); vm.setSearch(true, devExtras.getString("vox_msearch")!!)
    }
    // The search model's cold load was most of an in-meeting Ask's retrieval (3.8–5.7 s of ~4–6 s on the
    // Note10+, the passes themselves 40 ms): load it while the user types, as the Studio search box does.
    LaunchedEffect(s.searchActive) { if (s.searchActive && searchModelReady && !s.running) EmbedHost.preload(context, 2) }
    // The open meeting as a library entry (the in-meeting Ask's scope); null for a file opened from outside.
    val meetingEntry by produceState<SessionLibrary.Entry?>(null, s.libraryDir, s.recentsVersion) {
        value = s.libraryDir?.let { d -> withContext(Dispatchers.IO) { SessionLibrary.byId(context, d.name) } }
    }
    val askMeetingAvailable = s.config.askEnabled && readerReady && meetingEntry != null && utterances.isNotEmpty()
    fun pickResult(r: studio.primeminutes.core.embed.MeetingSearch.Result) {
        resultsOpen = false
        searchMatches.indexOf(r.line).takeIf { it >= 0 }?.let { matchPos = it }
        // The library-passage path: transcript tab, the line in view, the player parked there (paused).
        pendingSeekSec = if (r.kind == studio.primeminutes.core.embed.MeetingSearch.Kind.LINE) utterances.getOrNull(r.line)?.startSec ?: r.timeSec else r.timeSec
    }
    // A passage tapped in the library search: once the session is open, show the transcript at it.
    LaunchedEffect(pendingSeekSec, s.opening, s.transcriptReady) {
        val t = pendingSeekSec ?: return@LaunchedEffect
        if (s.opening || !s.transcriptReady || s.utterances.isEmpty()) return@LaunchedEffect
        pendingSeekSec = null
        val idx = studio.primeminutes.core.embed.MeetingSearch.lineAt(s.utterances, t).coerceAtLeast(0)
        sessTab = 1
        // The transcript list is composed only once its tab shows: an animated scroll issued before its
        // first layout did nothing, so the meeting opened at 0:00. Wait for the items, then jump.
        kotlinx.coroutines.withTimeoutOrNull(3_000) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > headerCount + idx }
        }
        runCatching { listState.scrollToItem(headerCount + idx, scrollOffset = -120) }
        // The player waits there too (paused), as on iOS: Play starts at the passage, not at 0:00. At the
        // line's start rounded up: a truncated 65.3337 s (or a note's whole 3:22 before a line at 202.4 s)
        // made the line above the playing one.
        val ms = s.utterances.getOrNull(idx)?.let { studio.primeminutes.core.embed.MeetingSearch.parkMs(it.startSec) } ?: (t * 1000).toInt()
        positionMs = ms
        kotlinx.coroutines.withTimeoutOrNull(5_000) { snapshotFlow { durationMs }.first { it > 0 } }
        positionMs = ms.coerceIn(0, maxOf(durationMs, ms))
        player?.let { p -> runCatching { p.seekTo(positionMs) } }
    }
    LaunchedEffect(s.searchQuery) { matchPos = 0 }
    fun searchPrev() { if (searchMatches.isNotEmpty()) matchPos = (matchPos - 1 + searchMatches.size) % searchMatches.size }
    fun searchNext() { if (searchMatches.isNotEmpty()) matchPos = (matchPos + 1) % searchMatches.size }
    fun closeSearch() { vm.setSearch(false, ""); semanticTargets = emptyList() }
    // Playback auto-follow — karaoke-style, but it must not FIGHT the user. Suppressed while
    // searching, while editing a line/speaker, when NOT actively playing, and for a few seconds after
    // the user manually scrolls. `autoScrolling` tags our OWN programmatic scroll.
    var autoScrolling by remember { mutableStateOf(false) }
    var lastUserScrollNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) lastUserScrollNanos = System.nanoTime()
        }
    }
    LaunchedEffect(activeIndex) {
        val editing = s.editingIndex >= 0 || s.editingSpeakerId != null
        val recentlyUserScrolled = System.nanoTime() - lastUserScrollNanos < 4_000_000_000L
        if (s.searchQuery.isBlank() && isPlaying && !editing && !recentlyUserScrolled &&
            activeIndex in s.utterances.indices
        ) {
            autoScrolling = true
            runCatching { listState.animateScrollToItem(headerCount + activeIndex, scrollOffset = -200) }
            autoScrolling = false
        }
    }
    // Keep the current search match in view as the user steps through, and move the player
    // there too (as iOS does): stepping through matches leaves the playhead behind otherwise,
    // so Play starts at 0:00 instead of the match on screen.
    LaunchedEffect(matchPos, searchMatches) {
        searchMatches.getOrNull(matchPos)?.let { idx ->
            runCatching { listState.animateScrollToItem(headerCount + idx, scrollOffset = -120) }
            s.utterances.getOrNull(idx)?.let { u ->
                positionMs = studio.primeminutes.core.embed.MeetingSearch.parkMs(u.startSec)
                player?.let { p -> runCatching { p.seekTo(positionMs) } }
            }
        }
    }

    // Active-model summary for the summary/title cards' attribution chip.
    val llmDisplay = LlmRegistry.byId(s.sessionModels?.first ?: s.config.llmModelId).displayName
    // Provenance for the TRANSCRIPT half of the pipeline, mirroring the summary's "via ..." line:
    // the ids that ACTUALLY produced this transcript, not today's default.
    val asrDisplay = AsrBackend.fromId(s.sessionModels?.third ?: s.config.asrBackend).shortName
    val diarizationDisplay = stringResource(R.string.pipeline_diar_nemotron)

    // The utterance list — shared by the portrait (single column) and landscape (right pane) layouts.
    val speakerIds = utterances.mapNotNull { it.speaker }.distinct().sorted()
    val transcriptItems: LazyListScope.() -> Unit = {
        if (utterances.isNotEmpty()) {
            item(key = "pipeline-note") {
                TranscriptPipelineNote(asr = asrDisplay, diarization = diarizationDisplay)
            }
        }
        items(count = utterances.size, key = { utterances[it].index }) { idx ->
            val u = utterances[idx]
            // Consecutive utterances from the same speaker read as one turn: only the
            // first line of a run carries the speaker chip (timestamps stay per line).
            val speakerChanged = idx == 0 || utterances[idx - 1].speaker != u.speaker
            // A seam handle between two speakers (finished transcripts only: live lines still move).
            val seam = !s.running && idx > 0 && speakerChanged && u.speaker != null && utterances[idx - 1].speaker != null
            if (seam) {
                val a = utterances[idx - 1].speaker!!; val b = u.speaker!!
                studio.primeminutes.ui.SpeakerSeam(
                    Color(speakerColorOn(a, pal.isDark)), Color(speakerColorOn(b, pal.isDark)),
                    s.speakerNames[a]?.name ?: stringResource(R.string.speaker_n, a + 1),
                    s.speakerNames[b]?.name ?: stringResource(R.string.speaker_n, b + 1),
                    onOpen = { boundarySeam = idx },
                )
            }
            UtteranceRow(
                utt = u,
                showSpeaker = speakerChanged,
                active = idx == activeIndex,
                highlight = if (s.searchActive) s.searchQuery else "",
                isEditing = s.editingIndex == idx,
                speakerNames = s.speakerNames,
                // The name is given in the people picker (a sheet), not inline any more.
                editingSpeakerId = null,
                onSeek = { sec -> lastUserScrollNanos = 0L; resumeOrRecover((sec * 1000).toInt()) },
                onBeginEdit = { vm.beginLineEdit(idx) },
                onSaveText = { newText -> vm.saveLineText(idx, newText) },
                onCancelEdit = { vm.cancelLineEdit() },
                onBeginSpeakerEdit = { sid -> vm.beginSpeakerEdit(sid, idx) },
                onCommitSpeakerName = { sid, name -> vm.commitSpeakerName(sid, name) },
                onCancelSpeakerEdit = { vm.cancelSpeakerEdit() },
                speakerIds = speakerIds,
                onReassignLine = { target -> vm.reassignLine(idx, target) },
                // A merge relabels every line of a speaker: confirmed first, then offered for Undo.
                onMergeSpeaker = { target -> u.speaker?.let { mergeAsk = it to target } },
                onSplitLine = if (!s.running && studio.primeminutes.data.BoundaryEdits.tokenize(u.text).size > 1) ({ splitLineAt = idx }) else null,
                topPadding = if (seam) 0.dp else null,
                // Like the seams: a run rewrites the lines, so a move/merge/edit made now would be lost.
                editable = !s.running,
            )
        }
    }

    // Title / summary / speaker-stats overview — the Summary tab in portrait, the left pane in
    // landscape. onSeek only when there IS audio to seek.
    val anchorSeek: ((Int) -> Unit)? = if (s.audioUri != null) ({ ms -> seekAndPlay(ms) }) else null
    // The download item this screen shows (re-read as its download state changes on disk).
    val dlChangesNow by studio.primeminutes.online.ImportDownloads.changes.collectAsState()
    val dlStateNow by studio.primeminutes.online.ImportDownloads.state.collectAsState()
    val dlEntry by produceState<SessionLibrary.Entry?>(null, s.downloadEntryId, dlChangesNow) {
        value = s.downloadEntryId?.let { id -> withContext(Dispatchers.IO) { SessionLibrary.byId(context, id) } }
    }
    val summaryCards: @Composable () -> Unit = {
        CompositionLocalProvider(
            studio.primeminutes.ui.LocalSpeakerRefs provides { t -> refs(t, screen = true) ?: t },
            studio.primeminutes.ui.LocalSpeakerNames provides utterances.mapNotNull { it.speaker }.toSet().associateWith { unbreakable(speakerLabel(it)) },
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // A podcast / YouTube item still downloading: its download, and what can be done about it.
            dlEntry?.takeIf { it.download != null }?.let { e ->
                studio.primeminutes.ui.ItemDownloadCard(
                    e, dlStateNow,
                    onRetry = { studio.primeminutes.online.ImportDownloads.retry(context, e) },
                    onDelete = { vm.deleteEntries(listOf(e)); vm.leaveToStudio() },
                )
            }
            // The reading agent at work (live during recording/processing; stays after as a log).
            val shownAgent = if (s.watchingQueue) queueAgent else agent
            if (shownAgent.active) SectionCard {
                studio.primeminutes.ui.AgentPanel(shownAgent, anchorSeek)
            }
            // Portrait: the title is in the top bar; the card only appears to edit it.
            s.title?.takeIf { twoPane || s.editingTitle }?.let { t ->
                TitleCard(t, llmDisplay, s.editingTitle,
                    onBeginEdit = { vm.editTitle(true) },
                    onSave = { vm.saveTitle(it) },
                    onCancel = { vm.editTitle(false) })
            }
            // Copy feedback: the snackbar is easy to miss on a long transcript, so the moment the text
            // is provably in the clipboard gets a light tap (iOS pairs the same moment with haptics).
            val copyHaptic = LocalHapticFeedback.current
            s.summary?.let { sm ->
                SummaryCard(sm, llmDisplay, s.editingSummary,
                    onBeginEdit = { vm.editSummary(true) },
                    onSave = { vm.saveSummary(it) },
                    onCancel = { vm.editSummary(false) },
                    onCopy = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Prime Minutes summary", refs(sm)))
                        copyHaptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.summary_copied)) }
                    },
                    onSeek = anchorSeek, showHeading = twoPane)
            }
            // Action items: a short follow-up section under the summary (experimental, opt-in).
            val hasActions = s.actionItems?.trim()?.let { it.isNotEmpty() && it != "-" } == true
            if (s.config.showActionItems && (hasActions || s.editingActions)) s.actionItems?.let { ai ->
                ActionItemsCard(ai, s.editingActions,
                    onBeginEdit = { vm.editActions(true) },
                    onSave = { vm.saveActions(it) },
                    onCancel = { vm.editActions(false) },
                    onCopy = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Prime Minutes action items", refs(ai)))
                        copyHaptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.action_items_copied)) }
                    },
                    onSeek = anchorSeek)
            }
            // Sections the v2 NOTES format adds; rendered only when non-empty.
            s.meetingNotes?.let { n ->
                NotesSection(stringResource(R.string.notes_decisions), n.decisions, anchorSeek)
                NotesSection(stringResource(R.string.notes_open), n.open, anchorSeek)
                NotesSection(stringResource(R.string.notes_topics), n.topics, anchorSeek)
                // Keys a future model version adds: shown rather than silently dropped.
                n.extra.forEach { (key, lines) -> NotesSection(key, lines, anchorSeek) }
            }
            if (stats.perSpeaker.isNotEmpty()) SectionCard {
                SpeakerStatsPanel(stats = stats, label = { sid ->
                    s.speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1)
                })
                // The one-tap path, once a person of this meeting has an address (§4.1).
                val peopleDoc by studio.primeminutes.core.people.PeopleStore.doc.collectAsState()
                if (!s.running && s.speakerNames.values.any { n -> peopleDoc.byId(n.person)?.emails?.isNotEmpty() == true }) {
                    TextButton(onClick = { mailOpen = true }, modifier = Modifier.testTag("speakers-email")) {
                        Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mail_chip))
                    }
                }
            }
            if (s.title == null && s.summary == null && stats.perSpeaker.isEmpty()) {
                Text(
                    stringResource(when {
                        s.running -> R.string.summary_pending_hint
                        utterances.isEmpty() -> R.string.status_no_speech
                        else -> R.string.summary_missing_hint
                    }),
                    color = pal.Slate400,
                    modifier = Modifier.padding(top = Spacing.xxl),
                )
            }
        }
        }
    }
    val overviewCards: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            summaryCards()
        }
    }

    // Blank slate → the EmptyState hero carries the primary "Add audio" CTA.
    val isEmptyState = utterances.isEmpty() && !s.running && player == null && s.downloadEntryId == null

    // Share a library entry's audio via the FileProvider (files/library is an exported path).
    fun shareEntryAudio(e: SessionLibrary.Entry) {
        val f = e.audioFile
        val subject = e.title ?: SessionLibrary.defaultTitle(context, e.createdAt)
        // Give the receiver a SEMANTIC filename ('My meeting.m4a', not 'session.m4a'): copy to the
        // shared cache under the title-derived name and share THAT.
        scope.launch {
            val named = withContext(Dispatchers.IO) {
                runCatching {
                    // Own cache dir (NOT the export flow's cacheDir/shared).
                    val dir = File(context.cacheDir, "share_audio").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    // With edits not yet folded into the entry's file, the copy is built with them.
                    SessionLibrary.shareableCopy(context, e, File(dir, PrimeMinutesSession.suggestFileName(subject, f.extension)))
                }.getOrNull()
            } ?: return@launch
            val shareUri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", named)
            }.getOrNull() ?: return@launch
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (f.extension == "wav") "audio/wav" else "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share_audio))) }
        }
    }

    interruptedEntry?.let { e ->
        AlertDialog(
            onDismissRequest = { interruptedEntry = null },
            title = { Text(stringResource(R.string.resume_prompt_title)) },
            text = { Text(stringResource(R.string.resume_prompt_message, e.title ?: SessionLibrary.defaultTitle(context, e.createdAt))) },
            confirmButton = { TextButton(onClick = { interruptedEntry = null; vm.resumeEntry(e) }) { Text(stringResource(R.string.action_resume)) } },
            dismissButton = { TextButton(onClick = { interruptedEntry = null }) { Text(stringResource(R.string.resume_prompt_later)) } },
        )
    }

    // Back inside the stack: Capture/Session → Studio (sheets keep their own handler below).
    BackHandler(
        s.screen != Screen.Studio && !s.showConfigSheet && !s.showPodcastSheet &&
            !s.showAddSourceSheet && !s.showYouTubeSheet,
    ) { vm.leaveToStudio() }

    // Back during a session-wide search closes the search first: leaving the meeting while the
    // transcript is still on screen loses the bar and the results list (iOS: back stays in the
    // view). The bar's ✕ still does the same thing; this catches the hardware button and gesture.
    BackHandler(
        (s.screen == Screen.Session || s.screen == Screen.Capture) &&
            s.searchActive && !s.showConfigSheet && !s.showPodcastSheet &&
            !s.showAddSourceSheet && !s.showYouTubeSheet && !s.showExportSheet,
    ) { closeSearch() }

    when (s.screen) {
        Screen.Studio -> {
            // Read once in place (so the first frame is not an empty library), then off the main
            // thread on every change: list() opens one meta.json per meeting.
            var studioEntries by remember { mutableStateOf(SessionLibrary.list(context)) }
            var studioQueuedIds by remember { mutableStateOf(ProcessingQueue.ids(context).toSet()) }
            val dlChanges by studio.primeminutes.online.ImportDownloads.changes.collectAsState()
            val dlState by studio.primeminutes.online.ImportDownloads.state.collectAsState()
            LaunchedEffect(s.recentsVersion, s.queueItemId, dlChanges) {
                withContext(Dispatchers.IO) { SessionLibrary.list(context) to ProcessingQueue.ids(context).toSet() }
                    .let { (e, q) -> studioEntries = e; studioQueuedIds = q }
            }
            // Titles saved in the other script read in the chosen one (the file itself is untouched).
            val titleConv by produceState<OpenCcConverter?>(null, s.config.summaryScript) {
                value = withContext(Dispatchers.IO) {
                    runCatching { OpenCcConverter.get(context, SummaryScript.scriptFor(s.config.summaryScript, context)) }.getOrNull()
                }
            }
            val shownEntries = remember(studioEntries, titleConv) {
                titleConv?.let { c -> studioEntries.map { e -> e.copy(title = e.title?.let(c::convert)) } } ?: studioEntries
            }
            val peopleVersion by studio.primeminutes.core.people.PeopleStore.version.collectAsState()
            val peopleDoc by studio.primeminutes.core.people.PeopleStore.doc.collectAsState()
            val studioPeople by produceState(emptyList<Pair<studio.primeminutes.core.people.Person, Set<String>>>(), peopleVersion, peopleDoc, studioEntries) {
                value = withContext(Dispatchers.IO) {
                    val ids = studioEntries.map { it.id }.toSet()
                    val by = studio.primeminutes.core.people.PeopleStore.meetingsByPerson(studio.primeminutes.core.people.PeopleStore.allLinks(context))
                    peopleDoc.people.mapNotNull { p -> by[p.id]?.filter { it in ids }?.toSet()?.takeIf { it.isNotEmpty() }?.let { p to it } }
                        .sortedWith(compareByDescending<Pair<studio.primeminutes.core.people.Person, Set<String>>> { it.second.size }.thenBy { it.first.name })
                }
            }
            StudioScreen(
                entries = shownEntries,
                queuedIds = studioQueuedIds,
                processingId = s.queueItemId,
                processingLabel = s.queueLabel,
                processingFraction = s.queueFraction,
                isRecording = s.isRecording,
                recSeconds = recSeconds,
                // !deferStopped: after ⏹ Stop&save, running stays true until the deferred Complete
                // lands — that short window must not flash a "processing: saved for later" banner.
                foregroundRun = s.running && !s.watchingQueue && !s.deferStopped,
                foregroundLabel = s.title ?: s.status,
                onResumeSession = { vm.showScreen(Screen.Session) },
                // The screen's own run over a library item (a downloaded podcast / YouTube item, a Resume):
                // that row is live, and tapping it returns to the run.
                foregroundId = s.libraryDir?.name?.takeIf { s.running && !s.watchingQueue && !s.deferStopped },
                foregroundFraction = s.progress,
                downloadState = dlState,
                onOpenDownload = { e -> vm.openDownload(e) },
                onRetryDownload = { e -> studio.primeminutes.online.ImportDownloads.retry(context, e) },
                // Exclude already-queued items: they show the Queued glyph on their row.
                pendingCount = studioEntries.count {
                    it.status == SessionLibrary.Status.RECORDED && it.wavFile.exists() && it.id !in studioQueuedIds
                },
                onRecord = { requestRecord() },
                onResumeCapture = { vm.showScreen(Screen.Capture) },
                // audioFile, not sessionFile: a DONE entry whose session.m4a is missing still has its
                // raw capture — open that (→ re-transcribe) instead of a dead toast.
                onOpen = { e -> vm.openSessionUri(Uri.fromFile(e.audioFile)) },
                onOpenAt = { e, t -> pendingSeekSec = t; vm.openSessionUri(Uri.fromFile(e.audioFile)) },
                searchModelReady = searchModelReady,
                initialQuery = devQuery,
                typeQuery = devExtras?.getString("vox_type") ?: "",
                // Ask shows once its models are there (AskEngine.available).
                onAsk = if (s.config.askEnabled && askModelReady) { q -> askQuestion = q.trim() } else null,
                searchDownload = searchDownload,
                onDownloadSearchModel = { downloadSearchModel() },
                indexingIds = indexingIds,
                onWatchLive = { e -> vm.watchQueueItem(e) },
                onProcessNow = { e -> if (e.sourceOnly) processDownloaded(e) else vm.enqueueAndStart(listOf(e.id)) },
                onResume = { e -> vm.resumeEntry(e) },
                onRemoveFromQueue = { e ->
                    scope.launch { withContext(Dispatchers.IO) { ProcessingQueue.remove(context, e.id) }; vm.bumpRecents() }
                },
                // Stop the drain: ACTION_STOP cancels the pipeline job; the current item stays
                // RECORDED and queued items remain, so "Process pending" resumes them.
                onStopProcessing = { onStop() },
                onProcessAll = {
                    vm.enqueueAndStart(
                        SessionLibrary.list(context)
                            .filter { it.status == SessionLibrary.Status.RECORDED && it.wavFile.exists() }
                            .map { it.id },
                    )
                },
                onRename = { e, n ->
                    scope.launch { withContext(Dispatchers.IO) { SessionLibrary.rename(context, e.dir, n) }; vm.bumpRecents() }
                },
                onShareAudio = { e -> shareEntryAudio(e) },
                onDelete = { e -> vm.deleteEntries(listOf(e)) },
                onDeleteMany = vm::deleteEntries,
                onImport = { vm.showAddSourceSheet(true) },
                onSettings = { vm.showConfigSheet(true) },
                updateBanner = updateBannerSlot,
                people = studioPeople,
            )
        }
        Screen.Capture -> CaptureScreen(
            isRecording = s.isRecording,
            recSeconds = recSeconds,
            micLevel = s.micLevel,
            utterances = utterances,
            stable = s.liveStable,
            agent = agent,
            notice = s.status.takeIf { it != context.getString(R.string.status_recording) },
            onNextTalk = { vm.nextTalk() },
            onStop = { vm.stop() },
            onBack = { vm.showScreen(Screen.Studio) },
        )
        Screen.Session ->
    Scaffold(
        modifier = Modifier.fillMaxSize().background(pal.Slate900Grad),
        containerColor = Color.Transparent,
        topBar = {
            // No generated title yet (just recorded / still processing): show the entry's provisional
            // name, as the library row does, rather than the app name.
            val entryTitle = remember(s.libraryDir, s.recentsVersion) {
                s.libraryDir?.let { d -> SessionLibrary.byId(context, d.name) }?.let { it.title ?: SessionLibrary.defaultTitle(context, it.createdAt) }
            }
            val resumableEntry = remember(s.libraryDir, s.recentsVersion, s.running) {
                s.libraryDir?.let { d -> SessionLibrary.byId(context, d.name) }
                    ?.takeIf { SessionLibrary.hasProgress(it) }
            }
            SessionTopBar(
                cover = null,   // no per-session art (the generated identicon was removed)
                // The title lives in the top bar on every tab (tap it to edit); the two-pane overview
                // carries its own title card, so the bar stays blank there.
                title = if (twoPane && !s.title.isNullOrBlank()) "" else refs(s.title, screen = true) ?: entryTitle,
                onTitleClick = if (!twoPane && !s.title.isNullOrBlank() && !s.running) ({ sessTab = 0; vm.editTitle(true) }) else null,
                status = s.status,
                running = s.running,
                progress = s.progress,
                transcriptAvailable = utterances.isNotEmpty(),
                statusIsError = s.statusIsError,
                // Same contract as the system BackHandler: leaving a watched queue item also releases
                // the borrowed running flag, or Studio paints a stuck phantom banner.
                onBack = { vm.leaveToStudio() },
                onStop = { vm.stop() },
                canUndo = studio.primeminutes.session.SessionReducer.canUndoEdit(s),
                canRedo = studio.primeminutes.session.SessionReducer.canRedoEdit(s),
                onUndo = { vm.undoEdit() }, onRedo = { vm.redoEdit() },
                showNextTalk = s.running && s.recordingRun && !s.isRecording,
                onNextTalk = { vm.nextTalk() },
                canExport = utterances.isNotEmpty() && !s.running,
                // All re-run actions are disabled while a run is in flight. Re-transcribe only needs
                // the audio source, NOT a completed transcript (a stopped first transcription must be
                // retryable).
                canReTranscribe = !s.running && s.audioUri != null,
                onReTranscribe = { vm.reTranscribe() },
                // Nothing was said: no AI notes to write (the reader never runs on a silent meeting).
                canReSummarize = s.transcriptReady && !s.running &&
                    remember(s.utterances) { studio.primeminutes.core.library.SpeechCheck.hasSpeech(s.utterances.map { it.text }) },
                onReSummarize = { vm.reSummarizeFromMenu() },
                canResume = !s.running && resumableEntry != null,
                onResume = { resumableEntry?.let { vm.resumeEntry(it) } },
                onSearch = {
                    sessTab = 1
                    val on = !vm.now.searchActive
                    vm.setSearch(on, if (on) vm.now.searchQuery else "")
                },
                onSettings = { vm.showConfigSheet(true) },
                // No pre-decode here; the picker callback hands the build+write to the service.
                onOpenExport = { vm.showExportSheet(true) },
                onEmailParticipants = { mailOpen = true },
                onAskMeeting = if (askMeetingAvailable) ({ askMeeting = "" }) else null,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Docked "now playing" bar — like a music player, the controls stay pinned at the
            // bottom while the transcript scrolls above.
            if (player != null) {
                fun doSeek(ms: Int) {
                    lastUserScrollNanos = 0L   // an explicit jump: the highlight is followed again at once
                    positionMs = ms.coerceIn(0, durationMs)
                    resumeOrRecover(positionMs)
                }
                Surface(color = pal.PanelSurface, tonalElevation = 3.dp,
                    modifier = Modifier.onGloballyPositioned { playerBarPx = it.size.height }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Spacing.lg),
                    ) {
                        PlayerBar(
                            utterances = utterances,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            dragMs = dragMs,
                            isPlaying = isPlaying,
                            volume = volume,
                            muted = muted,
                            activeIndex = activeIndex,
                            onPlayPause = {
                                val p = player ?: return@PlayerBar
                                if (isPlaying) { runCatching { p.pause() }; isPlaying = false; buffering = false }
                                else resumeOrRecover(null)
                            },
                            onSeekTo = { doSeek(it) },
                            onDragChange = { dragMs = it },
                            onSkip = { delta -> doSeek((dragMs ?: positionMs) + delta) },
                            onVolume = { v -> volume = v; muted = v == 0f; player?.setVolume(v, v) },
                            onToggleMute = {
                                muted = !muted
                                val v = if (muted) 0f else volume.coerceAtLeast(0.05f).also { volume = it }
                                player?.setVolume(v, v)
                            },
                            compact = landscape,
                            buffering = buffering,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = Spacing.lg),
        ) {
            Spacer(Modifier.height(10.dp))
            updateBannerSlot()
            // Add audio / Stop / Re-run live in the top bar (top = functions, middle = text,
            // bottom = player), so the content area is just the empty state or the transcript.

            if (isEmptyState) {
                EmptyState(
                    onAddSource = { vm.showAddSourceSheet(true) },
                    modifier = Modifier.weight(1f),
                )
            } else if (s.downloadEntryId != null && utterances.isEmpty() && !s.running) {
                // A podcast / YouTube item not downloaded yet: its download card alone (no tabs, nothing to read yet).
                Spacer(Modifier.height(Spacing.sm))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { summaryCards() }
            } else if (twoPane) {
                // Landscape: two panes — the overview on the left scrolls independently of the
                // transcript on the right.
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier
                            .weight(0.40f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        overviewCards()
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(0.60f).fillMaxHeight()) {
                        if (s.searchActive) TranscriptSearchBar(
                            query = s.searchQuery, onQuery = { vm.setSearchQuery(it) },
                            matchCount = searchMatches.size, matchPos = matchPos,
                            onPrev = { searchPrev() }, onNext = { searchNext() }, onClose = { closeSearch() },
                            semanticAvailable = semanticAvailable, semantic = semanticSearch,
                            onSemantic = { semanticSearch = !semanticSearch }, indexing = semanticIndexing,
                            resultsOpen = resultsOpen && (meetingResults.isNotEmpty() || semanticIndexing), onToggleResults = { resultsOpen = !resultsOpen },
                            onAsk = if (askMeetingAvailable) ({ askMeeting = s.searchQuery.trim() }) else null,
                        )
                        if (s.searchActive && resultsOpen && (meetingResults.isNotEmpty() || semanticIndexing)) studio.primeminutes.ui.MeetingSearchResults(
                            meetingResults, s.searchQuery, speakerName = { speakerLabel(it) }, onPick = { pickResult(it) },
                            onAsk = if (askMeetingAvailable) ({ askMeeting = s.searchQuery.trim() }) else null,
                            indexing = semanticIndexing,
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            content = transcriptItems,
                        )
                    }
                }
            } else {
                // Portrait: Summary · Transcript tabs — each job gets a shallow room
                // instead of one long scroll (Prime Minutes 2.0).
                SessionTabs(selected = sessTab, onSelect = { sessTab = it })
                Spacer(Modifier.height(Spacing.sm))
                when (sessTab) {
                    0 -> Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    ) { summaryCards(); Spacer(Modifier.height(Spacing.sm)) }
                    else -> {
                        if (s.searchActive) TranscriptSearchBar(
                            query = s.searchQuery, onQuery = { vm.setSearchQuery(it) },
                            matchCount = searchMatches.size, matchPos = matchPos,
                            onPrev = { searchPrev() }, onNext = { searchNext() }, onClose = { closeSearch() },
                            semanticAvailable = semanticAvailable, semantic = semanticSearch,
                            onSemantic = { semanticSearch = !semanticSearch }, indexing = semanticIndexing,
                            resultsOpen = resultsOpen && (meetingResults.isNotEmpty() || semanticIndexing), onToggleResults = { resultsOpen = !resultsOpen },
                            onAsk = if (askMeetingAvailable) ({ askMeeting = s.searchQuery.trim() }) else null,
                        )
                        if (s.searchActive && resultsOpen && (meetingResults.isNotEmpty() || semanticIndexing)) studio.primeminutes.ui.MeetingSearchResults(
                            meetingResults, s.searchQuery, speakerName = { speakerLabel(it) }, onPick = { pickResult(it) },
                            onAsk = if (askMeetingAvailable) ({ askMeeting = s.searchQuery.trim() }) else null,
                            indexing = semanticIndexing,
                        )
<<<<<<< HEAD
                        // A silent meeting and a not-yet-transcribed one are different states: the
                        // entry's persisted no-speech flag (SpeechCheck at save, like iOS) tells
                        // them apart once the transcript is in. While it loads, say nothing.
                        if (utterances.isEmpty() && !s.running && !s.opening && s.transcriptReady) {
                            val silent = meetingEntry?.noSpeech == true
                            Column(modifier = Modifier.padding(top = Spacing.xxl)) {
                                Text(
                                    stringResource(if (silent) R.string.status_no_speech else R.string.transcript_empty_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = pal.Slate200,
                                )
                                if (!silent) Text(
                                    stringResource(R.string.transcript_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = pal.Slate400,
                                )
                            }
                        }
=======
                        if (utterances.isEmpty() && !s.running) Text(
                            stringResource(R.string.status_no_speech),
                            color = pal.Slate400,
                            modifier = Modifier.padding(top = Spacing.xxl),
                        )
>>>>>>> origin/ui-android-aesthetic
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                        ) {
                            transcriptItems(this)
                        }
                    }
                }
            }
        }
    }
    }   // end when(screen)

    // A status line is a string made when its event happened; after a language switch an idle one
    // would stay in the old language, so it is rebuilt (or dropped) instead. Runs and errors keep theirs.
    val uiLangCode = LocalLanguageController.current.code
    LaunchedEffect(uiLangCode) { vm.languageChanged() }
    // A hand-edited transcript makes its summary/action-items children stale → offer a one-tap re-summarize (once per
    // edit episode; the flag stays set while the snackbar shows so further edits don't stack it).
    LaunchedEffect(s.transcriptDirty) {
        // Only on the Session screen: this snackbar carries a Re-summarize action.
        if (s.transcriptDirty && (!s.summary.isNullOrBlank() || s.actionItems != null) && !s.running && s.screen == Screen.Session) {
            val res = snackbarHostState.showSnackbar(
                message = context.getString(R.string.transcript_changed_resummarize),
                actionLabel = context.getString(R.string.re_summarize),
                duration = SnackbarDuration.Long,
            )
            vm.clearTranscriptDirty()
            if (res == SnackbarResult.ActionPerformed) vm.regenerateStaleChildren()
        }
    }
    // A session saved in the other script (or by an older build) reads in the chosen one as soon as
    // it is opened. A no-op, and no rewrite of the file, when it already matches.
    LaunchedEffect(s.sessionGen) {
        if (s.utterances.isNotEmpty() && !s.running) {
            vm.applyChineseScript(SummaryScript.scriptFor(s.config.summaryScript, context))
        }
    }
    // When Settings closes after a change that needs the LLM (and a summary exists), offer a one-tap
    // re-summarize. Keyed on `screen` too: a flag raised while in Studio surfaces when the session
    // is next opened.
    LaunchedEffect(s.showConfigSheet, s.screen, s.running) {
        if (!s.showConfigSheet && s.transcriptStale && !s.running && s.screen == Screen.Session) {
            vm.clearTranscriptStale()
            // Informational only — deliberately NO action button: re-transcribing discards the
            // summary, hand edits and speaker names; the user takes that step through the ⋮ menu.
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.asr_language_changed),
                duration = SnackbarDuration.Long,
            )
        }
        if (!s.showConfigSheet && s.summaryStale && !s.summary.isNullOrBlank() && !s.running && s.screen == Screen.Session) {
            vm.clearSummaryStale()
            val res = snackbarHostState.showSnackbar(
                message = context.getString(R.string.summary_settings_changed),
                actionLabel = context.getString(R.string.re_summarize),
                duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) vm.regenerateStaleChildren()   // re-titles + re-extracts too
        }
    }
    if (s.showConfigSheet) {
        ConfigSheet(
            config = s.config,
            enabled = !s.running,
            onChange = { newCfg -> vm.configChanged(newCfg) },
            onDismiss = { vm.showConfigSheet(false) },
            // Manual "Check for updates" found a newer release → surface the banner, close settings.
            onUpdateFound = { info -> updateInfo = info; updateDismissed = false; vm.showConfigSheet(false) },
            onOpenPeople = { vm.showConfigSheet(false); peopleOpen = true },
        )
    }
    studio.primeminutes.ui.VoiceOutlierPrompt()
    if (peopleOpen) {
        studio.primeminutes.ui.PeopleScreen(
            onClose = { peopleOpen = false },
            onOpenMeeting = { e, t -> peopleOpen = false; pendingSeekSec = t; vm.openSessionUri(Uri.fromFile(e.audioFile)) },
            voiceHeader = { studio.primeminutes.ui.VoiceSettingsCard() },
            voiceOfPerson = { p -> studio.primeminutes.ui.PersonVoiceRow(p) },
        )
    }
    // Podcast/YouTube items: their row and their screen show the download (no floating card any more).
    // A finished download is processed as soon as nothing else runs (one job at a time); a failure is said
    // once here, and stays on the item.
    val dlReady by studio.primeminutes.online.ImportDownloads.ready.collectAsState()
    val runJobs by RunRegistry.jobs.collectAsState()
    LaunchedEffect(dlReady, runJobs.busy, s.isRecording) {
        val id = dlReady.firstOrNull() ?: return@LaunchedEffect
        if (runJobs.busy || s.isRecording) return@LaunchedEffect
        val e = withContext(Dispatchers.IO) { SessionLibrary.byId(context, id) }
        // Consumed only after the last suspension: it changes this effect's key, which cancels it.
        studio.primeminutes.online.ImportDownloads.consumeReady(id)
        if (e != null && e.sourceOnly) withModels { vm.processDownloaded(e) }
    }
    val dlFailure by studio.primeminutes.online.ImportDownloads.failure.collectAsState()
    LaunchedEffect(dlFailure) {
        dlFailure?.let { vm.notify(context.getString(R.string.dl_failed_reason, it)); studio.primeminutes.online.ImportDownloads.consumeFailure() }
    }
    if (s.showPodcastSheet) {
        PodcastSheet(onDismiss = { vm.showPodcastSheet(false) })
    }
    boundarySeam?.let { seamIdx ->
        val us = s.utterances
        if (seamIdx !in 1 until us.size) { boundarySeam = null; return@let }
        val a = us[seamIdx - 1]; val b = us[seamIdx]
        val sm = remember(a, b) { studio.primeminutes.data.BoundaryEdits.seam(a, b) }
        val nm = { sid: Int? -> sid?.let { s.speakerNames[it]?.name ?: context.getString(R.string.speaker_n, it + 1) } ?: "" }
        val col = { sid: Int? -> Color(speakerColorOn(sid ?: 0, pal.isDark)) }
        studio.primeminutes.ui.BoundaryEditorSheet(
            tokens = sm.tokens, starts = sm.starts, end = sm.end, initialSplit = sm.split,
            colorA = col(a.speaker), labelA = nm(a.speaker), colorB = col(b.speaker), labelB = nm(b.speaker),
            estimated = remember(a, b) { studio.primeminutes.data.BoundaryEdits.run { estimated(a) || estimated(b) } },
            canPlay = s.audioUri != null,
            onPreview = { from, to -> boundaryPreview(from, to) },
            onApply = { k, _ -> stopBoundaryPreview(); boundarySeam = null; vm.moveBoundary(seamIdx, k) },
            onDismiss = { stopBoundaryPreview(); boundarySeam = null },
        )
    }
    splitLineAt?.let { li ->
        val us = s.utterances
        if (li !in us.indices) { splitLineAt = null; return@let }
        val u = us[li]
        val toks = remember(u) { studio.primeminutes.data.BoundaryEdits.tokenize(u.text) }
        val starts = remember(u) { studio.primeminutes.data.BoundaryEdits.tokenStarts(u, toks) }
        val nm = { sid: Int -> s.speakerNames[sid]?.name ?: context.getString(R.string.speaker_n, sid + 1) }
        val col = { sid: Int -> Color(speakerColorOn(sid, pal.isDark)) }
        // The likeliest owner of the rest first: the neighbouring lines' other speaker, then the others,
        // then a new speaker.
        val order = remember(us, li) { studio.primeminutes.data.BoundaryEdits.splitCandidates(us, li) }
        val known = us.mapNotNull { it.speaker }.toSet()
        studio.primeminutes.ui.BoundaryEditorSheet(
            tokens = toks, starts = starts, end = u.endSec, initialSplit = toks.size / 2,
            colorA = col(u.speaker ?: 0), labelA = nm(u.speaker ?: 0), colorB = col(order.first()), labelB = nm(order.first()),
            choices = order.map { studio.primeminutes.ui.SpeakerChoice(it, nm(it), col(it), isNew = it !in known) },
            estimated = remember(u) { studio.primeminutes.data.BoundaryEdits.estimated(u) },
            canPlay = s.audioUri != null,
            onPreview = { from, to -> boundaryPreview(from, to) },
            onApply = { k, spk -> stopBoundaryPreview(); splitLineAt = null; vm.splitLine(li, k, spk ?: order.first()) },
            onDismiss = { stopBoundaryPreview(); splitLineAt = null },
        )
    }
    mergeAsk?.let { (from, into) ->
        val name = { sid: Int -> s.speakerNames[sid]?.name ?: context.getString(R.string.speaker_n, sid + 1) }
        AlertDialog(
            onDismissRequest = { mergeAsk = null },
            text = { Text(stringResource(R.string.speaker_merge_confirm, name(from), name(into))) },
            confirmButton = {
                TextButton(onClick = { mergeAsk = null; vm.mergeSpeaker(from, into) }) { Text(stringResource(R.string.speaker_merge)) }
            },
            dismissButton = { TextButton(onClick = { mergeAsk = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Naming a speaker: the people picker (docs/design/speaker-tags.md §2.1).
    s.editingSpeakerId?.let { sid ->
        val pickerSpeakers = speakerIds.map { studio.primeminutes.ui.PickerSpeaker(it, speakerLabel(it), s.speakerNames[it]?.person) }
        val me = pickerSpeakers.firstOrNull { it.id == sid } ?: studio.primeminutes.ui.PickerSpeaker(sid, speakerLabel(sid), s.speakerNames[sid]?.person)
        val voice = studio.primeminutes.ui.rememberVoiceSuggestions(s.libraryDir, sid, s.speakerNames, s.utterances)
        studio.primeminutes.ui.SpeakerNameFlow(
            speaker = me, speakers = pickerSpeakers,
            suggestions = voice.first, voiceNote = voice.second,
            onPlaySample = if (s.audioUri != null) ({
                studio.primeminutes.core.people.CleanSpeech.sampleStart(s.utterances, sid)?.let { t -> seekAndPlay((t * 1000).toInt()) }
            }) else null,
            onLink = { p -> vm.linkPerson(sid, p.id) },
            onNew = { n -> vm.linkNewPerson(sid, n) },
            onClear = { vm.clearSpeakerName(sid) },
            onMerge = { into -> vm.cancelSpeakerEdit(); vm.mergeSpeaker(sid, into) },
            onDismiss = { vm.cancelSpeakerEdit() },
        )
    }
    askQuestion?.let { q ->
        if (!ModelManager(context).let { it.llmReady(it.readerSpec(s.config.llmModelId)) }) {
            LaunchedEffect(q) {
                Toast.makeText(context, R.string.ask_no_model, Toast.LENGTH_LONG).show(); askQuestion = null
            }
        } else studio.primeminutes.ui.AskSheet(
            question = q, entries = remember(s.recentsVersion) { SessionLibrary.list(context) },
            onOpenAt = { e, t -> askQuestion = null; pendingSeekSec = t; vm.openSessionUri(Uri.fromFile(e.audioFile)) },
            onDismiss = { askQuestion = null },
        )
    }
    // In-meeting Ask: this meeting's excerpts only; a citation moves the transcript and player there.
    val askScope = meetingEntry
    if (askMeeting != null && askScope != null) studio.primeminutes.ui.AskSheet(
        question = askMeeting ?: "", entries = listOf(askScope), scope = askScope,
        onOpenAt = { _, t -> askMeeting = null; resultsOpen = false; pendingSeekSec = t },
        onDismiss = { askMeeting = null },
    )
    // DEV: `--es vox_ask_meeting "…"` opens the in-meeting Ask on the open meeting (device checks, screenshots).
    var devAskMeetingAsked by remember { mutableStateOf(false) }
    if (devExtras?.getString("vox_ask_meeting") != null && !devAskMeetingAsked) LaunchedEffect(s.utterances.size, askScope) {
        if (s.utterances.isEmpty() || askScope == null) return@LaunchedEffect
        studio.primeminutes.core.ask.AskEngine.devRepeat = devExtras.getInt("vox_ask_repeat")
        delay(3_000); devAskMeetingAsked = true; askMeeting = devExtras.getString("vox_ask_meeting")!!.trim()
    }
    if (mailOpen && s.utterances.isNotEmpty()) studio.primeminutes.ui.EmailParticipantsFlow(
        s = s, notes = agent.notes.toList(), speakerLabel = speakerLabel,
        exportBaseName = ::exportBaseName, transcriptText = ::transcriptText, exportExtras = ::exportExtras,
        refs = { refs(it) }, notify = { vm.notify(it) },
        // Like the transcript's speaker names: off while a run relabels the speakers.
        onNameSpeaker = if (s.running) null else ({ sid -> mailOpen = false; vm.beginSpeakerEdit(sid, s.utterances.indexOfFirst { it.speaker == sid }.coerceAtLeast(0)) }),
        onDismiss = { mailOpen = false },
    )
    // DEV: `--ez vox_mail true` opens the participants sheet on the open meeting (device checks, screenshots).
    var devMailAsked by remember { mutableStateOf(false) }
    if (devExtras?.getBoolean("vox_mail") == true && !devMailAsked) LaunchedEffect(s.utterances.size) {
        if (s.utterances.isEmpty()) return@LaunchedEffect
        delay(3_000); devMailAsked = true; mailOpen = true
    }
    if (s.showExportSheet) {
        val exportHaptic = LocalHapticFeedback.current
        ExportSheet(
            onExport = { f, share -> onExport(f, share) },
            onCopyTranscript = { copyTranscript(); exportHaptic.performHapticFeedback(HapticFeedbackType.ContextClick) },
            onDismiss = { vm.showExportSheet(false) },
        )
    }
    if (s.showAddSourceSheet) {
        AddSourceSheet(
            onPickFile = { picker.launch(arrayOf("audio/*", "video/*")) },
            onRecord = { requestRecord() },
            onPodcast = { vm.showPodcastSheet(true) },
            onYouTube = { vm.showYouTubeSheet(true) },
            onOpenSession = { sessionOpener.launch(arrayOf("audio/ogg", "application/ogg", "audio/mp4", "audio/x-m4a", "*/*")) },
            onDismiss = { vm.showAddSourceSheet(false) },
        )
    }
    if (s.showYouTubeSheet) {
        YouTubeSheet(onDismiss = { vm.showYouTubeSheet(false) })
    }
    // An export that lands, felt — iOS fires `Haptics.success()` on the same completion. Armed on
    // the exporting edge, so a screen that opens while an export is already running stays silent.
    val exportDoneHaptic = LocalHapticFeedback.current
    var wasExporting by remember { mutableStateOf(false) }
    LaunchedEffect(s.exporting) {
        if (s.exporting) wasExporting = true
        else if (wasExporting) { wasExporting = false; exportDoneHaptic.performHapticFeedback(HapticFeedbackType.ContextClick) }
    }
    // The overlay waits for TranscriptEvent.ExportDone, which never arrives if the service is
    // killed mid-export: once nothing is exporting in the service any more and no completion has
    // landed, close it and say so.
    if (s.exporting) {
        LaunchedEffect(Unit) {
            delay(2_000)   // let the service actually start and bump the counter
            while (vm.now.exporting && RunRegistry.exportsInFlight > 0) delay(1_000)
            if (vm.now.exporting) {
                vm.setExporting(false)
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.export_interrupted),
                    duration = SnackbarDuration.Long,
                )
            }
        }
        ExportingOverlay(onDismiss = { vm.setExporting(false) })
    }
    if (s.opening) OpeningOverlay()
    BackHandler(s.showConfigSheet || s.showPodcastSheet || s.showAddSourceSheet || s.showYouTubeSheet || s.showExportSheet) {
        vm.closeSheets()
    }
}
