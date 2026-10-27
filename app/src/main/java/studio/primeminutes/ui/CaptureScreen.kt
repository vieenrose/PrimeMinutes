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
import androidx.compose.material.icons.filled.Mic
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

/**
 * Full-screen capture — the studio's recording booth. Fixed layout with two GIANT buttons
 * (⏭ Next talk / ⏹ Stop & save) sized for a conference table and an e-ink screen: nothing
 * shifts position with state, unlike the old top-bar icon strip. A collapsible live-transcript
 * panel fills the space between the name field and the buttons, auto-following the newest line;
 * back leaves the recording running (Studio shows a live banner to return here).
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
) {
    val pal = LocalPrimeMinutesPalette.current
    // A meeting is watched, not touched: keep the screen on while recording.
    val keepOnView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(isRecording) {
        keepOnView.keepScreenOn = isRecording
        onDispose { keepOnView.keepScreenOn = false }
    }
    val conf = LocalConfiguration.current
    val landscape = studio.primeminutes.ui.components.rememberIsLandscape()
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.Slate900Grad)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = pal.Slate200)
            }
            if (isRecording) {
                RecDot()
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                stringResource(if (isRecording) R.string.status_recording else R.string.capture_title),
                style = MaterialTheme.typography.titleMedium,
                color = if (isRecording) PrimeMinutesPalette.Red else pal.Slate200,
                fontWeight = FontWeight.Bold,
                // Weighted so the gauges are measured first and the title wraps (not ellipsizes)
                // into the remaining width at large font scales.
                modifier = Modifier.weight(1f),
            )
            // The engines' load, trailing on the header row (page gutter, same as iOS) rather than
            // floating centered under the timer; only while the mic and the engines run.
            if (isRecording) {
                HwStatusLine(Modifier.padding(end = Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        if (landscape) {
            // Two columns: timer/name/buttons left, live transcript right — stacking them
            // vertically left the transcript panel with zero height on phone landscape.
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TimerRow(recSeconds, micLevel, Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(Spacing.md))
                    Spacer(Modifier.weight(1f))
                    CaptureButtons(isRecording, onNextTalk, onStop, buttonHeight = 72.dp)
                }
                Spacer(Modifier.width(Spacing.xxl))
                Column(Modifier.weight(1.2f).fillMaxHeight()) {
                    LivePanel(utterances, stable, agent, notice)
                }
            }
            Spacer(Modifier.height(Spacing.md))
        } else {
            TimerRow(recSeconds, micLevel, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(Spacing.md))
            // Live transcript: a first-class panel filling everything between the timer and
            // the buttons — the full running transcript, auto-following the newest line.
            LivePanel(utterances, stable, agent, notice)
            Spacer(Modifier.height(Spacing.lg))
            CaptureButtons(isRecording, onNextTalk, onStop, buttonHeight = 72.dp)
            Spacer(Modifier.height(Spacing.lg))
        }
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

@Composable
private fun TimerRow(recSeconds: Int, micLevel: Float, modifier: Modifier = Modifier) {
    val pal = LocalPrimeMinutesPalette.current
    // Compact header: timer + mic bars on one line — still readable from across a table, but
    // the vertical space goes to the live transcript instead of empty padding.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            studio.primeminutes.fmtSec(recSeconds),
            style = TypeRole.timer(),   // tabular: no layout shift per tick
            color = pal.Slate200,
        )
        Spacer(Modifier.width(Spacing.xl))
        // 2x: at 1x the bars are a speck beside 64sp digits — scale to visually balance them.
        MicLevelBars(micLevel, pal.Sky, scale = 2f)
    }
}

/** The transcript panel body — takes all remaining column height. Declared as a ColumnScope
 *  extension for the weight modifier. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LivePanel(
    utterances: List<TranscriptEvent.Utterance>,
    stable: Int,
    agent: AgentUiState?,
    notice: String? = null,
) {
    val pal = LocalPrimeMinutesPalette.current
    // The summarizing agent works alongside ASR + diarization: its status stays pinned on top.
    agent?.let {
        // The live notes name speakers as the booth does (S2 → 語者 2 / 说话人 2 / Speaker 2).
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val known = utterances.mapNotNull { u -> u.speaker }.toSet().ifEmpty { null }
        CompositionLocalProvider(LocalSpeakerRefs provides { t ->
            studio.primeminutes.core.reader.SpeakerRefs.resolve(t, { id -> ctx.getString(R.string.speaker_n, id + 1) }, known)
        }) { AgentStrip(it) }
    }
    // A model still downloading (e.g. the 3 GB summary model on a first run, while the meeting is
    // already being transcribed): say so on the booth, not only in the notification shade.
    if (utterances.isNotEmpty()) notice?.takeIf { it.isNotBlank() }?.let {
        // Polite live region: TalkBack users hear the download state change without hunting for it.
        Text(it, color = pal.Slate400, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (utterances.isEmpty()) {
        // Centered waiting state: a corner-anchored one-liner made the big empty panel
        // look unfinished — center it with a quiet mic glyph so the space reads intentional.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Mic, contentDescription = null,
                    tint = pal.Sky.copy(alpha = 0.5f), modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.capture_live_waiting), color = pal.Slate400, style = MaterialTheme.typography.titleMedium)
                notice?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = pal.Slate400, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    } else {
        val listState = rememberLazyListState()
        // Follow the newest words. The last line grows in place (a line closes only on a speaker change
        // or after ~10 s), so key on its length too. Instant jump, not animate: e-ink hates animated
        // scrolls. The large offset pins the BOTTOM of a tall last line into view.
        LaunchedEffect(utterances.size, utterances.lastOrNull()?.text?.length) {
            listState.scrollToItem(utterances.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .then(if (pal.isEink) Modifier else Modifier.fadeTop(14.dp))
                .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        ) {
            items(utterances.size) { i ->
                val u = utterances[i]
                // Text is live; a speaker tag appears only once the line is settled (i < stable, i.e.
                // the configured speaker delay has passed) — never a guess that may still flip. Tag
                // only where the speaker changes.
                val settled = i < stable
                val showTag = settled && u.speaker != null &&
                    (i == 0 || utterances[i - 1].speaker != u.speaker)
                // Settled lines carry their speaker's colour rail (like the session transcript);
                // the provisional tail has none yet, and its text is a shade lighter.
                val rail = if (settled && u.speaker != null) Color(speakerColorOn(u.speaker, pal.isDark)) else pal.Hairline
                Row(Modifier.padding(top = if (showTag && i > 0) 10.dp else 2.dp, bottom = 2.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(rail))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        if (showTag) {
                            val color = Color(speakerColorOn(u.speaker, pal.isDark))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.speaker_n, u.speaker + 1),
                                    color = color,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    studio.primeminutes.fmtSec(u.startSec.toInt()),
                                    color = pal.Slate400, style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Text(u.text, color = if (settled) pal.Slate200 else pal.Slate400, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureButtons(
    isRecording: Boolean,
    onNextTalk: () -> Unit,
    onStop: () -> Unit,
    buttonHeight: androidx.compose.ui.unit.Dp,
) {
    val pal = LocalPrimeMinutesPalette.current
    // Both buttons end a capture, which was a silent event: felt parity with iOS (record switch
    // `toggle`, saved-meeting `success`).
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        // ⏭ Next talk — the batch-recording workhorse: auto-save this capture, defer its
        // processing, and roll straight into the next session.
        Button(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onNextTalk() },
            enabled = isRecording,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = pal.Sky),
            modifier = Modifier.weight(1f).heightIn(min = buttonHeight),
            // Minimal padding: on narrow phones the default 24dp sides forced CJK labels to wrap.
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.capture_next_talk), fontWeight = FontWeight.Bold, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        // ⏹ Stop & save — always safe: the capture is in the library before processing starts.
        Button(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onStop() },
            enabled = isRecording,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimeMinutesPalette.Red),
            modifier = Modifier.weight(1f).heightIn(min = buttonHeight),
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.capture_stop), fontWeight = FontWeight.Bold, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
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
