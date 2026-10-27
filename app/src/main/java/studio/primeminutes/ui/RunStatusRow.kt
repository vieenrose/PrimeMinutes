package studio.primeminutes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import studio.primeminutes.R
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import studio.primeminutes.ui.theme.statusColor

/**
 * Pinned (non-scrolling) run-status row for the Session screen: while a run is in flight the
 * top bar's status scrolls out of mind and the scroll area itself says nothing, so the current
 * phase text plus the run's progress bar live here — above the transcript list and above the
 * summary scroll — until processing finishes. Idle layout is untouched (callers only emit this
 * while running). Reuses the SessionTopBar status/progress plumbing; no engine wiring.
 */
@Composable
fun RunStatusRow(
    status: String,
    progress: Float,
    transcriptAvailable: Boolean,
    statusIsError: Boolean,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPrimeMinutesPalette.current
    Column(
        modifier
            .testTag("run-status")
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
    ) {
        Text(
            status.ifBlank { stringResource(R.string.status_processing) },
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor(
                running = true,
                transcriptReady = transcriptAvailable,
                isError = statusIsError,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xs))
        if (progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                color = pal.Sky,
                trackColor = pal.Slate700,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(
                color = pal.Sky,
                trackColor = pal.Slate700,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
