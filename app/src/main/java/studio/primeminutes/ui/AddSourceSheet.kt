package studio.primeminutes.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading

/**
 * One place to add audio from any source — File / Podcast / Shows / Session as list rows,
 * so the main screen carries a single "Add audio" button instead of competing buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceSheet(
    onPickFile: () -> Unit,
    onRecord: () -> Unit,
    onPodcast: () -> Unit,
    onDismiss: () -> Unit,
    onShows: () -> Unit = {},
) {
    val pal = LocalPrimeMinutesPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pal.Slate800,
    ) {
        // Scrollable so every source stays reachable on a short (landscape) viewport.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            Text(
                stringResource(R.string.add_audio),
                style = MaterialTheme.typography.titleLarge,
                color = pal.Slate200,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm).semantics { heading() },
            )
            SourceRow(Icons.Filled.FolderOpen, stringResource(R.string.source_audio_file), stringResource(R.string.source_audio_file_desc)) { onDismiss(); onPickFile() }
            SourceRow(Icons.Filled.Podcasts, stringResource(R.string.source_podcast), stringResource(R.string.source_podcast_desc)) { onDismiss(); onPodcast() }
            SourceRow(Icons.Filled.Subscriptions, stringResource(R.string.sub_source), stringResource(R.string.sub_source_desc)) { onDismiss(); onShows() }
        }
    }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    ListItem(
        headlineContent = { Text(title, color = pal.Slate200) },
        supportingContent = { Text(subtitle, color = pal.Slate400) },
        leadingContent = { Icon(icon, contentDescription = null, tint = pal.Sky) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}
