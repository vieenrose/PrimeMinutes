package studio.primeminutes.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.statusColor
import studio.primeminutes.ui.theme.Spacing
import studio.primeminutes.ui.theme.EinkPolicy

/**
 * Prime Minutes 2.0 session top bar: back · title · search · ONE overflow. The nine-icon strip is gone —
 * re-run actions, exports and settings all live in the sectioned overflow menu, because they're
 * rare; permanent chrome is reserved for navigation and the transcript search. Status line and
 * the run progress bar sit under the bar, as before.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SessionTopBar(
    cover: androidx.compose.ui.graphics.ImageBitmap?,
    title: String?,
    status: String,
    running: Boolean,
    progress: Float,
    transcriptAvailable: Boolean,
    statusIsError: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    // ⏭ while this session is a live/processing run of a saved capture: start recording the next
    // talk immediately — the interrupted processing stays pending for the queue.
    showNextTalk: Boolean,
    onNextTalk: () -> Unit,
    onSearch: () -> Unit,
    canReTranscribe: Boolean, onReTranscribe: () -> Unit,
    canReSummarize: Boolean, onReSummarize: () -> Unit,
    /** An interrupted run left a checkpoint: continue it instead of starting over. */
    canResume: Boolean = false, onResume: () -> Unit = {},
    canExport: Boolean,
    onSettings: () -> Unit,
    /** "Ask this meeting" in the ⋮ menu; null when the in-meeting Ask is not available. */
    onAskMeeting: (() -> Unit)? = null,
    /** "Share" (the one Share sheet: what, then where); null when the open meeting is not a library entry. */
    onShare: (() -> Unit)? = null,
    /** Tap on the title: edit it (null → not editable, e.g. no title yet). */
    onTitleClick: (() -> Unit)? = null,
    /** Undo / Redo of transcript hand-edits; the pair shows once there is something to undo or redo. */
    canUndo: Boolean = false, canRedo: Boolean = false,
    onUndo: () -> Unit = {}, onRedo: () -> Unit = {},
    /** Collapses the bar as the session content scrolls (null: pinned). */
    scrollBehavior: TopAppBarScrollBehavior? = null,
    /** Redesign ⋯ entries (null: not offered). Copy and edit of the summary moved here from its card. */
    onRename: (() -> Unit)? = null,
    onCopySummary: (() -> Unit)? = null,
    onEditSummary: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Redesign: the title lives in the content header, the bar keeps back · search · share · ⋯. */
    showTitle: Boolean = true,
) {
    val pal = LocalPrimeMinutesPalette.current
    // statusBarsPadding: as a Scaffold topBar this composable owns its own inset — without it the
    // bar draws under the clock on edge-to-edge phones (unnoticed on Boox, whose status bar keeps
    // an opaque strip above the app).
    Column(Modifier.fillMaxWidth().background(pal.Slate900Grad).statusBarsPadding()) {
        // M3 top app bar; with [scrollBehavior] it slides away while the content scrolls up (the
        // status line, progress bar and the sticky tabs below stay put).
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = pal.Slate200)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    cover?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier.padding(end = Spacing.sm).size(26.dp).clip(RoundedCornerShape(7.dp)),
                        )
                    }
                    if (showTitle) Text(
                        title ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = pal.Slate200,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier,
                    )
                }
            },
            actions = {
                if (showNextTalk) {
                    IconButton(onClick = onNextTalk) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_next_talk), tint = pal.Sky)
                    }
                }
                if (running) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop_resumable), tint = PrimeMinutesPalette.Red)
                    }
                }
                if (canUndo || canRedo) {
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo),
                            tint = if (canUndo) pal.Slate200 else pal.Slate400.copy(alpha = 0.4f))
                    }
                    IconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.redo),
                            tint = if (canRedo) pal.Slate200 else pal.Slate400.copy(alpha = 0.4f))
                    }
                }
                if (transcriptAvailable) {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_transcript), tint = pal.Slate200)
                    }
                }
                if (canExport && onShare != null) {
                    IconButton(onClick = onShare, modifier = Modifier.testTag("bar-share")) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_title), tint = pal.Slate200)
                    }
                }
                OverflowMenu(
                    canReTranscribe, onReTranscribe, canReSummarize, onReSummarize,
                    canResume, onResume,
                    canExport, onSettings, onAskMeeting, onShare,
                    onRename, onCopySummary, onEditSummary, onDelete,
                )
            },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
            scrollBehavior = scrollBehavior,
        )
        // A healthy idle status ("Loaded session — N lines") is two-second information, not
        // permanent chrome: show it muted and let it disappear after a beat. Errors and live-run
        // status keep their color and stay put.
        var statusVisible by remember(status, running, statusIsError) { mutableStateOf(true) }
        LaunchedEffect(status, running, statusIsError) {
            if (status.isNotBlank() && !running && !statusIsError) {
                kotlinx.coroutines.delay(5000)
                statusVisible = false
            }
        }
        if (status.isNotBlank() && statusVisible) {
            val idle = !running && !statusIsError
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status,
                    style = if (idle) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                    color = if (idle) pal.Slate400 else statusColor(running, transcriptAvailable, statusIsError),
                    modifier = Modifier.weight(1f),
                )
                // The engines' load while this meeting is transcribed / read: on its status row, trailing,
                // above the progress bar (iOS: the live view's status row). Not when idle.
                if (running) studio.primeminutes.ui.HwStatusLine(Modifier.padding(start = Spacing.sm))
            }
        } else if (!running && !statusIsError) {
            // The idle line has faded (or there is none): keep its height so the tabs below
            // don't jump between sessions or tabs.
            Spacer(Modifier.height(Spacing.lg))
        }
        if (running) {
            LinearProgressIndicator(
                progress = { progress },
                color = pal.Sky, trackColor = pal.Slate700,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    canReTranscribe: Boolean, onReTranscribe: () -> Unit,
    canReSummarize: Boolean, onReSummarize: () -> Unit,
    canResume: Boolean, onResume: () -> Unit,
    canExport: Boolean,
    onSettings: () -> Unit,
    onAskMeeting: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onRename: (() -> Unit)? = null,
    onCopySummary: (() -> Unit)? = null,
    onEditSummary: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val pal = LocalPrimeMinutesPalette.current
    var open by remember { mutableStateOf(false) }
    fun pick(action: () -> Unit): () -> Unit = { open = false; action() }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options), tint = pal.Slate200)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // --- this meeting's text ---
            if (onRename != null) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Title, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.action_rename)) }, onClick = pick(onRename))
            if (onCopySummary != null) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.cd_copy_summary)) }, onClick = pick(onCopySummary))
            if (onEditSummary != null) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.cd_edit_summary)) }, onClick = pick(onEditSummary))
            if (onRename != null || onCopySummary != null || onEditSummary != null) HorizontalDivider()
            // --- re-run ---
            if (canResume) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.action_resume)) }, onClick = pick(onResume))
            if (canReTranscribe) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_transcribe)) }, onClick = pick(onReTranscribe))
            if (canReSummarize) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Summarize, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_summarize)) }, onClick = pick(onReSummarize))
            if (canResume || canReTranscribe || canReSummarize) HorizontalDivider()
            if (onAskMeeting != null) {
                DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.ask_meeting_title)) }, onClick = pick(onAskMeeting),
                    modifier = Modifier.testTag("menu-ask-meeting"))
                HorizontalDivider()
            }
            // --- sharing (disabled while running): one entry, the Share sheet picks what and where ---
            if (canExport && onShare != null) {
                DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.share_title)) }, onClick = pick(onShare),
                    modifier = Modifier.testTag("menu-share"))
                HorizontalDivider()
            }
            DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.cd_settings)) }, onClick = pick(onSettings))
            if (onDelete != null) {
                HorizontalDivider()
                DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = pal.Record) },
                    text = { Text(stringResource(R.string.action_delete), color = pal.Record) }, onClick = pick(onDelete))
            }
        }
    }
}

/** Summary · Transcript session tabs: the M3 primary TabRow (indicator under the selected tab). */
@Composable
fun SessionTabs(selected: Int, onSelect: (Int) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val labels = listOf(
        stringResource(R.string.tab_summary),
        stringResource(R.string.tab_transcript),
    )
    androidx.compose.material3.PrimaryTabRow(
        selectedTabIndex = selected,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        containerColor = Color.Transparent,
        contentColor = pal.Sky,
        divider = { androidx.compose.material3.HorizontalDivider(color = pal.Hairline) },
    ) {
        labels.forEachIndexed { i, label ->
            androidx.compose.material3.Tab(
                selected = i == selected,
                onClick = { onSelect(i) },
                selectedContentColor = pal.Sky,
                unselectedContentColor = pal.Slate400,
                text = { Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) },
            )
        }
    }
}


/**
 * Redesign: the meeting's segmented control 摘要 / 筆記 n / 逐字稿 — a fill track with a raised
 * selected segment. Each segment is a [Role.Tab] with its selected state (TalkBack: "tab, selected").
 */
@Composable
fun SessionSegmented(selected: Int, labels: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPrimeMinutesPalette.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.Fill).padding(3.dp).selectableGroup()
            .testTag("session-segmented"),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) (if (pal.isDark) pal.Slate700 else pal.Slate800) else Color.Transparent)
                    .then(if (on && !pal.isDark) Modifier.border(1.dp, pal.Hairline, RoundedCornerShape(9.dp)) else Modifier)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) pal.Slate200 else pal.Slate400)
            }
        }
    }
}
