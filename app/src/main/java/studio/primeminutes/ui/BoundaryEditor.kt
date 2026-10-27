package studio.primeminutes.ui

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import studio.primeminutes.R
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import studio.primeminutes.ui.theme.TypeRole
import java.util.Locale
import androidx.compose.ui.semantics.heading

/**
 * The seam between two consecutive lines of different speakers: a hairline that fades from A's colour
 * to B's, with a small ⇕ grip. Tapping anywhere on it opens [BoundaryEditorSheet]. It replaces the
 * 20 dp gap that already opens a new turn, so it costs ~8 dp per speaker change.
 */
@Composable
fun SpeakerSeam(colorA: Color, colorB: Color, labelA: String, labelB: String, onOpen: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val cd = stringResource(R.string.cd_boundary_handle, labelA, labelB)
    Box(
        // 44 dp: the seam is the only entry to the boundary editor, and a 28 dp band in a
        // scrolling transcript is a hard target for a thumb (iOS SpeakerSeam: same 44 pt).
        Modifier.fillMaxWidth().height(44.dp)
            .semantics { contentDescription = cd }
            .clickable(onClickLabel = cd, onClick = onOpen),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(start = 36.dp, end = 10.dp).fillMaxWidth().height(1.5.dp)
            .background(Brush.horizontalGradient(listOf(colorA.copy(alpha = 0.55f), colorB.copy(alpha = 0.55f)))))
        Box(
            Modifier.padding(start = 6.dp).size(width = 28.dp, height = 20.dp)
                .clip(MaterialTheme.shapes.small).background(pal.Slate800)
                .border(BorderStroke(1.dp, pal.Slate400.copy(alpha = 0.5f)), MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.UnfoldMore, null, tint = pal.Slate400, modifier = Modifier.size(16.dp)) }
    }
}

/** A speaker the rest of a split line can go to; [isNew] = a speaker id no line uses yet. */
data class SpeakerChoice(val id: Int, val label: String, val color: Color, val isNew: Boolean = false)

/**
 * Who says the rest of a split line: one chip per candidate, in the order given (the neighbouring
 * speakers first, then the others, then a new speaker: [studio.primeminutes.data.BoundaryEdits.splitCandidates]).
 *
 * The seam with the speaker directory (built separately): it may replace this composable with its
 * own picker as long as it keeps the contract — show [choices], report the pick through [onPick].
 */
@Composable
fun SpeakerPicker(choices: List<SpeakerChoice>, selected: SpeakerChoice?, onPick: (SpeakerChoice) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { c ->
            FilterChip(selected = selected == c, onClick = { onPick(c) },
                label = { Text(if (c.isNew) "+ ${c.label}" else c.label, maxLines = 1) },
                leadingIcon = { SpeakerDot(c.color) })
        }
    }
}

/**
 * Bottom sheet that moves the boundary between two speakers word by word (Han text: character by
 * character). One token strip = the end of A + the start of B; the marker sits between two tokens.
 * Drag it, tap the word B starts with, or step with ‹ ›. Each move plays the audio just after the
 * boundary (debounced), so the user hears whether B's first word is really B's. "Before"/"After"
 * replay 2 s up to / from the boundary. With [choices] (split mode) the sheet first asks who says the
 * rest of the line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoundaryEditorSheet(
    tokens: List<String>,
    starts: List<Double>,
    end: Double,
    initialSplit: Int,
    colorA: Color,
    labelA: String,
    colorB: Color,
    labelB: String,
    choices: List<SpeakerChoice>? = null,
    /** The token times come from a character-count spread (no engine times): "≈" on the boundary time. */
    estimated: Boolean = false,
    canPlay: Boolean,
    onPreview: (fromSec: Double, toSec: Double) -> Unit,
    onApply: (split: Int, speakerB: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    val haptics = LocalHapticFeedback.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var split by remember { mutableIntStateOf(initialSplit.coerceIn(1, tokens.size - 1)) }
    var pick by remember { mutableStateOf(choices?.firstOrNull()) }
    var moves by remember { mutableIntStateOf(0) }
    val cB = pick?.color ?: colorB
    val lB = pick?.label ?: labelB
    // The strip shows WINDOW tokens either side of the ORIGINAL boundary (96 % of detected boundaries
    // are within 5 tokens of the truth on AMI, 91 % on AISHELL-4: docs/design/speaker-boundary.md).
    val window = 40
    val lo = (initialSplit - window).coerceAtLeast(0)
    val hi = (initialSplit + window).coerceAtMost(tokens.size)
    fun set(k: Int) {
        val c = k.coerceIn(maxOf(1, lo), minOf(tokens.size - 1, hi))
        if (c != split) { split = c; moves++; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    val t = starts[split]
    // Auto-preview: hear B's first words after the marker settles.
    if (canPlay) LaunchedEffect(moves) {
        if (moves == 0) return@LaunchedEffect
        delay(450); onPreview(t, minOf(t + 1.8, end))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = pal.Slate900) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = Spacing.md)) {
            Text(stringResource(if (choices != null) R.string.boundary_split_title else R.string.boundary_title),
                style = MaterialTheme.typography.titleMedium, color = pal.Slate200, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakerDot(colorA); Text(" $labelA", color = colorA, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge)
                Text("  →  ", color = pal.Slate400)
                SpeakerDot(cB); Text(" $lB", color = cB, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge)
            }
            if (choices != null) SpeakerPicker(choices, pick, onPick = { pick = it }, Modifier.padding(top = 6.dp).fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            TokenStrip(tokens, lo, hi, split, colorA, cB, lB, onTap = { set(it) }, onDrag = { set(it) },
                stepEarlier = { set(split - 1) }, stepLater = { set(split + 1) },
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp))
            Spacer(Modifier.height(6.dp))
            val delta = split - initialSplit
            val dt = t - starts[initialSplit]
            Text(
                stringResource(R.string.boundary_at, (if (estimated) "≈" else "") + fmtPrecise(t)) +
                    if (delta != 0) String.format(Locale.ROOT, "   %+d · %+.1f s", delta, dt) else "",
                style = MaterialTheme.typography.labelMedium, color = pal.Slate400,
            )
            Text(stringResource(R.string.boundary_hint, lB), style = MaterialTheme.typography.bodySmall,
                color = pal.Slate400, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(Spacing.md))
            // One-handed control row: thumbs reach the bottom of the sheet.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth()) {
                FilledTonalIconButton(onClick = { set(split - 1) }, modifier = Modifier.size(52.dp), colors = nudgeColors()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.cd_boundary_earlier))
                }
                OutlinedButton(onClick = { onPreview(maxOf(0.0, t - 2.0), t) }, enabled = canPlay,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)) { Text("◀| " + stringResource(R.string.boundary_before), maxLines = 2, textAlign = TextAlign.Center) }
                OutlinedButton(onClick = { onPreview(t, minOf(t + 2.0, end)) }, enabled = canPlay,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)) { Text(stringResource(R.string.boundary_after) + " |▶", maxLines = 2, textAlign = TextAlign.Center) }
                FilledTonalIconButton(onClick = { set(split + 1) }, modifier = Modifier.size(52.dp), colors = nudgeColors()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.cd_boundary_later))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { onApply(split, pick?.id) }, enabled = choices != null || split != initialSplit,
                    modifier = Modifier.weight(1f)) { Text(stringResource(R.string.boundary_apply)) }
            }
        }
    }
}

@Composable
private fun nudgeColors() = LocalPrimeMinutesPalette.current.let { p ->
    androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(containerColor = p.Sky.copy(alpha = 0.14f), contentColor = p.Sky)
}

@Composable
private fun SpeakerDot(c: Color) = Canvas(Modifier.size(10.dp)) { drawCircle(c) }

/**
 * The token strip: A's tokens on a wash of A's colour, B's on B's, and a draggable marker (a bar with a
 * round knob under the line, like a text-selection handle) at the boundary. Tap = "B starts with this
 * word"; drag = nearest gap. TalkBack: the strip is one adjustable element with earlier/later actions.
 */
@Composable
private fun TokenStrip(
    tokens: List<String>, lo: Int, hi: Int, split: Int, colorA: Color, colorB: Color, labelB: String,
    onTap: (Int) -> Unit, onDrag: (Int) -> Unit, stepEarlier: () -> Unit, stepLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPrimeMinutesPalette.current
    // Char offset where each visible token starts (index i - lo), plus the leading ellipsis.
    val lead = if (lo > 0) "… " else ""
    // The original spacing: the space two Latin words get when they meet across the seam is shown too.
    val shown = remember(tokens) { studio.primeminutes.data.BoundaryEdits.displayTokens(tokens) }
    val offs = remember(shown, lo, hi) {
        val o = IntArray(hi - lo + 1); var p = lead.length
        for (i in lo until hi) { o[i - lo] = p; p += shown[i].length }
        o[hi - lo] = p; o
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = pal.Slate400)) { append(lead) }
        for (i in lo until hi) {
            val c = if (i < split) colorA else colorB
            withStyle(SpanStyle(background = c.copy(alpha = if (pal.isDark) 0.28f else 0.20f), color = pal.Slate200)) {
                append(shown[i])
            }
        }
        if (hi < tokens.size) withStyle(SpanStyle(color = pal.Slate400)) { append(" …") }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    fun tokenAt(pos: Offset): Int? {
        val l = layout ?: return null
        // getOffsetForPosition snaps to the nearest caret position: a tap on the right half of a glyph
        // returns the offset AFTER it. Use the glyph under the finger instead.
        var ch = l.getOffsetForPosition(pos)
        if (ch > 0 && l.getBoundingBox(ch - 1).contains(pos)) ch -= 1
        val j = offs.indexOfLast { it <= ch }.coerceIn(0, hi - lo - 1)
        return lo + j
    }
    fun gapAt(pos: Offset): Int? {
        val l = layout ?: return null
        val ch = l.getOffsetForPosition(pos)
        // nearest token start
        var best = 0; var bd = Int.MAX_VALUE
        for (k in offs.indices) { val d = kotlin.math.abs(offs[k] - ch); if (d < bd) { bd = d; best = k } }
        return lo + best
    }
    val desc = stringResource(R.string.cd_boundary_marker)
    val earlier = stringResource(R.string.cd_boundary_earlier)
    val later = stringResource(R.string.cd_boundary_later)
    val state = stringResource(R.string.boundary_state, labelB, tokens.getOrNull(split)?.trim().orEmpty())
    Box(
        modifier
            .clip(MaterialTheme.shapes.small).background(pal.Slate800)
            .verticalScroll(rememberScrollState())
            .semantics {
                contentDescription = desc
                stateDescription = state
                customActions = listOf(
                    CustomAccessibilityAction(earlier) { stepEarlier(); true },
                    CustomAccessibilityAction(later) { stepLater(); true },
                )
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        Text(
            text,
            style = TypeRole.transcriptBody(),
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
                .pointerInput(offs) { detectTapGestures { p -> tokenAt(p)?.let(onTap) } }
                .pointerInput(offs) {
                    detectDragGestures(
                        onDragStart = { p -> gapAt(p)?.let(onDrag) },
                        onDrag = { ch, _ -> gapAt(ch.position)?.let(onDrag) },
                    )
                }
                .drawWithContent {
                    drawContent()
                    val l = layout ?: return@drawWithContent
                    val off = offs[(split - lo).coerceIn(0, hi - lo)]
                    val r = l.getCursorRect(off.coerceAtMost(text.length))
                    val bar = 3.dp.toPx()
                    val x = r.left - bar / 2
                    drawRoundRect(pal.Slate200, Offset(x, r.top - 2.dp.toPx()), Size(bar, r.height + 4.dp.toPx()),
                        CornerRadius(bar / 2, bar / 2))
                    val knob = 5.dp.toPx()
                    drawCircle(pal.Slate200, knob, Offset(r.left, r.bottom + knob * 0.6f))
                    drawCircle(colorB, knob * 0.55f, Offset(r.left, r.bottom + knob * 0.6f))
                },
        )
    }
}

private fun fmtPrecise(sec: Double): String {
    val ms = (sec * 1000).toLong().coerceAtLeast(0)
    // h:mm:ss from the first hour, so the preview label reads "1:15:03.2" past 3600 s ("75:03.2" was
    // unreadable); Locale.ROOT: machine-adjacent fixed text.
    return String.format(Locale.ROOT, if (ms >= 3_600_000) "%d:%02d:%02d.%d" else "%d:%02d.%d",
        ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, (ms % 1000) / 100)
}
