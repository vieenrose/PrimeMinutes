package studio.primeminutes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/**
 * A docked status strip (the recording screen's minimized notes, the library's queue): a hairline on top,
 * a 48 dp row — status dot, one line, an optional action, › — taking its own layout space, never floating
 * over content. [progress]: a thin line under the row (null: none).
 */
@Composable
fun DockedStrip(
    onClick: () -> Unit,
    onClickLabel: String,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    progress: Float? = null,
    content: @Composable () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.Hairline))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) { content() }
            trailing?.invoke()
            Text("›", style = MaterialTheme.typography.titleMedium, color = pal.Slate400)
        }
        if (progress != null && (progress > 0f || pal.isEink)) LinearProgressIndicator(
            progress = { progress }, color = PrimeMinutesPalette.Success, trackColor = pal.Slate700,
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}
