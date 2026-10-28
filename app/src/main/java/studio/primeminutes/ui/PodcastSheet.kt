package studio.primeminutes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading

/** Podcast search/browse/download in a bottom sheet (hosts the existing [PodcastPanel]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSheet(onDismiss: () -> Unit, onOpenShow: (String) -> Unit = {}) {
    val pal = LocalPrimeMinutesPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pal.Slate800,
    ) {
        Column(
            // imePadding: the sheet's URL field sat under the keyboard (no scroll past the last row).
            Modifier.fillMaxWidth().imePadding().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xxl),
        ) {
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(studio.primeminutes.R.string.source_podcast),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = pal.Slate200, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
            )
            PodcastPanel(onStarted = onDismiss, onOpenShow = { onDismiss(); onOpenShow(it) })
        }
    }
}
