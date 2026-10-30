package studio.primeminutes

// Redesign (2026-10) pieces of the meeting screen: the header (title + meta + speaker chips), the
// one-line AI status, the summary prose, the Notes tab, the compact player and the "now playing"
// jump. Stateless: they take state and callbacks, like SessionComponents.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.primeminutes.core.llm.MeetingNotes
import studio.primeminutes.data.speakerColorOn
import studio.primeminutes.ui.renderMarkdown
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import studio.primeminutes.ui.theme.TypeRole

/** The meeting header: tappable title (21/700, wraps), then date · length and the speakers as tinted chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeetingHeader(
    title: String,
    meta: String,
    speakers: List<Pair<Int, String>>,
    onTitleClick: (() -> Unit)?,
) {
    val pal = LocalPrimeMinutesPalette.current
    val renameLabel = stringResource(R.string.action_rename)
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xs, bottom = Spacing.md)) {
        Text(
            title, style = TypeRole.meetingTitle(), color = pal.Slate200,
            modifier = Modifier.semantics { heading() }
                .then(if (onTitleClick != null) Modifier.clickable(role = Role.Button, onClickLabel = renameLabel, onClick = onTitleClick) else Modifier)
                .testTag("meeting-title"),
        )
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (meta.isNotBlank()) Text(meta, style = TypeRole.meta(), color = pal.Slate400)
            speakers.forEach { (id, name) -> TintTag(name, Color(speakerColorOn(id, pal.isDark))) }
        }
    }
}

/** A 6 dp tag: 12 % tint fill, solid text in the hue (speaker or note type). */
@Composable
internal fun TintTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** One line for the AI reader: a dot, what it did (or is doing), and 看過程 to open the Agent panel. */
@Composable
internal fun AgentStatusLine(running: Boolean, noteCount: Int, onOpen: (() -> Unit)?) {
    val pal = LocalPrimeMinutesPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) PrimeMinutesPalette.Warning else PrimeMinutesPalette.Success))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            when {
                running -> stringResource(R.string.agent_status_reading)
                noteCount > 0 -> stringResource(R.string.agent_status_done, noteCount)
                else -> stringResource(R.string.agent_status_summary_done)
            },
            style = TypeRole.meta(), color = pal.Slate400, modifier = Modifier.weight(1f),
        )
        if (onOpen != null) Text(
            stringResource(R.string.agent_see_process), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = pal.Sky,
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onOpen)
                .heightIn(min = 40.dp).padding(horizontal = Spacing.sm, vertical = Spacing.sm).testTag("agent-see-process"),
        )
    }
}

/** The summary as prose: reading body (15.5 / 1.8), citation pills that seek, the AI disclaimer under it. */
@Composable
internal fun SummaryProse(summary: String, onSeek: ((Int) -> Unit)?) {
    val pal = LocalPrimeMinutesPalette.current
    Column(Modifier.fillMaxWidth()) {
        summary.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.forEach { para ->
            Text(
                studio.primeminutes.ui.colorSpeakerNames(
                    renderMarkdown(para.trim(), anchorColor = if (onSeek != null) pal.Sky else null, onSeek = onSeek),
                    studio.primeminutes.ui.LocalSpeakerNames.current,
                ) { id -> Color(speakerColorOn(id, pal.isDark)) },
                style = TypeRole.readingBody(), color = pal.Slate200,
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md),
            )
        }
        Text(stringResource(R.string.summary_ai_caution), style = TypeRole.meta(), color = pal.Ink3)
    }
}

/** One group of the Notes tab: its heading, its colour, its lines (raw, anchors included). */
internal data class NoteGroup(val label: String, val color: Color, val lines: List<String>, val check: Boolean = false)

private val FIRST_ANCHOR = Regex("""\[(\d+):(\d{2})(?::(\d{2}))?(?:\s*[,，、;；][^\]]*)?]""")

/** The groups of [n] (plus the action items of the older format), empty ones left out. */
@Composable
internal fun noteGroups(n: MeetingNotes?, actionItems: String?): List<NoteGroup> {
    val dark = LocalPrimeMinutesPalette.current.isDark
    fun c(light: Long, darkC: Long) = Color(if (dark) darkC else light)
    val actions = n?.actions?.takeIf { it.isNotEmpty() }
        ?: actionItems?.lines()?.map { it.trim().removePrefix("-").removePrefix("*").trim() }?.filter { it.isNotEmpty() && it != "-" }.orEmpty()
    return listOfNotNull(
        NoteGroup(stringResource(R.string.notes_decisions), c(0xFF2E9156, 0xFF7FD3A2), n?.decisions.orEmpty()),
        NoteGroup(stringResource(R.string.card_action_items), c(0xFF2F6FDE, 0xFF8DB4FF), actions),
        NoteGroup(stringResource(R.string.notes_open), c(0xFF8A5A12, 0xFFE0A84A), n?.open.orEmpty()),
        NoteGroup(stringResource(R.string.notes_topics), c(0xFF8A5CD0, 0xFFC4A8F5), n?.topics.orEmpty()),
    ).plus(n?.extra.orEmpty().map { (k, v) -> NoteGroup(k, c(0xFFB03A8A, 0xFFF08CCB), v) })
        .filter { it.lines.isNotEmpty() }
}

/** The live reader's kept notes grouped by type (決議, 待辦, 提議, 未決, 數字, other), each line "text [ts]". */
@Composable
internal fun agentNoteGroups(notes: List<studio.primeminutes.core.reader.Note>): List<NoteGroup> {
    val dark = LocalPrimeMinutesPalette.current.isDark
    fun c(light: Long, darkC: Long) = Color(if (dark) darkC else light)
    val colors = mapOf(
        "DECISION" to c(0xFF2E9156, 0xFF7FD3A2), "ACTION" to c(0xFF2F6FDE, 0xFF8DB4FF), "PROPOSAL" to c(0xFF8A5CD0, 0xFFC4A8F5),
        "OPEN-ISSUE" to c(0xFF8A5A12, 0xFFE0A84A), "NUMBER" to c(0xFFB03A8A, 0xFFF08CCB),
    )
    val order = listOf("DECISION", "ACTION", "PROPOSAL", "OPEN-ISSUE", "NUMBER")
    val by = notes.groupBy { studio.primeminutes.ui.fullTag(it.tag?.takeIf { t -> t != "-" } ?: "") }
    val other = stringResource(R.string.tab_notes_plain)
    return (order + by.keys.filter { it !in order }.sorted()).mapNotNull { k ->
        val l = by[k]?.sortedBy { studio.primeminutes.core.reader.ReaderProtocol.parseTs(it.ts) ?: 0 } ?: return@mapNotNull null
        NoteGroup(if (k.isEmpty()) other else studio.primeminutes.ui.tagLabel(k), colors[k] ?: LocalPrimeMinutesPalette.current.Slate400,
            l.map { "${it.text} [${it.ts}]" }, check = k in setOf("DECISION", "ACTION", "NUMBER"))
    }
}

/** The Notes tab: grouped by type, one line each, the time on the left plays from there. */
@Composable
internal fun NotesTab(groups: List<NoteGroup>, onSeek: ((Int) -> Unit)?) {
    val pal = LocalPrimeMinutesPalette.current
    val refs = studio.primeminutes.ui.LocalSpeakerRefs.current
    if (groups.isEmpty()) {
        Text(stringResource(R.string.notes_empty), color = pal.Slate400, modifier = Modifier.padding(top = Spacing.xxl))
        return
    }
    Column(Modifier.fillMaxWidth()) {
        groups.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs)) {
                TintTag(g.label, g.color, Modifier.semantics { heading() })
                Spacer(Modifier.width(Spacing.sm))
                Text("${g.lines.size}", style = TypeRole.meta(), color = pal.Ink3)
            }
            g.lines.forEachIndexed { i, raw ->
                val m = FIRST_ANCHOR.find(raw)
                val secs = m?.groupValues?.let { v ->
                    if (v[3].isNotEmpty()) v[1].toInt() * 3600 + v[2].toInt() * 60 + v[3].toInt() else v[1].toInt() * 60 + v[2].toInt()
                }
                val label = m?.value?.removePrefix("[")?.substringBefore(']')?.split(Regex("""\s*[,，、;；]\s*"""))?.first()
                val text = if (m != null && m.range.first > raw.length / 2) raw.removeRange(m.range).trim() else raw
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = pal.Hairline)
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.Top) {
                    val playFrom = label?.let { stringResource(R.string.cd_play_from, it) }
                    Box(
                        Modifier.widthIn(min = 52.dp).heightIn(min = 32.dp)
                            .then(if (secs != null && onSeek != null) Modifier.clip(RoundedCornerShape(6.dp))
                                .clickable(role = Role.Button) { onSeek(secs * 1000) }
                                .semantics { contentDescription = playFrom ?: "" } else Modifier),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Text(label ?: "", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            color = if (onSeek != null) pal.Sky else pal.Slate400, modifier = Modifier.padding(top = 3.dp))
                    }
                    Text(
                        renderMarkdown(refs(text), anchorColor = if (onSeek != null) pal.Sky else null, onSeek = onSeek),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f),
                        color = pal.Slate200, modifier = Modifier.weight(1f),
                    )
                    // 核對 only on the error-prone types (decisions, actions, figures): plays the cited moment.
                    if (g.check && secs != null && onSeek != null) Text(
                        stringResource(R.string.notes_check), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                        color = pal.WarningText,
                        modifier = Modifier.padding(start = Spacing.sm).clip(CircleShape).background(pal.WarningTint)
                            .clickable(role = Role.Button) { onSeek(secs * 1000) }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * The compact player (56 dp): play, the speaker-lane scrubber, "1:48 / 7:38", speed; the chevron expands
 * to the full controls (±5 s, volume). Flat, hairline on top.
 */
@Composable
internal fun CompactPlayer(
    utterances: List<studio.primeminutes.core.events.TranscriptEvent.Utterance>,
    positionMs: Int, durationMs: Int, dragMs: Int?, isPlaying: Boolean, buffering: Boolean, activeIndex: Int,
    speed: Float, onSpeed: () -> Unit,
    expanded: Boolean, onExpand: () -> Unit,
    onPlayPause: () -> Unit, onSeekTo: (Int) -> Unit, onDragChange: (Int?) -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    val shown = dragMs ?: positionMs
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                onClick = onPlayPause, shape = CircleShape, color = pal.Slate200,
                modifier = Modifier.size(44.dp).testTag("player-play"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
                        tint = pal.Slate900, modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (buffering) CircularProgressIndicator(Modifier.size(44.dp), color = pal.Sky, strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            TimelineStrip(
                utterances = utterances, durationMs = durationMs, activeIndex = activeIndex, progressMs = shown,
                onSeekTo = onSeekTo, onDragChange = onDragChange,
                modifier = Modifier.fillMaxWidth().height(14.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(fmtMs(shown), style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
                Spacer(Modifier.weight(1f))
                Text(fmtMs(durationMs), style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        val sp = if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"
        val spLabel = stringResource(R.string.player_speed, sp)
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onSpeed)
                .semantics { contentDescription = spLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(sp, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = pal.Slate200,
                modifier = Modifier.clip(CircleShape).background(pal.Fill).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        IconButton(onClick = onExpand) {
            Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = stringResource(R.string.player_more), tint = pal.Slate400)
        }
    }
}

/** The floating "目前播放" pill: back to the line that is playing when it scrolled out of view. */
@Composable
internal fun NowPlayingJump(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPrimeMinutesPalette.current
    Surface(
        onClick = onClick, shape = CircleShape, color = pal.Slate800,
        shadowElevation = if (pal.isEink) 0.dp else 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, pal.Hairline),
        modifier = modifier.heightIn(min = 40.dp).testTag("jump-now-playing"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Icon(Icons.Filled.MyLocation, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.jump_now_playing), style = MaterialTheme.typography.labelLarge, color = pal.Slate200, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The citation preview: the few transcript turns around a cited moment, docked just above the player
 * (never over it). The cited turn — then the playing one — is highlighted as in the transcript; ✕ or a
 * downward swipe closes it, 「看完整逐字稿 →」 opens the transcript at the cited line.
 */
@Composable
internal fun CitePreviewPanel(
    utterances: List<studio.primeminutes.core.events.TranscriptEvent.Utterance>,
    window: IntRange, highlighted: Int,
    speakerLabel: (Int) -> String,
    maxHeight: androidx.compose.ui.unit.Dp,
    onSeek: (Double) -> Unit, onOpenTranscript: () -> Unit, onClose: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    val label = stringResource(R.string.cite_preview_label)
    var dragged by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    Surface(
        color = pal.PanelSurface, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, pal.Hairline),
        shadowElevation = if (pal.isEink) 0.dp else 2.dp,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("cite-preview")
            .semantics { contentDescription = label; paneTitle = label }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > swipePx) onClose(); dragged = 0f },
                    onDragCancel = { dragged = 0f },
                ) { _, dy -> dragged += dy }
            },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.cite_preview_full), style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, color = pal.Sky,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onOpenTranscript)
                        .heightIn(min = 40.dp).padding(horizontal = 4.dp, vertical = 10.dp).testTag("cite-preview-full"),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag("cite-preview-close")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cite_preview_close), tint = pal.Slate400)
                }
            }
            androidx.compose.material3.HorizontalDivider(color = pal.Hairline)
            Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = Spacing.sm)) {
                for (i in window) {
                    val u = utterances.getOrNull(i) ?: continue
                    val active = i == highlighted
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (active) pal.Slate200.copy(alpha = if (pal.isEink) 0.12f else 0.07f) else Color.Transparent)
                            .then(if (active) Modifier.semantics { selected = true } else Modifier)
                            .clickable { onSeek(u.startSec) }
                            .padding(vertical = 4.dp, horizontal = Spacing.md),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            u.speaker?.let { sid -> TranscriptSpeakerChip(speakerLabel(sid), sid); Spacer(Modifier.width(6.dp)) }
                            val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                            if (active) Text("▶ ${fmt(u.startSec)}", style = timeStyle, fontWeight = FontWeight.Bold, color = pal.Slate200)
                            else Text(fmt(u.startSec), style = timeStyle, color = pal.Slate400)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(u.text, style = MaterialTheme.typography.bodyLarge, color = pal.Slate200,
                            maxLines = if (active) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * A tap anywhere in this content closes the citation preview (when [enabled]). Seen on the way down,
 * before the children: a citation tapped there closes the old preview, then opens its own. Scrolls
 * (a consumed move) do not count as taps.
 */
internal fun Modifier.citeDismissOnTap(enabled: Boolean, onDismiss: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            val pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            val slop = viewConfiguration.touchSlop
            while (true) {
                val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) break   // a scroll, not a tap
                if (!change.pressed) { onDismiss(); break }
            }
        }
    }
