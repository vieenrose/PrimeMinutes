package studio.primeminutes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.models.SizeFormat
import studio.primeminutes.online.ImportDownloads
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/** The running download of [entry], or null (waiting its turn, failed, or not a download item). */
fun runningDownload(entry: SessionLibrary.Entry, state: ImportDownloads.State): ImportDownloads.State.Running? =
    (state as? ImportDownloads.State.Running)?.takeIf { it.entryId == entry.id && entry.download != null }

/**
 * A download item's state in one line: "Downloading… 45% · 12 MB / 30 MB", "Download paused ·
 * resumes automatically · 12 MB / 30 MB", "Waiting to download", "Download failed: <reason>".
 */
@Composable
fun downloadLine(entry: SessionLibrary.Entry, state: ImportDownloads.State): String {
    val d = entry.download ?: return ""
    d.error?.let { return stringResource(R.string.dl_failed_reason, it) }
    val r = runningDownload(entry, state) ?: return stringResource(R.string.dl_waiting)
    val pct = r.progress?.takeIf { !r.paused }?.let { " ${(it * 100).toInt()}%" } ?: ""
    val bytes = when {
        r.total > 0 -> " · ${SizeFormat.bytes(r.bytes)} / ${SizeFormat.bytes(r.total)}"
        r.bytes > 0 -> " · ${SizeFormat.bytes(r.bytes)}"
        else -> ""
    }
    return stringResource(r.stageRes) + pct + bytes
}

/**
 * The download card on a download item's own screen (the library row shows the same line): the
 * state, a bar, and what can be done — Resume now while paused, Retry after a failure, Delete.
 */
@Composable
fun ItemDownloadCard(
    entry: SessionLibrary.Entry,
    state: ImportDownloads.State,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // Delete here removes the whole item (its partial audio included) exactly as the library row's
    // delete does, so it confirms the same way — the card's own Delete used to fire on the first tap.
    var confirming by remember { mutableStateOf(false) }
    val d = entry.download ?: return
    val r = runningDownload(entry, state)
    Surface(
        color = pal.PanelSurface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, pal.Hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                downloadLine(entry, state),
                color = if (d.error != null) PrimeMinutesPalette.Red else pal.Slate200,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (d.error == null) {
                val barMod = Modifier.fillMaxWidth()
                val p = r?.progress
                if (p != null) LinearProgressIndicator(progress = { p }, color = pal.Sky, trackColor = pal.Slate700, modifier = barMod)
                else if (r != null && !r.paused && !pal.isEink) LinearProgressIndicator(color = pal.Sky, trackColor = pal.Slate700, modifier = barMod)
                Text(stringResource(R.string.dl_item_hint), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (r?.paused == true) TextButton(onClick = { ImportDownloads.resumeNow() }) { Text(stringResource(R.string.dl_resume)) }
                if (d.error != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.action_delete), color = PrimeMinutesPalette.Red) }
            }
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.action_delete)) },
        text = { Text(stringResource(R.string.delete_confirm, entry.title ?: SessionLibrary.defaultTitle(context, entry.createdAt))) },
        confirmButton = {
            TextButton(onClick = { confirming = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = PrimeMinutesPalette.Red) }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
