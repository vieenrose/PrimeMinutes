package studio.primeminutes.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R

/**
 * Quick Settings tile (see [RecordEntry]): tap to record, the tile turns active with "Stop"; tap
 * again to stop and save. Up to Android 13 the tap starts the service directly; on 14+ (which
 * refuses it the microphone), or when the mic is not granted or models need a download, the app
 * opens on its record button and records from there.
 */
class RecordTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var watch: kotlinx.coroutines.Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // While the shade shows the tile, follow the recording (started or stopped anywhere).
        watch?.cancel()
        watch = scope.launch { studio.primeminutes.service.RunRegistry.jobs.collect { render() } }
    }

    override fun onStopListening() {
        watch?.cancel(); watch = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val s = RecordEntry.state()
        if (s.recording) { send(); return }
        // Android 14+ refuses the microphone to a service a tile starts while the app is in the
        // background (SecurityException, measured on an A53; Android 12 allows it): open the app,
        // which starts the recording from its record button.
        if (Build.VERSION.SDK_INT >= 34) { openApp(); return }
        scope.launch {
            val direct = withContext(Dispatchers.IO) { RecordEntry.canStartDirectly(this@RecordTileService) }
            if (!direct || !send()) openApp()
        }
    }

    /** Start (or stop) through the service; false when the system refused the start. */
    private fun send(): Boolean = runCatching {
        ContextCompat.startForegroundService(this, RecordEntry.toggleIntent(this))
    }.onFailure { Log.w(TAG, "tile: service start refused, opening the app", it) }.isSuccess

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = RecordEntry.openAppIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val s = RecordEntry.state()
        tile.state = if (s.recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (s.recording) R.string.rec_entry_recording else R.string.rec_entry_start)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (s.recording) getString(R.string.rec_entry_stop) else null
        tile.icon = Icon.createWithResource(this, if (s.recording) R.drawable.ic_tile_stop else R.drawable.ic_stat_mic)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object { const val TAG = "primeminutes-recentry" }
}
