package studio.primeminutes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/**
 * The brand primary CTA — a sky→indigo gradient pill with dark ink, matching the web app's
 * accent button. Gradient lives on the (transparent-container) button's own background so it
 * fills the whole shape; disabled state falls back to a flat slate.
 */
@Composable
fun GradientButton(
    text: String,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.background(
            brush = if (!enabled) SolidColor(pal.Slate700) else if (pal.isDark) SolidColor(pal.Sky) else pal.BrandGradient,
            shape = MaterialTheme.shapes.medium,
        ),
    ) {
        val fg = if (enabled) pal.Slate900 else pal.Slate400
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(text, color = fg, fontWeight = FontWeight.SemiBold)
            if (trailingIcon != null) {
                Spacer(Modifier.width(Spacing.xs))
                Icon(trailingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            }
        }
    }
}
