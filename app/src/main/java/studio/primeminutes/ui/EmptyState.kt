package studio.primeminutes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.core.session.RecentSession
import studio.primeminutes.ui.components.GradientButton
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/**
 * Blank-slate hero that states the product promise up front — fully on-device, offline, and yours
 * to keep — then the single call to action. Centred when there is room, but scrollable so nothing
 * is clipped on a short viewport (e.g. landscape). [modifier] should give it the remaining height
 * (weight) from the parent column.
 */
@Composable
fun EmptyState(
    onAddSource: () -> Unit,
    recents: List<RecentSession> = emptyList(),
    onOpenRecent: (RecentSession) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        // Wide + short: put the hero/CTA beside the pillars instead of stacking, so it fits the
        // short viewport without scrolling and uses the horizontal space.
        Row(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xxl, vertical = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).widthIn(max = 380.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Hero(iconSize = 56)
                GradientButton(stringResource(R.string.add_audio), Icons.Filled.Add, onClick = onAddSource)
            }
            Column(
                Modifier.weight(1f).widthIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Pillars()
                RecentList(recents, onOpenRecent)
            }
        }
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Hero(iconSize = 64)
            // The three pillars of the product — the reason this app exists.
            Column(
                Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Pillars()
            }
            GradientButton(stringResource(R.string.add_audio), Icons.Filled.Add, onClick = onAddSource)
            RecentList(recents, onOpenRecent)
        }
    }
}

/** Recent sessions on the blank slate — tap to reopen, skipping the file picker. Hidden when empty. */
@Composable
private fun RecentList(recents: List<RecentSession>, onOpen: (RecentSession) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    if (recents.isEmpty()) return
    Column(
        Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.recent_sessions),
            style = MaterialTheme.typography.labelLarge,
            color = pal.Slate400,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        recents.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable { onOpen(r) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    r.title.ifBlank { stringResource(R.string.recent_untitled) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.Slate200,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Hero(iconSize: Int) {
    val pal = LocalPrimeMinutesPalette.current
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(R.drawable.ic_brand),
        contentDescription = null,
        modifier = Modifier.size(iconSize.dp),
    )
    Text(
        stringResource(R.string.empty_headline),
        style = MaterialTheme.typography.titleMedium,
        color = pal.Slate200,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.empty_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = pal.Slate400,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Pillars() {
    Pillar(Icons.Filled.Lock, R.string.pillar_private_title, R.string.pillar_private_desc)
    Pillar(Icons.Filled.CloudOff, R.string.pillar_offline_title, R.string.pillar_offline_desc)
    Pillar(Icons.Filled.Savings, R.string.pillar_cost_title, R.string.pillar_cost_desc)
}

@Composable
private fun Pillar(icon: ImageVector, titleRes: Int, descRes: Int) {
    val pal = LocalPrimeMinutesPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = pal.Sky,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(pal.Sky.copy(alpha = 0.14f))
                .padding(9.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = pal.Slate200,
            )
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = pal.Slate400,
            )
        }
    }
}
