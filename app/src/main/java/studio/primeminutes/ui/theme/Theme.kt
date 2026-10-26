package studio.primeminutes.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import studio.primeminutes.core.config.ThemeMode

/**
 * Theme-aware color set. Every surface / text / brand-band token that must differ between the
 * dark, light, and e-ink themes lives here and is read at the call site via [LocalPrimeMinutesPalette]
 * (the `pal` convention: `val pal = LocalPrimeMinutesPalette.current`). The three concrete instances are
 * [DarkColors], [LightColors] and [EinkColors]; [PrimeMinutesTheme] picks one per [ThemeMode] and
 * provides it down the tree.
 *
 * Member names are deliberately kept identical to the original single-palette `PrimeMinutesPalette`
 * object (Slate900, Sky, PanelSurface, …) so the meaning of each call site is unchanged — only its
 * concrete value flips with the theme. Truly theme-independent tokens (status colors, speaker
 * alphas) stay on [PrimeMinutesPalette].
 */
data class PrimeMinutesColors(
    /** Whether this palette reads as a dark theme (drives Material's base scheme + status-bar icons). */
    val isDark: Boolean,
    /** True only for the e-ink palette — UI reads it to avoid continuous animation (spinners) that
     *  ghosts on e-paper; static indicators are used instead. */
    val isEink: Boolean = false,
    val Sky: Color,
    val Indigo: Color,
    val Slate900: Color,
    val Slate800: Color,
    val Slate700: Color,
    val Slate600: Color,
    val Slate400: Color,
    val Slate200: Color,
    val OnBrand: Color,
    val OnBrandMuted: Color,
    val OnBrandFaint: Color,
    val BrandGradient: Brush,
    val Slate900Grad: Brush,
    val PanelSurface: Color,
    val InsetSurface: Color,
    val Hairline: Color,
    val ActiveTint: Color,
    val ActiveBar: Color,
    /** Amber for TEXT on the panel surface (the status [PrimeMinutesPalette.Warning] is too light on white). */
    val WarningText: Color,
    /** Ink for text/glyphs drawn ON the [Sky] accent (a selected chip, the selection tick, a filled
     *  chip). Never `Color.White`: the dark theme's accent is a light blue, and white on it is ~2.6:1.
     *  [Slate900] is the app's ground, i.e. the contrasting ink of each theme (near-black ink on the
     *  dark accent, off-white on the light and e-ink accents) — the same ink [GradientButton] uses. */
    val OnSky: Color = Slate900,
    /** Tonal wash: accent-tinted container for selected chips, tonal buttons and pills. Mapped
     *  from the Sky palette per theme (not the M3 lavender default) so `secondaryContainer` and
     *  `tertiary` roles stay in the palette's hue family. */
    val AccentWash: Color,
    /** Muted accent for secondary tonal surfaces (progress tracks, inactive ticks). */
    val AccentMuted: Color,
    /** Error ink per theme: pure red reads on dark grounds but washes out on white, so the
     *  light and e-ink themes deepen it. */
    val ErrorText: Color,
)

/**
 * Theme-independent tokens. Status colors read the same on light, dark and e-ink backgrounds, and
 * the speaker alphas are pure ratios — so these never flip and stay a plain object.
 */
object PrimeMinutesPalette {
    // Semantic status colors (Prime Minutes 2.0): status never borrows the accent — done is green,
    // queue-at-work is amber, live recording is red. Tuned to read on light AND dark grounds.
    val Success = Color(0xFF2E9E63)
    val Warning = Color(0xFFE08A0B)
    val Info = Color(0xFF2F6BFF)
    val Idle = Color(0xFFEAB308)
    val Red = Color(0xFFE5484D)
    /** Neutral fallback for [statusColor] (mid-slate — legible on any background). */
    val Neutral = Color(0xFF64748B)

    // Per-speaker chip/segment alpha convention.
    const val SpeakerFillAlpha = 0.15f
    const val SpeakerBorderAlpha = 0.6f
}

/** Dark theme — Prime Minutes 2.0: deep blue-black ground, calm navy cards, one electric-blue accent.
 *  The old sky→indigo gradient is retired; "brand" fills are now the solid accent. */
val DarkColors = PrimeMinutesColors(
    isDark = true,
    Sky = Color(0xFF7DA2FF),
    Indigo = Color(0xFF9DB8FF),
    Slate900 = Color(0xFF0E1420),
    Slate800 = Color(0xFF1D2740),   // cards: a visible step above the ground (1.25:1 was 1.16:1)
    Slate700 = Color(0xFF36435F),   // inactive tracks, disabled fills — 2.0:1 on the ground (was 1.5:1)
    Slate600 = Color(0xFF55658A),   // borders — 3.0:1 (was 2.0:1)
    Slate400 = Color(0xFF93A0B8),
    Slate200 = Color(0xFFE9EDF6),
    OnBrand = Color.White,
    OnBrandMuted = Color.White.copy(alpha = 0.70f),
    OnBrandFaint = Color.White.copy(alpha = 0.35f),
    BrandGradient = Brush.linearGradient(listOf(Color(0xFF23304B), Color(0xFF23304B))),
    Slate900Grad = Brush.verticalGradient(listOf(Color(0xFF0E1420), Color(0xFF0E1420))),
    PanelSurface = Color(0xFF1D2740),
    InsetSurface = Color(0xFF0E1420).copy(alpha = 0.55f),
    Hairline = Color(0xFF93A0B8).copy(alpha = 0.30f),
    ActiveTint = Color(0xFF7DA2FF).copy(alpha = 0.16f),
    ActiveBar = Color(0xFF7DA2FF),
    WarningText = Color(0xFFE08A0B),
    AccentWash = Color(0xFF7DA2FF).copy(alpha = 0.16f),
    AccentMuted = Color(0xFF9DB8FF),
    ErrorText = Color(0xFFFF7A7A),
)

/**
 * Light theme — tuned for LCD phones (the common case), not e-ink: soft off-white surfaces, a
 * subtle background gradient, and a slightly deepened sky so accents keep contrast on white. The
 * brand band stays the colored sky→indigo gradient with white text.
 */
val LightColors = PrimeMinutesColors(
    isDark = false,
    Sky = Color(0xFF2F6BFF),        // the ONE accent — actions and identity
    Indigo = Color(0xFF1E4FD6),     // deepened accent for primary-role fills
    Slate900 = Color(0xFFF2F4F1),   // soft warm-grey ground
    Slate800 = Color(0xFFFFFFFF),   // card surface
    Slate700 = Color(0xFFDFE4DD),   // inactive tracks / disabled fills
    Slate600 = Color(0xFF9AA7B8),   // borders / secondary lines
    Slate400 = Color(0xFF5A6982),   // muted text
    Slate200 = Color(0xFF17263F),   // primary text = deep navy ink
    OnBrand = Color.White,
    OnBrandMuted = Color.White.copy(alpha = 0.80f),
    OnBrandFaint = Color.White.copy(alpha = 0.45f),
    BrandGradient = Brush.linearGradient(listOf(Color(0xFF2F6BFF), Color(0xFF2F6BFF))),
    Slate900Grad = Brush.verticalGradient(listOf(Color(0xFFF2F4F1), Color(0xFFF2F4F1))),
    PanelSurface = Color(0xFFFFFFFF),
    InsetSurface = Color(0xFFECEFEA),
    Hairline = Color(0xFF17263F).copy(alpha = 0.10f),
    ActiveTint = Color(0xFF2F6BFF).copy(alpha = 0.12f),
    ActiveBar = Color(0xFF2F6BFF),
    WarningText = Color(0xFF9A5B00),
    AccentWash = Color(0xFF2F6BFF).copy(alpha = 0.12f),
    AccentMuted = Color(0xFF1E4FD6),
    ErrorText = Color(0xFFB3261E),
)

/**
 * E-ink theme — a manual choice for e-paper devices (e.g. Boox). Flat pure white, black ink,
 * crisp black hairlines instead of subtle alpha borders, and the brand band flattened to white
 * (black content) so there is no gradient banding or large ink-heavy block to ghost. Accents use
 * a deep blue that stays legible on a color e-ink (Kaleido) panel.
 */
val EinkColors = PrimeMinutesColors(
    isDark = false,
    isEink = true,
    Sky = Color(0xFF0B5CAD),
    Indigo = Color(0xFF3730A3),
    Slate900 = Color(0xFFFFFFFF),
    Slate800 = Color(0xFFFFFFFF),
    Slate700 = Color(0xFFD4D4D4),
    Slate600 = Color(0xFF000000),
    Slate400 = Color(0xFF333333),
    Slate200 = Color(0xFF000000),
    // Brand band / filled CTAs stay a solid dark blue (flat: a gradient bands on e-paper) so white ink on them reads
    // (BrandGradient also fills the play button and the selected-card border). Content ink here is
    // Slate900 = white, so this MUST stay dark enough for white to contrast.
    OnBrand = Color(0xFFFFFFFF),
    OnBrandMuted = Color(0xFFFFFFFF).copy(alpha = 0.78f),
    OnBrandFaint = Color(0xFFFFFFFF).copy(alpha = 0.50f),
    BrandGradient = Brush.linearGradient(listOf(Color(0xFF0B5CAD), Color(0xFF0B5CAD))),
    Slate900Grad = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFFFFFFF))),
    PanelSurface = Color(0xFFFFFFFF),
    InsetSurface = Color(0xFFF2F2F2),
    Hairline = Color(0xFF000000).copy(alpha = 0.38f),
    ActiveTint = Color(0xFF0B5CAD).copy(alpha = 0.12f),
    ActiveBar = Color(0xFF0B5CAD),
    WarningText = Color(0xFF000000),
    AccentWash = Color(0xFF0B5CAD).copy(alpha = 0.12f),
    AccentMuted = Color(0xFF3730A3),
    ErrorText = Color(0xFF8B0000),
)

/** Ambient palette. Defaults to dark so previews / stray reads outside [PrimeMinutesTheme] still resolve. */
val LocalPrimeMinutesPalette = staticCompositionLocalOf { DarkColors }

/** Lets deep UI (Settings) read the current theme and switch it. Provided by MainActivity. */
data class ThemeController(val mode: ThemeMode, val setMode: (ThemeMode) -> Unit)

/** The interface language ([studio.primeminutes.core.config.AppLanguage] code) and how to change it live. */
data class LanguageController(val code: String, val set: (String) -> Unit)

val LocalLanguageController = staticCompositionLocalOf { LanguageController("auto") {} }

val LocalThemeController = staticCompositionLocalOf { ThemeController(ThemeMode.AUTO) {} }

/** The app-wide text scale (over the system font size) and how to change it live. */
data class FontScaleController(val scale: Float, val set: (Float) -> Unit)

val LocalFontScaleController = staticCompositionLocalOf { FontScaleController(1f) {} }

/**
 * Semantic color for the pipeline status pill, from TYPED state — not by matching English keywords
 * in the localized status text (that left every pill Neutral in fr/zh-rTW). Red on error, Info while
 * a run is in flight, Success once a transcript exists and the run is idle, Neutral otherwise.
 */
fun statusColor(running: Boolean, transcriptReady: Boolean, isError: Boolean): Color = when {
    isError -> PrimeMinutesPalette.Red
    running -> PrimeMinutesPalette.Info
    transcriptReady -> PrimeMinutesPalette.Success
    else -> PrimeMinutesPalette.Neutral
}

private fun schemeFor(pal: PrimeMinutesColors): ColorScheme {
    val base = if (pal.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = pal.Indigo,
        onPrimary = pal.Slate900,
        secondary = pal.Sky,
        onSecondary = pal.Slate900,
        background = pal.Slate900,
        onBackground = pal.Slate200,
        surface = pal.Slate800,
        onSurface = pal.Slate200,
        // Dialogs, menus and sheets read these (M3 default is a lavender tint that clashes with the palette).
        surfaceContainerLowest = pal.Slate800,
        surfaceContainerLow = pal.Slate800,
        surfaceContainer = pal.Slate800,
        surfaceContainerHigh = pal.Slate800,
        surfaceContainerHighest = pal.Slate800,
        inverseSurface = pal.Slate200,
        inverseOnSurface = pal.Slate900,
        inversePrimary = if (pal.isDark) pal.Sky else androidx.compose.ui.graphics.Color(0xFF9CC2FF),   // snackbar action: light on the dark bar
        surfaceTint = androidx.compose.ui.graphics.Color.Transparent,
        surfaceVariant = pal.Slate700,
        onSurfaceVariant = pal.Slate400,
        // Tonal roles follow the Sky palette per theme — never the M3 lavender default, which
        // clashes with the deep-blue / white / paper grounds. On-tonal ink is the theme's
        // primary text (Slate200, ~13:1 on the wash in every theme).
        secondaryContainer = pal.AccentWash,
        onSecondaryContainer = pal.Slate200,
        tertiary = pal.AccentMuted,
        onTertiary = pal.Slate900,
        tertiaryContainer = pal.AccentWash,
        onTertiaryContainer = pal.Slate200,
        error = pal.ErrorText,
        onError = pal.Slate900,
        outline = pal.Slate600,
    )
}

/**
 * Spacing scale — the 8pt grid the whole app composes from. All layout paddings, gaps
 * (`spacedBy`), `Spacer` ticks and `contentPadding` use these; raw dp literals in layout
 * position are rhythm drift. Sizing roles (touch targets, icon sizes, dividers, fixed widths,
 * offsets, elevation) keep plain dp — they are geometry, not rhythm.
 *
 * Values stay fixed dp (Compose `dp` already scales with screen density; only fonts follow the
 * user's font scale, which [PrimeMinutesTheme] applies via typography).
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

/**
 * Radius tokens — every radius in the app maps onto the existing M3 shape scale in
 * [PrimeMinutesShapes], so one edit changes the whole shape language:
 *
 * Call sites keep reading `MaterialTheme.shapes.*` (a composable-free lookup); these
 * aliases exist so the shape language has pronounceable names in code review:
 *
 * | Alias      | Shape slot    | Radius | Use                                     |
 * |------------|---------------|--------|-----------------------------------------|
 * | Chip       | `extraSmall`  | 8.dp   | chips, tags, thumbnails, rails          |
 * | Card       | `small`       | 10.dp  | inset cards, input containers           |
 * | Dialog     | `medium`      | 12.dp  | dialogs, buttons, tiles                 |
 * | Sheet      | `large`       | 16.dp  | sheets, panels, banners                 |
 * | Pill       | `CircleShape` | —      | pills, dots, avatars, toggle thumbs     |
 *
 * `extraLarge` keeps the M3 default 28.dp: nothing in the app used a hardcoded 28.dp, and
 * drifting from the spec default would break M3 shape monotonicity for zero gain.
 * Hairline 1–3.dp radii (progress rails, dividers' end caps) are geometry, not shape language,
 * and stay raw dp at the call site.
 *
 * NOTE: `MaterialTheme.shapes.*` is @Composable, so it cannot back a top-level `val`. Keep
 * these as plain dp and construct shapes at the call site via `MaterialTheme.shapes.*`.
 */
object Radius {
    val Chip = 8.dp
    val Card = 10.dp
    val Dialog = 12.dp
    val Sheet = 16.dp
}

private val PrimeMinutesShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.Chip),
    small = RoundedCornerShape(Radius.Card),
    medium = RoundedCornerShape(Radius.Dialog),
    large = RoundedCornerShape(Radius.Sheet),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PrimeMinutesTheme(themeMode: ThemeMode = ThemeMode.AUTO, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val pal = when (themeMode) {
        ThemeMode.AUTO -> if (isSystemInDarkTheme()) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.EINK -> EinkColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Both bars follow the palette: the XML theme is Material.Light, so without this the
            // dark palettes kept a light navigation bar (and dark icons on it).
            @Suppress("DEPRECATION")
            window.statusBarColor = pal.Slate900.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = pal.Slate900.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !pal.isDark
                isAppearanceLightNavigationBars = !pal.isDark
            }
        }
    }
    CompositionLocalProvider(LocalPrimeMinutesPalette provides pal) {
        // Ripples fade over several frames and leave grey ghost smears on e-ink — kill them there
        // (taps then register instantly, no ghosting). Other themes keep the normal ripple.
        // One call site for every theme: branching here moved the whole app to another place in the
        // composition, so entering or leaving e-ink reset all screen state (the open settings sheet
        // closed, an open session went back to the library).
        // The text-size setting scales the typography, not LocalDensity: dialogs and sheets open in their
        // own windows, which re-provide the window's density but inherit the MaterialTheme.
        val base = androidx.compose.material3.Typography()
        val typography = remember(fontScale) { if (fontScale == 1f) base else base.scaled(fontScale) }
        MaterialTheme(colorScheme = schemeFor(pal), shapes = PrimeMinutesShapes, typography = typography) {
            val ripple = androidx.compose.material3.LocalRippleConfiguration.current
            CompositionLocalProvider(
                androidx.compose.material3.LocalRippleConfiguration provides (if (themeMode == ThemeMode.EINK) null else ripple),
                content = content,
            )
        }
    }
}

/**
 * Semantic type roles — the three places the audit found hardcoded sizes. Each derives from
 * an M3 type slot (so the user's font scale, applied to the whole [androidx.compose.material3.Typography]
 * in [PrimeMinutesTheme], flows through automatically) with a size floor so the role's intent
 * survives even if the M3 default drifts:
 *
 * | Role            | Base slot      | Intent                                        |
 * |-----------------|----------------|-----------------------------------------------|
 * | `timer`         | `displayLarge` | capture timer digits: tabular, across-a-table |
 * | `transcriptBody`| `bodyLarge`    | boundary-tap transcript: generous line height |
 * | `sectionTitle`  | `titleMedium`  | section headers over grouped content          |
 */
object TypeRole {
    /** Capture timer — tabular monospace digits sized to the display slot. */
    @androidx.compose.runtime.Composable
    fun timer(): androidx.compose.ui.text.TextStyle =
        androidx.compose.material3.MaterialTheme.typography.displayLarge.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )

    /** Boundary-editor transcript body — body slot with a roomy line height for tap targeting. */
    @androidx.compose.runtime.Composable
    fun transcriptBody(): androidx.compose.ui.text.TextStyle =
        androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
            lineHeight = androidx.compose.material3.MaterialTheme.typography.bodyLarge.lineHeight * 2.5f,
        )

    /** Section title — title slot for headers over grouped content. */
    @androidx.compose.runtime.Composable
    fun sectionTitle(): androidx.compose.ui.text.TextStyle =
        androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
}

/**
 * Central e-ink derivations — every per-screen `if (pal.isEink)` branch that picks a fill or
 * decides motion should read one of these, so the e-paper policy lives in one place:
 * solid fills (no translucent wash that bands on e-paper), static indicators (no animation
 * that ghosts), full-strength ink for status glyphs.
 */
object EinkPolicy {
    /** Fill for a selected segment / chip: solid accent on e-ink, the translucent wash elsewhere. */
    @androidx.compose.runtime.Composable
    fun selectedFill(pal: PrimeMinutesColors): androidx.compose.ui.graphics.Color =
        if (pal.isEink) pal.Sky else pal.AccentWash

    /** Ink drawn on [selectedFill]: white on the solid e-ink accent, the accent itself on the wash. */
    @androidx.compose.runtime.Composable
    fun selectedInk(pal: PrimeMinutesColors): androidx.compose.ui.graphics.Color =
        if (pal.isEink) androidx.compose.ui.graphics.Color.White else pal.Sky

    /** Whether continuous motion (pulses, spinners, fades) is allowed — never on e-ink. */
    fun motionAllowed(pal: PrimeMinutesColors): Boolean = !pal.isEink
}

/**
 * Motion tokens — the M3 duration ladder at one source of truth (durations in ms). Pair with
 * M3 easing (`short*` → FastOutSlowIn, `medium*` → Emphasized); call sites gate on
 * [EinkPolicy.motionAllowed] so e-ink never animates. Reading `ANIMATOR_DURATION_SCALE == 0`
 * (reduced motion) is left to the call site via the platform setting.
 */
object Motion {
    const val short1 = 50
    const val short2 = 100
    const val short3 = 150
    const val short4 = 200
    const val medium1 = 250
    const val medium2 = 300
    const val medium3 = 350
    const val medium4 = 400
}

private fun androidx.compose.ui.text.TextStyle.scaled(f: Float) = copy(
    fontSize = fontSize * f,
    lineHeight = lineHeight * f,
)

private fun androidx.compose.material3.Typography.scaled(f: Float) = copy(
    displayLarge = displayLarge.scaled(f), displayMedium = displayMedium.scaled(f), displaySmall = displaySmall.scaled(f),
    headlineLarge = headlineLarge.scaled(f), headlineMedium = headlineMedium.scaled(f), headlineSmall = headlineSmall.scaled(f),
    titleLarge = titleLarge.scaled(f), titleMedium = titleMedium.scaled(f), titleSmall = titleSmall.scaled(f),
    bodyLarge = bodyLarge.scaled(f), bodyMedium = bodyMedium.scaled(f), bodySmall = bodySmall.scaled(f),
    labelLarge = labelLarge.scaled(f), labelMedium = labelMedium.scaled(f), labelSmall = labelSmall.scaled(f),
)
