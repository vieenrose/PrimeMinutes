package studio.primeminutes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.core.models.ModelPrefetch
import studio.primeminutes.core.models.SizeFormat
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/**
 * The first-use model download (ModelPrefetch) while it runs: percent, bytes so far over the total, and
 * Cancel (Paused, with Resume, while an interruption is waited out). Shown wherever the user is likely to be (the library, empty or not, and Settings): it used to
 * be drawn only on the empty home, so after "Download now" a library with meetings showed nothing.
 */
@Composable
fun ModelDownloadCard(status: ModelPrefetch.Status, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPrimeMinutesPalette.current
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(pal.Fill)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "${SizeFormat.bytes(status.doneBytes)} / ${SizeFormat.bytes(status.totalBytes)}",
                color = pal.Slate200, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (status.paused) TextButton(onClick = { ModelPrefetch.resumeNow() }) { Text(stringResource(R.string.dl_resume)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        // Redesign: one line + bar per model (speech, AI notes), then the overall state below.
        status.parts.forEach { pt ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = Spacing.sm, top = 4.dp)) {
                Text(stringResource(if (pt.kind == "asr") R.string.dl_part_speech else R.string.dl_part_reader),
                    color = pal.Slate200, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(if (pt.fraction >= 1f) stringResource(R.string.dl_part_done) else "${(pt.fraction * 100).toInt()}% · ${SizeFormat.bytes(pt.bytes)}",
                    color = if (pt.fraction >= 1f) studio.primeminutes.ui.theme.PrimeMinutesPalette.Success else pal.Slate400,
                    style = MaterialTheme.typography.labelSmall)
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { pt.fraction }, color = pal.Sky, trackColor = pal.Slate700,
                modifier = Modifier.fillMaxWidth().padding(end = Spacing.sm, top = 2.dp).height(3.dp))
        }
        if (status.parts.isNotEmpty()) androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        if (status.paused) {
            // Interrupted (network lost, phone asleep): the partial files stay and it continues by itself.
            DownloadStatusBar(R.string.dl_paused, status.fraction, Modifier.padding(end = Spacing.sm))
        } else DownloadStatusBar(R.string.dl_prefetching, status.fraction, Modifier.padding(end = Spacing.sm))
    }
}
