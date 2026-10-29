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
    onOpenExport: () -> Unit,
    onSettings: () -> Unit,
    /** "Email participants…" (docs/design/speaker-contacts.md §4.1), shown with the exports. */
    onEmailParticipants: () -> Unit = {},
    /** "Ask this meeting" in the ⋮ menu; null when the in-meeting Ask is not available. */
    onAskMeeting: (() -> Unit)? = null,
    /** "Share meeting" (the enriched file through the system sheet: Quick Share, AirDrop on iOS); null when
     *  the open meeting is not a processed library entry. */
    onShareMeeting: (() -> Unit)? = null,
    /** Tap on the title: edit it (null → not editable, e.g. no title yet). */
    onTitleClick: (() -> Unit)? = null,
    /** Undo / Redo of transcript hand-edits; the pair shows once there is something to undo or redo. */
    canUndo: Boolean = false, canRedo: Boolean = false,
    onUndo: () -> Unit = {}, onRedo: () -> Unit = {},
    /** Collapses the bar as the session content scrolls (null: pinned). */
    scrollBehavior: TopAppBarScrollBehavior? = null,
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
                    Text(
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
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_transcript), tint = pal.Slate400)
                    }
                }
                OverflowMenu(
                    canReTranscribe, onReTranscribe, canReSummarize, onReSummarize,
                    canResume, onResume,
                    canExport, onOpenExport, onSettings, onEmailParticipants, onAskMeeting, onShareMeeting,
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
    onOpenExport: () -> Unit,
    onSettings: () -> Unit,
    onEmailParticipants: () -> Unit,
    onAskMeeting: (() -> Unit)?,
    onShareMeeting: (() -> Unit)?,
) {
    val pal = LocalPrimeMinutesPalette.current
    var open by remember { mutableStateOf(false) }
    fun pick(action: () -> Unit): () -> Unit = { open = false; action() }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options), tint = pal.Slate400)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
            // --- exports (disabled while running, like before) ---
            // One entry, not eight: the formats and the save/share choice live in ExportSheet, which
            // groups them by what you get. A dropdown this long also meant several slow full-page
            // repaints on e-ink.
            if (canExport) {
                if (onShareMeeting != null) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.action_share_meeting)) }, onClick = pick(onShareMeeting),
                    modifier = Modifier.testTag("menu-share-meeting"))
                DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Email, null, Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.mail_menu_entry)) }, onClick = pick(onEmailParticipants),
                    modifier = Modifier.testTag("menu-email-participants"))
                DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.IosShare, null, Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.export_menu_entry)) }, onClick = pick(onOpenExport))
                HorizontalDivider()
            }
            DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.cd_settings)) }, onClick = pick(onSettings))
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
