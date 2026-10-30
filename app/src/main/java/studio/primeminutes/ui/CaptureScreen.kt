package studio.primeminutes.ui

import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateFloat

import androidx.compose.ui.draw.alpha

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.layout.IntrinsicSize

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import studio.primeminutes.ui.confirm
import studio.primeminutes.ui.reject
import studio.primeminutes.ui.toggleOn
import studio.primeminutes.ui.toggleOff
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.data.speakerColorOn
import studio.primeminutes.data.speakerLabel
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import studio.primeminutes.ui.theme.EinkPolicy
import studio.primeminutes.ui.theme.Motion
import studio.primeminutes.ui.theme.TypeRole

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle

/**
 * Full-screen capture — the recording booth. A top bar (back · recording pill · ⋯), a scrolling
 * waveform, the live transcript filling everything from 0:00 (top-aligned: it grows down from the
 * top edge), the agent's line pinned above the controls, and a round stop button with a quiet
 * "next talk" beside it. The CPU / RAM / battery meters stay out of sight unless something needs
 * attention (heat, memory, battery, load) or the user pins them from the ⋯ menu.
 * Back leaves the recording running (Studio shows a live banner to return here).
 */
@Composable
fun CaptureScreen(
    isRecording: Boolean,
    recSeconds: Int,
    micLevel: Float,
    utterances: List<TranscriptEvent.Utterance>,
    /** Leading [utterances] that are settled; the rest are provisional (speaker may still change). */
    stable: Int = 0,
    /** The meeting-reading agent, when it runs live alongside the recording. */
    agent: AgentUiState? = null,
    /** Shown under the waiting state, e.g. a first-run model download (the mic records meanwhile). */
    notice: String? = null,
    onNextTalk: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    /** Tapping the AI-notes line / card opens the full Agent panel. */
    onOpenAgent: () -> Unit = {},
) {
    val pal = LocalPrimeMinutesPalette.current
    // A meeting is watched, not touched: keep the screen on while recording.
    val keepOnView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(isRecording) {
        keepOnView.keepScreenOn = isRecording
        onDispose { keepOnView.keepScreenOn = false }
    }
    val ctx = LocalContext.current
    var metersPinned by remember { mutableStateOf(studio.primeminutes.core.config.ThemeStore.loadCaptureMeters(ctx)) }
    val landscape = studio.primeminutes.ui.components.rememberIsLandscape()
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.Slate900Grad)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Before the first words (no transcript yet) the pill says what is happening — e.g. models
        // loading — instead of a clock nobody is waiting on (iOS shows the status the same way).
        val pillStatus = notice?.takeIf { it.isNotBlank() && utterances.isEmpty() }
        CaptureTopBar(isRecording, recSeconds, pillStatus, metersPinned, onBack) {
            metersPinned = !metersPinned
            studio.primeminutes.core.config.ThemeStore.saveCaptureMeters(ctx, metersPinned)
        }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.lg)) {
            if (isRecording) CaptureMeters(metersPinned, Modifier.padding(bottom = Spacing.sm))
            if (landscape) {
                Row(Modifier.weight(1f)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        WaveformStrip(micLevel, isRecording)
                        Spacer(Modifier.weight(1f))
                        agent?.let { AgentLine(it, utterances, onOpenAgent) }
                        CaptureButtons(isRecording, onNextTalk, onStop)
                    }
                    Spacer(Modifier.width(Spacing.xl))
                    Column(Modifier.weight(1.2f).fillMaxHeight()) {
                        TranscriptPanel(utterances, stable, notice)
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            } else {
                WaveformStrip(micLevel, isRecording)
                Spacer(Modifier.height(Spacing.sm))
                TranscriptPanel(utterances, stable, notice)
                agent?.let { AgentLine(it, utterances, onOpenAgent) }
                CaptureButtons(isRecording, onNextTalk, onStop)
                Spacer(Modifier.height(Spacing.sm))
            }
        }
    }
}

@Composable
private fun CaptureTopBar(
    isRecording: Boolean,
    recSeconds: Int,
    status: String?,
    metersPinned: Boolean,
    onBack: () -> Unit,
    onToggleMeters: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.xs)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = pal.Slate200)
        }
        RecordingPill(isRecording, recSeconds, status, Modifier.align(Alignment.Center).padding(horizontal = 52.dp))
        Box(Modifier.align(Alignment.CenterEnd)) {
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options), tint = pal.Slate200)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_show_meters)) },
                    trailingIcon = { if (metersPinned) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { open = false; onToggleMeters() },
                )
            }
        }
    }
}

/** Centred pill: pulsing red dot + elapsed time (tabular, no shift per tick). */
@Composable
private fun RecordingPill(isRecording: Boolean, recSeconds: Int, status: String?, modifier: Modifier = Modifier) {
    val pal = LocalPrimeMinutesPalette.current
    val cd = stringResource(R.string.a11y_rec_pill, recSeconds / 60, recSeconds % 60)
    Row(
        modifier.clip(CircleShape).background(pal.InsetSurface)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clearAndSetSemantics {
                if (status != null) { contentDescription = status; liveRegion = LiveRegionMode.Polite }
                else if (isRecording) contentDescription = cd
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRecording) {
            RecDot()
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(
            status ?: if (isRecording) studio.primeminutes.fmtSec(recSeconds) else stringResource(R.string.capture_title),
            style = if (status != null) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            color = pal.Slate200,
            maxLines = 1,
        )
    }
}

/** Pulsing red "on air" dot (static on e-ink). */
@Composable
private fun RecDot() {
    val pal = LocalPrimeMinutesPalette.current
    val a = if (!EinkPolicy.motionAllowed(pal)) 1f else {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
        t.animateFloat(1f, 0.25f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(Motion.medium4 + Motion.short4), androidx.compose.animation.core.RepeatMode.Reverse), label = "rec-a").value
    }
    Box(Modifier.size(10.dp).alpha(a).clip(CircleShape).background(PrimeMinutesPalette.Red))
}

private const val WAVE_BARS = 64

/**
 * Scrolling level strip: one bar per 50 ms from [micLevel], newest on the right. The samples live in
 * a ring buffer and only a frame counter is state, so the Canvas redraws without recomposing the
 * screen. E-ink gets the static 5-step meter instead (no 20 Hz repaint).
 */
@Composable
private fun WaveformStrip(micLevel: Float, active: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val mod = Modifier.fillMaxWidth().height(40.dp).semantics { invisibleToUser() }
    if (!EinkPolicy.motionAllowed(pal)) {
        Box(mod, contentAlignment = Alignment.Center) { MicLevelBars(micLevel, pal.Sky, scale = 2f) }
        return
    }
    val level = androidx.compose.runtime.rememberUpdatedState(micLevel)
    val ring = remember { FloatArray(WAVE_BARS) }
    var head by remember { mutableStateOf(0) }   // total samples written; drives the redraw
    LaunchedEffect(active) {
        while (active) {
            ring[head % WAVE_BARS] = level.value.coerceIn(0f, 1f)
            head++
            kotlinx.coroutines.delay(50)
        }
    }
    val color = pal.Sky
    Canvas(mod) {
        val n = head
        val slot = size.width / WAVE_BARS
        val barW = slot * 0.55f
        val minH = 4.dp.toPx()
        for (k in 0 until WAVE_BARS) {
            // k = 0 is the oldest visible bar (left edge).
            val idx = n - WAVE_BARS + k
            val v = if (idx < 0) 0f else ring[idx % WAVE_BARS]
            val h = (minH + v * (size.height - minH)).coerceAtMost(size.height)
            val alpha = if (k < WAVE_BARS * 35 / 100) 0.35f else 1f
            drawRoundRect(
                color.copy(alpha = alpha),
                topLeft = Offset(k * slot + (slot - barW) / 2, (size.height - h) / 2),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}

/** The agent's line (then card of latest notes), pinned above the controls; tap opens the Agent panel. */
@Composable
private fun AgentLine(agent: AgentUiState, utterances: List<TranscriptEvent.Utterance>, onOpen: () -> Unit) {
    // The live notes name speakers as the booth does (S2 → 語者 2 / 说话人 2 / Speaker 2).
    val ctx = LocalContext.current
    val known = utterances.mapNotNull { u -> u.speaker }.toSet().ifEmpty { null }
    CompositionLocalProvider(LocalSpeakerRefs provides { t ->
        studio.primeminutes.core.reader.SpeakerRefs.resolve(t, { id -> ctx.getString(R.string.speaker_n, id + 1) }, known)
    }) {
        val pal = LocalPrimeMinutesPalette.current
        CaptureAgentNotes(agent, onOpen, if (EinkPolicy.motionAllowed(pal)) Modifier.animateContentSize() else Modifier)
    }
}

/** The live transcript, on a surface that fills all remaining height from 0:00. Lines start at the
 *  TOP edge and grow downward; once the list overflows it follows the newest words unless the user
 *  scrolled up (then a "Latest" chip brings them back). */
@Composable
private fun ColumnScope.TranscriptPanel(
    utterances: List<TranscriptEvent.Utterance>,
    stable: Int,
    notice: String?,
) {
    val pal = LocalPrimeMinutesPalette.current
    Surface(
        color = pal.PanelSurface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        if (utterances.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.Top) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (EinkPolicy.motionAllowed(pal)) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = pal.Slate400, strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text(stringResource(R.string.capture_listening_hint), color = pal.Slate400, style = MaterialTheme.typography.bodyMedium)
                }
                notice?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = pal.Slate400, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        } else {
            TranscriptList(utterances, stable, notice)
        }
    }
}

@Composable
private fun TranscriptList(utterances: List<TranscriptEvent.Utterance>, stable: Int, notice: String?) {
    val pal = LocalPrimeMinutesPalette.current
    val listState = rememberLazyListState()
    // Following unless the user scrolled away from the end.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, more) -> if (scrolling) follow = !more else if (!more) follow = true }
    }
    // Follow the newest words. The last line grows in place, so key on its length too. Instant jump,
    // not animate: e-ink hates animated scrolls. While everything fits, this is a no-op and the list
    // stays top-aligned.
    LaunchedEffect(utterances.size, utterances.lastOrNull()?.text?.length, follow) {
        if (follow) listState.scrollToItem(utterances.lastIndex + 1, scrollOffset = Int.MAX_VALUE / 2)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier.fillMaxSize()
                .then(if (pal.isEink) Modifier else Modifier.fadeTop(14.dp))
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            notice?.takeIf { it.isNotBlank() }?.let { n ->
                item {
                    Text(n, color = pal.Slate400, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            items(utterances.size) { i -> UtteranceRow(utterances, i, stable, last = i == utterances.lastIndex) }
            item { Spacer(Modifier.height(1.dp)) }
        }
        if (!follow) {
            Surface(
                onClick = { follow = true },
                shape = CircleShape, color = pal.Sky, contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.sm),
            ) {
                Text(stringResource(R.string.capture_jump_latest), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun UtteranceRow(utterances: List<TranscriptEvent.Utterance>, i: Int, stable: Int, last: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val u = utterances[i]
    // Text is live; a speaker tag appears only once the line is settled (i < stable) — never a guess
    // that may still flip. Tag only where the speaker changes.
    val settled = i < stable
    val showTag = settled && u.speaker != null && (i == 0 || utterances[i - 1].speaker != u.speaker)
    Column(Modifier.fillMaxWidth().padding(top = if (showTag && i > 0) 12.dp else 2.dp, bottom = 2.dp)) {
        if (showTag) {
            val color = Color(speakerColorOn(u.speaker, pal.isDark))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                Text(
                    stringResource(R.string.speaker_n, u.speaker + 1),
                    color = color,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    studio.primeminutes.fmtSec(u.startSec.toInt()),
                    color = pal.Slate400,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                )
            }
        }
        val caret = last && !settled
        val caretOn = if (caret && EinkPolicy.motionAllowed(pal)) {
            val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "caret")
            t.animateFloat(1f, 0f, androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(500, easing = { f -> if (f < 0.5f) 0f else 1f }),
                androidx.compose.animation.core.RepeatMode.Reverse), label = "caret-a").value
        } else 1f
        val text = if (!caret) androidx.compose.ui.text.AnnotatedString(u.text) else
            androidx.compose.ui.text.buildAnnotatedString {
                append(u.text)
                withStyle(androidx.compose.ui.text.SpanStyle(color = pal.Sky.copy(alpha = caretOn))) { append(" \u258F") }
            }
        Text(text, color = if (settled) pal.Slate200 else pal.Slate400, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CaptureButtons(
    isRecording: Boolean,
    onNextTalk: () -> Unit,
    onStop: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    // Both buttons end a capture: felt parity with iOS (record switch `toggle`, saved meeting `success`).
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        // ⏭ Next talk — quiet: auto-save this capture, defer its processing, roll into the next one.
        Box(Modifier.weight(1f).height(72.dp), contentAlignment = Alignment.Center) {
            OutlinedButton(
                onClick = { haptics.toggleOn(); onNextTalk() },
                enabled = isRecording,
                border = androidx.compose.foundation.BorderStroke(1.dp, pal.Hairline),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.capture_next_talk), color = pal.Sky, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
        // ⏹ Stop & save — always safe: the capture is in the library before processing starts.
        val stopCd = stringResource(R.string.capture_stop)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = { haptics.toggleOff(); onStop() },
                enabled = isRecording,
                shape = CircleShape,
                color = PrimeMinutesPalette.Red,
                shadowElevation = 3.dp,
                modifier = Modifier.size(72.dp).semantics { contentDescription = stopCd },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stopCd, style = MaterialTheme.typography.labelMedium, color = pal.Slate200, maxLines = 2,
                textAlign = TextAlign.Center, modifier = Modifier.clearAndSetSemantics {})
        }
        // Right slot left empty for balance.
        Spacer(Modifier.weight(1f))
    }
}

/** Dissolves the first [height] of a scrolling list into the ground, so a line cut by the panel's
 *  top edge fades instead of being sliced. */
private fun Modifier.fadeTop(height: androidx.compose.ui.unit.Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = height.toPx()),
            blendMode = BlendMode.DstIn,
        )
    }
