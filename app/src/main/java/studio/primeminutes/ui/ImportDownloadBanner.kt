package studio.primeminutes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import studio.primeminutes.R
import studio.primeminutes.online.ImportDownloads
import studio.primeminutes.ui.components.DownloadStatusBar
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette

/**
 * Compact download card shown over any screen while a podcast/YouTube import downloads in the
 * service. A non-focusable popup, so the rest of the app stays usable; Cancel keeps the `.part`.
 * [above] lifts it over a docked bar (the session player) instead of covering it.
 */
@Composable
fun ImportDownloadBanner(state: ImportDownloads.State.Running, above: Dp = 0.dp, onCancel: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Popup(alignment = Alignment.BottomCenter) {
        Surface(
            color = pal.PanelSurface,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, pal.Hairline),
            modifier = Modifier.fillMaxWidth()
                .then(if (above > 0.dp) Modifier.padding(bottom = above) else Modifier.navigationBarsPadding())   // the bar already clears the nav bar
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(state.title ?: stringResource(R.string.import_download_title), color = pal.Slate200,
                        style = MaterialTheme.typography.titleSmall, maxLines = 1,
                        modifier = Modifier.weight(1f).padding(end = 8.dp))
                    if (state.stageRes == R.string.dl_paused) TextButton(onClick = { ImportDownloads.resumeNow() }) { Text(stringResource(R.string.dl_resume)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                DownloadStatusBar(state.stageRes, state.progress, Modifier.padding(end = 8.dp, bottom = 8.dp))
            }
        }
    }
}
