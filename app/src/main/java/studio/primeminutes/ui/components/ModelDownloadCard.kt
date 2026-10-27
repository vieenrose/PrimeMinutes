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
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(pal.Sky.copy(alpha = 0.10f))
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
        if (status.paused) {
            // Interrupted (network lost, phone asleep): the partial files stay and it continues by itself.
            DownloadStatusBar(R.string.dl_paused, status.fraction, Modifier.padding(end = Spacing.sm))
        } else DownloadStatusBar(R.string.dl_prefetching, status.fraction, Modifier.padding(end = Spacing.sm))
    }
}
