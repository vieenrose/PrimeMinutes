package studio.primeminutes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.core.hw.hwSamples
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/**
 * Hardware status as a row of hairline gauges — `CPU ▬▬▭ RAM ▬▭▭ ⚡ ▬▬▬` — while the engines work.
 * Bars, not numbers: the glance question is "is it maxed out?", not the exact percent. Sampling
 * runs only while this is on screen; a gauge turns red only when it is worth acting on (CPU or RAM
 * over 90 %, battery under 15 % off the charger, or a battery at 42 °C or more).
 */
@Composable
fun HwStatusLine(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (!remember { studio.primeminutes.core.config.ThemeStore.loadHwMonitor(ctx) }) return
    val s = remember { hwSamples(ctx) }.collectAsState(initial = null).value ?: return
    val hot = (s.tempC ?: 0) >= 42
    val cd = stringResource(R.string.hw_status_cd, s.cpuPct, s.ramPct, s.batteryPct)
    Row(
        modifier.clearAndSetSemantics { contentDescription = cd },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Gauge("CPU", s.cpuPct, s.cpuPct > 90)
        Gauge("RAM", s.ramPct, s.ramPct > 90)
        if (s.batteryPct >= 0) Gauge(if (s.charging) "BAT\u2009+" else "BAT", s.batteryPct,
            hot || (!s.charging && s.batteryPct < 15))
    }
}

@Composable
private fun Gauge(label: String, pct: Int, alert: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val fill = when {
        alert -> PrimeMinutesPalette.Red
        pal.isEink -> pal.Slate200
        // Grey, not the accent: the blue mic bars right above are the recording's own indicator.
        else -> pal.Slate400
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = pal.Slate400.copy(alpha = 0.7f))
        Box(Modifier.size(width = 16.dp, height = 2.dp).clip(RoundedCornerShape(1.dp))
            .background(pal.Slate400.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(pct.coerceIn(0, 100) / 100f).background(fill))
        }
    }
}

/** What the recording screen warns about, worst first; null = all fine. */
internal enum class CaptureWarn { HEAT, MEMORY, BATTERY, LOAD }

internal fun captureWarn(s: studio.primeminutes.core.hw.HwSample): CaptureWarn? = when {
    s.thermalSevere || (s.tempC ?: 0) >= 42 -> CaptureWarn.HEAT
    s.lowMemory || s.ramPct > 90 -> CaptureWarn.MEMORY
    s.batteryPct in 0..14 && !s.charging -> CaptureWarn.BATTERY
    s.cpuPct > 90 -> CaptureWarn.LOAD
    else -> null
}

/**
 * The recording screen's meters: hidden while all is well, shown as a warning banner when the phone
 * runs hot, short of memory, low on battery or maxed out (kept 30 s after recovery so it does not
 * flicker), and always shown when [pinned] from the ⋯ menu.
 */
@Composable
fun CaptureMeters(pinned: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val pal = LocalPrimeMinutesPalette.current
    val s = remember { hwSamples(ctx) }.collectAsState(initial = null).value
    val warn = s?.let(::captureWarn)
    var lastWarn by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<CaptureWarn?>(null) }
    var lastWarnAt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(s) {
        if (warn != null) { lastWarn = warn; lastWarnAt = android.os.SystemClock.elapsedRealtime() }
    }
    // Re-evaluated at each 2 s sample, so the 30 s hold expires on its own.
    val recent = lastWarn != null && android.os.SystemClock.elapsedRealtime() - lastWarnAt < 30_000
    if (s == null || (!pinned && !recent)) return
    val shown = warn ?: lastWarn.takeIf { recent }
    val bg = if (shown != null) PrimeMinutesPalette.Warning.copy(alpha = 0.12f) else pal.InsetSurface
    val cd = stringResource(R.string.hw_status_cd, s.cpuPct, s.ramPct, s.batteryPct)
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics(mergeDescendants = true) {
                liveRegion = if (shown != null) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown?.let {
            Text(
                stringResource(when (it) {
                    CaptureWarn.HEAT -> R.string.capture_warn_heat
                    CaptureWarn.MEMORY -> R.string.capture_warn_memory
                    CaptureWarn.BATTERY -> R.string.capture_warn_battery
                    CaptureWarn.LOAD -> R.string.capture_warn_load
                }),
                style = MaterialTheme.typography.labelMedium, color = pal.WarningText,
            )
        }
        Row(
            Modifier.clearAndSetSemantics { contentDescription = cd },
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Gauge("CPU", s.cpuPct, s.cpuPct > 90)
            Gauge("RAM", s.ramPct, s.ramPct > 90 || s.lowMemory)
            if (s.batteryPct >= 0) Gauge(if (s.charging) "BAT +" else "BAT", s.batteryPct,
                (s.tempC ?: 0) >= 42 || (!s.charging && s.batteryPct < 15))
        }
    }
}
