package studio.primeminutes.entry

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import studio.primeminutes.MainActivity
import studio.primeminutes.R
import studio.primeminutes.core.models.DownloadPlan
import studio.primeminutes.core.models.ModelPrefetch
import studio.primeminutes.service.RunRegistry
import studio.primeminutes.service.TranscriptionService

/**
 * Recording from outside the app: the launcher shortcut, the home-screen widget and the Quick
 * Settings tile. All three end in the in-app record button's own entry points — the shortcut opens
 * the app with [ACTION_START_RECORDING] (MainActivity runs its record button), the widget and the
 * tile send [TranscriptionService.ACTION_TOGGLE_RECORDING], which starts a recording exactly like
 * ACTION_RECORD or stops the live one exactly like the capture screen's ⏹ Stop & save.
 *
 * What the widget and the tile show follows [RunRegistry] (a recording started or stopped in the
 * app too). When the mic permission is missing, or the models still need a download the user has
 * not agreed to, they open the app to ask instead (the record button's own flow).
 */
object RecordEntry {
    private const val TAG = "primeminutes-recentry"

    /** MainActivity: press the record button (the shortcut, and the widget/tile when they cannot start it). */
    const val ACTION_START_RECORDING = "studio.primeminutes.action.START_RECORDING"
    private const val SHORTCUT_ID = "start_recording"

    /** What the widget and the tile show. [startedAtMs]: wall-clock start of the live capture. */
    data class State(val recording: Boolean, val startedAtMs: Long, val stopping: Boolean)

    fun state(j: RunRegistry.Jobs = RunRegistry.jobs.value): State {
        val run = j.pipeline?.takeIf { j.recordingJobActive && it.kind == RunRegistry.Kind.RECORDING }
        // A stop already asked: the button is back to ● while the capture winds down (a tap then is a no-op).
        return State(recording = run != null && j.captureStop == null, startedAtMs = run?.startedAtMs ?: 0L, stopping = run != null && j.captureStop != null)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var following = false

    /** Keep the widget and the tile in step with the service, for the life of the process. Idempotent. */
    fun follow(context: Context) {
        val app = context.applicationContext
        synchronized(this) { if (following) return; following = true }
        scope.launch {
            RunRegistry.jobs.map { state(it) }.distinctUntilChanged().collect { refresh(app, it) }
        }
    }

    /** Redraw every widget and ask the system to re-read the tile. */
    fun refresh(context: Context, s: State = state()) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            runCatching {
                val direct = canStartDirectly(app)
                val awm = AppWidgetManager.getInstance(app)
                val ids = awm.getAppWidgetIds(ComponentName(app, RecordWidget::class.java))
                if (ids.isNotEmpty()) awm.updateAppWidget(ids, widgetViews(app, s, direct))
            }.onFailure { Log.w(TAG, "widget refresh failed", it) }
            runCatching { TileService.requestListeningState(app, ComponentName(app, RecordTileService::class.java)) }
        }
    }

    /** The mic is granted and the models need no download the user has not agreed to (as the record
     *  button's `withModels`); otherwise a start goes through the app. File checks: off the main thread. */
    fun canStartDirectly(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val p = runCatching { ModelPrefetch.pending(context) }.getOrNull() ?: return false
        return DownloadPlan.decide(ModelPrefetch.consent(context), ModelPrefetch.unmetered(context), p) == DownloadPlan.Action.PROCEED
    }

    fun micGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The app, opened on its record button. */
    fun openAppIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_START_RECORDING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun toggleIntent(context: Context): Intent =
        Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_TOGGLE_RECORDING)

    private fun widgetViews(context: Context, s: State, direct: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_record)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Started from the widget itself, not through a receiver: "the service starts by interacting
        // with app widgets" is what exempts a microphone service from the while-in-use restriction.
        // A stop always goes to the service (it is running), whatever [direct] says.
        val pi = if (s.recording || direct) PendingIntent.getForegroundService(context, 0, toggleIntent(context), flags)
        else PendingIntent.getActivity(context, 0, openAppIntent(context), flags)
        v.setOnClickPendingIntent(R.id.widget_record_button, pi)
        v.setImageViewResource(R.id.widget_record_button, if (s.recording) R.drawable.widget_record_stop else R.drawable.widget_record_start)
        v.setContentDescription(R.id.widget_record_button,
            context.getString(if (s.recording) R.string.rec_entry_stop else R.string.rec_entry_start))
        if (s.recording) {
            // The Chronometer counts on elapsedRealtime: the capture's wall-clock start, moved onto it.
            val started = if (s.startedAtMs > 0) s.startedAtMs else System.currentTimeMillis()
            val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - started).coerceAtLeast(0L)
            v.setChronometer(R.id.widget_record_time, base, null, true)
            v.setViewVisibility(R.id.widget_record_time, android.view.View.VISIBLE)
        } else {
            v.setChronometer(R.id.widget_record_time, SystemClock.elapsedRealtime(), null, false)
            v.setViewVisibility(R.id.widget_record_time, android.view.View.GONE)
        }
        return v
    }

    /** The launcher shortcut (long-press the icon). Dynamic: a static one cannot name the test build's id. */
    fun publishShortcut(context: Context) {
        runCatching {
            val sc = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel(context.getString(R.string.rec_entry_start))
                .setLongLabel(context.getString(R.string.rec_entry_start))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_record))
                .setIntent(Intent(context, MainActivity::class.java).setAction(ACTION_START_RECORDING))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, sc)
        }.onFailure { Log.w(TAG, "shortcut not published", it) }
    }
}
