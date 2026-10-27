package studio.primeminutes

// The Session screen's stateless pieces (cards, player bar, timeline, transcript rows), split out of
// MainActivity: they take state and callbacks and own no session logic.

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.core.content.IntentCompat
import studio.primeminutes.core.export.ExportFormat
import studio.primeminutes.core.export.TranscriptExport
import java.io.File
import studio.primeminutes.R
import studio.primeminutes.core.asr.AsrBackend
import studio.primeminutes.core.audio.AudioDecoder
import studio.primeminutes.core.audio.ImportRecovery
import studio.primeminutes.core.audio.RecordingRecovery
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.session.SessionAutosave
import studio.primeminutes.core.config.ConfigStore
import studio.primeminutes.core.config.SummaryScript
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.power.BackgroundReliability
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.session.RecentSession
import studio.primeminutes.core.session.RecentSessions
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.core.update.UpdateChecker
import studio.primeminutes.core.update.UpdateInfo
import studio.primeminutes.core.update.UpdateInstaller
import studio.primeminutes.data.SpeakerEdits
import studio.primeminutes.data.SpeakerName
import studio.primeminutes.data.computeDiarizationStats
import androidx.compose.ui.graphics.SolidColor
import studio.primeminutes.data.speakerColor
import studio.primeminutes.data.speakerColorOn
import studio.primeminutes.service.TranscriptionService
import studio.primeminutes.ui.AddSourceSheet
import studio.primeminutes.ui.ExportSheet
import studio.primeminutes.ui.CaptureScreen
import studio.primeminutes.ui.SessionTabs
import studio.primeminutes.ui.SessionTopBar
import studio.primeminutes.ui.StudioScreen
import studio.primeminutes.ui.ConfigSheet
import studio.primeminutes.ui.EmptyState
import studio.primeminutes.ui.PodcastSheet
import studio.primeminutes.ui.UpdateBanner
import studio.primeminutes.ui.renderMarkdown
import studio.primeminutes.ui.SpeakerStatsPanel
import studio.primeminutes.ui.TranscriptSearchBar
import studio.primeminutes.core.embed.EmbedHost
import studio.primeminutes.core.embed.EmbedRegistry
import studio.primeminutes.core.embed.Embedder
import studio.primeminutes.core.embed.LibraryIndexer
import studio.primeminutes.core.embed.LibrarySearch
import studio.primeminutes.core.embed.SessionIndex
import studio.primeminutes.ui.highlightedTranscript
import studio.primeminutes.ui.YouTubeSheet
import studio.primeminutes.ui.theme.LanguageController
import studio.primeminutes.ui.theme.LocalLanguageController
import studio.primeminutes.ui.theme.LocalThemeController
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.ThemeController
import studio.primeminutes.ui.theme.PrimeMinutesTheme
import studio.primeminutes.ui.theme.primeMinutesSliderColors
import studio.primeminutes.ui.theme.primeMinutesTextFieldColors
import studio.primeminutes.ui.theme.Spacing
import studio.primeminutes.core.config.ThemeMode
import studio.primeminutes.core.config.ThemeStore

/**
 * "Exporting…" overlay shown while a session .ogg is built/written. The work runs in the foreground
 * service, so it finishes even if the app is closed — the overlay is therefore dismissable (tap away
 * / back) and the export keeps going; the result arrives via a snackbar (save) or share chooser.
 */
@Composable
internal fun ExportingOverlay(onDismiss: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = pal.PanelSurface, tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                WorkingIndicator()
                Spacer(Modifier.width(Spacing.lg))
                Column {
                    Text(stringResource(R.string.exporting), color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.exporting_hint), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A "working" indicator that avoids continuous animation on e-ink (a spinning ring ghosts the
 *  e-paper) — a static accent dot there, the normal spinner on LCD. */
@Composable
internal fun WorkingIndicator(size: Dp = 28.dp) {
    val pal = LocalPrimeMinutesPalette.current
    if (pal.isEink) {
        Box(Modifier.size(size).clip(CircleShape).background(pal.ActiveBar))
    } else {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(size))
    }
}

/** Brief non-dismissable "Opening…" overlay while a tapped session file decodes (the load switches
 *  to the Session screen when done), so a row tap gives immediate feedback instead of a dead pause. */
@Composable
internal fun OpeningOverlay() {
    val pal = LocalPrimeMinutesPalette.current
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = pal.PanelSurface, tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                WorkingIndicator()
                Spacer(Modifier.width(Spacing.lg))
                Text(stringResource(R.string.opening_session), color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** One section of the v2 structured notes — a localized header over its bullets. The section
 *  KEYS are wire format and never shown; this renders the app's own heading. */
@Composable
internal fun NotesSection(
    header: String,
    items: List<String>,
    /** Seek the player to a millisecond offset — makes each bullet's [m:ss] anchor a tap target. */
    onSeek: ((Int) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val pal = LocalPrimeMinutesPalette.current
    Card(colors = CardDefaults.cardColors(containerColor = pal.Slate800)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(header, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold, color = pal.Slate200,
                 modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            items.forEach {
                // Through renderMarkdown so the bullet's trailing [m:ss] becomes a tap target that
                // seeks the recording — the anchor exists so a claim can be CHECKED, and reading a
                // timestamp then hunting for it with the scrubber defeats that.
                Text(
                    renderMarkdown("- $it", anchorColor = if (onSeek != null) pal.Sky else null,
                                   onSeek = onSeek),
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.Slate200, modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** Which ASR pipeline produced the transcript, diarization and timestamps — the counterpart to the
 *  "via <model>" line on the title and summary cards. Sits above the first utterance in BOTH
 *  layouts because it is emitted from the shared [LazyListScope] builder. */
@Composable
internal fun TranscriptPipelineNote(asr: String, diarization: String) {
    val pal = LocalPrimeMinutesPalette.current
    Text(
        stringResource(R.string.pipeline_transcript_via, asr, diarization),
        style = MaterialTheme.typography.labelSmall,
        color = pal.Slate400,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

/** Generated-title card with model attribution. */
@Composable
internal fun TitleCard(
    title: String, llm: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (isEditing) {
            UtteranceTextEditor(initial = title, onSave = onSave, onCancel = onCancel, minLines = 1)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    studio.primeminutes.ui.LocalSpeakerRefs.current(title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = pal.Slate200,
                    modifier = Modifier.weight(1f).clickable { onBeginEdit() },
                )
                EditPencil(onBeginEdit)
            }
            // No "via <model>" here: the summary card right below already credits the model.
        }
    }
}

/** Markdown display folded past [collapsedMaxLines] behind Show more/Show less — a long summary
 *  must not push the transcript below the fold. Tap the text to edit, as before. */
@Composable
internal fun CollapsibleMarkdown(
    text: String,
    collapsedMaxLines: Int,
    onBeginEdit: () -> Unit,
    /** Seek the player to a millisecond offset. Null → anchors render as plain text. */
    onSeek: ((Int) -> Unit)? = null,
) {
    val pal = LocalPrimeMinutesPalette.current
    // remember(text): a fresh summary (or each streamed partial) starts collapsed.
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowed by remember(text) { mutableStateOf(false) }
    Text(
        // Speaker names (already resolved from the model's S1, S2) in their transcript colour.
        studio.primeminutes.ui.colorSpeakerNames(
            renderMarkdown(text, anchorColor = if (onSeek != null) pal.Slate400 else null, onSeek = onSeek),
            studio.primeminutes.ui.LocalSpeakerNames.current,
        ) { id -> Color(speakerColorOn(id, pal.isDark)) },
        style = MaterialTheme.typography.bodyMedium,
        color = pal.Slate200,
        maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { overflowed = it.hasVisualOverflow },
        modifier = Modifier.fillMaxWidth().clickable { onBeginEdit() },
    )
    if (overflowed || expanded) {
        Text(
            stringResource(if (expanded) R.string.show_less else R.string.show_more),
            style = MaterialTheme.typography.labelMedium,
            color = pal.Slate400,
            // Role + 48dp minimum: the bare label was a ~16dp-high tap target that TalkBack read as plain text.
            modifier = Modifier.padding(top = Spacing.xs)
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button) { expanded = !expanded },
        )
    }
}

/** Summary card with model attribution (export is in the top-bar menu). */
@Composable
internal fun SummaryCard(
    summary: String, llm: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit, onCopy: () -> Unit,
    onSeek: ((Int) -> Unit)? = null,
    /** Off when a tab already carries the name (portrait): the card then keeps only its tools. */
    showHeading: Boolean = true,
) {
    val pal = LocalPrimeMinutesPalette.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showHeading) Text(
                stringResource(R.string.card_summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = pal.Slate200,
                modifier = Modifier.weight(1f).semantics { heading() },
            ) else Spacer(Modifier.weight(1f))
            if (!isEditing) {
                // One-tap copy to clipboard (the summary export was removed — the .ogg is the editor).
                // No size override: IconButton's 48dp box is the touch target (28dp was under the 44dp minimum).
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_summary),
                        tint = pal.Slate400, modifier = Modifier.size(16.dp))
                }
                EditPencil(onBeginEdit)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        if (isEditing) {
            UtteranceTextEditor(initial = summary, onSave = onSave, onCancel = onCancel, minLines = 4)
        } else {
            CollapsibleMarkdown(studio.primeminutes.ui.LocalSpeakerRefs.current(summary), collapsedMaxLines = 12, onBeginEdit = onBeginEdit, onSeek = onSeek)
        }
        // Faithfulness caution — UI chrome only, never part of the exported summary text.
        // The shipped summarizer is a PLACEHOLDER running un-fine-tuned base weights, which can
        // state facts backwards (e.g. "revenue dropped 11%" for "11% above forecast"). Revisit
        // (soften or remove) once the fine-tuned model ships and its faithfulness is measured.
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.summary_ai_caution),
            style = MaterialTheme.typography.labelSmall,
            color = pal.Slate400,
        )
    }
}

/** Action items + decisions card — an editable draft (the model can miss or invent items). */
@Composable
internal fun ActionItemsCard(
    text: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit, onCopy: () -> Unit,
    onSeek: ((Int) -> Unit)? = null,
    showHeading: Boolean = true,
) {
    val pal = LocalPrimeMinutesPalette.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showHeading) Text(
                stringResource(R.string.card_action_items),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = pal.Slate200,
                modifier = Modifier.weight(1f).semantics { heading() },
            ) else Spacer(Modifier.weight(1f))
            if (!isEditing) {
                // No size override: IconButton's 48dp box is the touch target (28dp was under the 44dp minimum).
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_actions),
                        tint = pal.Slate400, modifier = Modifier.size(16.dp))
                }
                EditPencil(onBeginEdit)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        if (isEditing) {
            UtteranceTextEditor(initial = text, onSave = onSave, onCancel = onCancel, minLines = 3)
        } else {
            CollapsibleMarkdown(studio.primeminutes.ui.LocalSpeakerRefs.current(text), collapsedMaxLines = 8, onBeginEdit = onBeginEdit, onSeek = onSeek)
            // Verify affordance (integration note §7): about one action item in five is not
            // supported by the transcript — each time jumps to what was actually said.
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.actions_verify_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalPrimeMinutesPalette.current.WarningText,
            )
        }
    }
}

/** Per-line speaker fix: move this line to another speaker, or merge this speaker into another. */
@Composable
internal fun LineMenu(
    current: Int?,
    speakerIds: List<Int>,
    speakerNames: Map<Int, SpeakerName>,
    onEdit: () -> Unit,
    onReassign: (Int) -> Unit,
    onMerge: (Int) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSplit: (() -> Unit)? = null,
) {
    val pal = LocalPrimeMinutesPalette.current
    val open = expanded
    val others = if (current == null) emptyList() else speakerIds.filter { it != current }
    // No per-line ⋮ any more (it cost ~40 dp on every line): the menu opens on a long-press of the line.
    Box {
        DropdownMenu(expanded = open, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cd_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { onExpandedChange(false); onEdit() },
            )
            if (onSplit != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.line_split)) },
                leadingIcon = { Icon(Icons.Filled.UnfoldMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { onExpandedChange(false); onSplit() },
            )
            if (others.isEmpty()) return@DropdownMenu
            HorizontalDivider()
            DropdownMenuItem(enabled = false, onClick = {},
                text = { Text(stringResource(R.string.speaker_move_line), style = MaterialTheme.typography.labelSmall, color = pal.Slate400) })
            others.forEach { sid ->
                val label = speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1)
                DropdownMenuItem(text = { Text(label) }, onClick = { onExpandedChange(false); onReassign(sid) })
            }
            HorizontalDivider()
            DropdownMenuItem(enabled = false, onClick = {},
                text = { Text(stringResource(R.string.speaker_merge_into), style = MaterialTheme.typography.labelSmall, color = pal.Slate400) })
            others.forEach { sid ->
                val label = speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1)
                DropdownMenuItem(text = { Text(label) }, onClick = { onExpandedChange(false); onMerge(sid) })
            }
        }
    }
}

/** Small pencil affordance reused by the title/summary cards (matches the utterance-row edit icon). */
@Composable
internal fun EditPencil(onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    // No size override: IconButton's 48dp box is the touch target (28dp was under the 44dp minimum).
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = stringResource(R.string.cd_edit),
            tint = pal.Slate400,
            modifier = Modifier.size(16.dp),
        )
    }
}

internal fun fmt(sec: Double): String {
    val s = sec.toInt()
    return fmtSec(s)
}

internal fun fmtMs(ms: Int): String {
    val s = ms / 1000
    return fmtSec(s)
}

// Line times match the exports and the reader's prompt: h:mm:ss from the first hour, so a 75-minute
// meeting reads "1:15:03", not the unreadable "75:03" (m:ss under 3600 s, as before).
internal fun fmtSec(s: Int): String {
    val v = s.coerceAtLeast(0)
    return if (v >= 3600) "%d:%02d:%02d".format(v / 3600, (v / 60) % 60, v % 60)
    else "%d:%02d".format(v / 60, v % 60)
}

/** A rounded surface card used to group a section (settings, podcast, summary). */
@Composable
internal fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pal.PanelSurface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, pal.Hairline),
        // Hairline border + a whisper of shadow: 8 dp read as heavy floating slabs on the light theme.
        elevation = CardDefaults.cardElevation(defaultElevation = if (pal.isDark) 0.dp else 1.dp),
    ) {
        Column(Modifier.padding(Spacing.lg), content = content)
    }
}

@Composable
internal fun PlayerBar(
    utterances: List<TranscriptEvent.Utterance>,
    positionMs: Int,
    durationMs: Int,
    dragMs: Int?,
    isPlaying: Boolean,
    volume: Float,
    muted: Boolean,
    activeIndex: Int,
    onPlayPause: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onDragChange: (Int?) -> Unit,
    onSkip: (Int) -> Unit,
    onVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
    compact: Boolean = false,
    buffering: Boolean = false,
) {
    val pal = LocalPrimeMinutesPalette.current
    val shownMs = dragMs ?: positionMs
    // Two slim rows: the seek bar IS the speaker timeline (one merged scrubber), then a centered
    // transport with the times at the edges and volume tucked behind a popup.
    // 28dp strip: at 22dp the scrubber was precise enough for a stylus but fiddly for a thumb.
    val stripH = if (compact) 16.dp else 28.dp
    // 44 dp minimum touch target on both densities (38/34 dp was below it).
    val playSize = if (compact) 44.dp else 48.dp
    val btnSize = if (compact) 40.dp else 44.dp
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 4.dp)) {
        TimelineStrip(
            utterances = utterances,
            durationMs = durationMs,
            activeIndex = activeIndex,
            progressMs = shownMs,
            onSeekTo = onSeekTo,
            onDragChange = onDragChange,
            modifier = Modifier.fillMaxWidth().height(stripH),
        )
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        // Box, not a Row with spacers: the edges (elapsed | volume + duration) differ in width, so
        // weighted spacers pushed the transport off the screen centre. Centre it on the box.
        Box(Modifier.fillMaxWidth()) {
            Text(fmtMs(shownMs), style = MaterialTheme.typography.labelSmall, color = pal.Slate400,
                modifier = Modifier.align(Alignment.CenterStart))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Center)) {
                IconButton(onClick = { onSkip(-5000) }, modifier = Modifier.size(btnSize)) {
                    Icon(Icons.Filled.Replay5, contentDescription = stringResource(R.string.cd_back5), tint = pal.Slate200)
                }
                Box(
                    Modifier
                        .size(playSize)
                        .clip(CircleShape)
                        // Dark: the solid accent (the brand fill is a navy that swallows the dark glyph).
                        .background(if (pal.isDark) androidx.compose.ui.graphics.SolidColor(pal.Sky) else pal.BrandGradient)
                        .clickable(role = Role.Button, onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                        tint = pal.Slate900,
                    )
                    // Underrun: overlay a spinner so it reads as "buffering, will resume" not "frozen".
                    if (buffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(playSize),
                            color = pal.Slate900,
                            strokeWidth = 2.dp,
                        )
                    }
                }
                IconButton(onClick = { onSkip(5000) }, modifier = Modifier.size(btnSize)) {
                    Icon(Icons.Filled.Forward5, contentDescription = stringResource(R.string.cd_forward5), tint = pal.Slate200)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterEnd)) {
                VolumeControl(volume = volume, muted = muted, onVolume = onVolume, onToggleMute = onToggleMute, btnSize = btnSize)
                Spacer(Modifier.width(6.dp))
                Text(fmtMs(durationMs), style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
            }
        }
    }
}

/** Volume behind a tap-to-open popup (a horizontal slider above the speaker icon), so the player bar
 *  doesn't reserve a permanent row/strip for a control that's rarely touched. */
@Composable
internal fun VolumeControl(
    volume: Float,
    muted: Boolean,
    onVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
    btnSize: Dp,
) {
    val pal = LocalPrimeMinutesPalette.current
    var open by remember { mutableStateOf(false) }
    val muteIcon = if (muted || volume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp
    val above = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(btnSize)) {
            Icon(muteIcon, contentDescription = stringResource(R.string.cd_mute), tint = pal.Slate400)
        }
        if (open) {
            Popup(
                popupPositionProvider = above,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = pal.Slate800,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.padding(start = Spacing.xs, end = Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onToggleMute, modifier = Modifier.size(48.dp)) {
                            Icon(muteIcon, contentDescription = if (muted) stringResource(R.string.cd_unmute) else stringResource(R.string.cd_mute), tint = pal.Slate200)
                        }
                        Slider(
                            value = if (muted) 0f else volume,
                            valueRange = 0f..1f,
                            onValueChange = onVolume,
                            colors = primeMinutesSliderColors(),
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TimelineStrip(
    utterances: List<TranscriptEvent.Utterance>,
    durationMs: Int,
    activeIndex: Int,
    progressMs: Int,
    onSeekTo: (Int) -> Unit,
    onDragChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPrimeMinutesPalette.current
    val durSec = (durationMs / 1000.0).coerceAtLeast(0.001)
    var dragging by remember { mutableStateOf(false) }
    val seekLabel = stringResource(R.string.cd_seek_bar)
    Canvas(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(pal.Slate800)
            // A Canvas has no semantics: TalkBack skipped the scrubber. Expose it as a slider
            // (position read out as time, volume keys / swipe step by 1/20 of the meeting).
            .semantics {
                contentDescription = seekLabel
                stateDescription = "${fmtMs(progressMs)} / ${fmtMs(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(progressMs.toFloat(), 0f..durationMs.coerceAtLeast(1).toFloat(), 20)
                setProgress { v -> if (durationMs > 0) { onSeekTo(v.toInt().coerceIn(0, durationMs)); true } else false }
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (size.width > 0 && durationMs > 0) {
                        onSeekTo(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toInt())
                    }
                }
            }
            .pointerInput(durationMs) {
                // Drag the playhead to scrub; report live position, commit on release.
                var pos = 0
                fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * durationMs).toInt()
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (size.width > 0 && durationMs > 0) { dragging = true; pos = at(o.x); onDragChange(pos) } },
                    onHorizontalDrag = { change, _ -> if (size.width > 0 && durationMs > 0) { pos = at(change.position.x); onDragChange(pos) } },
                    onDragEnd = { dragging = false; if (durationMs > 0) onSeekTo(pos); onDragChange(null) },
                    onDragCancel = { dragging = false; onDragChange(null) },
                )
            },
    ) {
        if (durationMs <= 0) return@Canvas
        val w = size.width
        val h = size.height
        // Inactive segments are a quiet, vertically-inset wash (a near-solid strip of full-height
        // speaker blocks read as an error bar, not a scrubber); only the ACTIVE segment pops —
        // full height, near-opaque, white outline.
        val segTop = h * 0.22f
        val segH = h * 0.56f
        val playX = (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) * w
        utterances.forEachIndexed { i, u ->
            val startX = (u.startSec / durSec).toFloat().coerceIn(0f, 1f) * w
            val endX = (u.endSec / durSec).toFloat().coerceIn(0f, 1f) * w
            val segW = (endX - startX).coerceAtLeast(1.5f)
            val active = i == activeIndex
            val base = Color(speakerColorOn(u.speaker, pal.isDark))
            // Already-heard speech reads stronger than what is still ahead: progress at a glance.
            val played = endX <= playX
            drawRoundRect(
                color = if (active) base.copy(alpha = 0.9f) else base.copy(alpha = if (played) 0.55f else 0.2f),
                topLeft = Offset(startX, if (active) 0f else segTop),
                size = Size(segW, if (active) h else segH),
                cornerRadius = CornerRadius(2f, 2f),
            )
            if (active) {
                // The active segment's ring: Slate200 is each theme's ink (near-white on dark, deep
                // navy on light, black on e-ink) — white at 35 % drew nothing on the white cards.
                drawRoundRect(
                    color = pal.Slate200.copy(alpha = 0.55f),
                    topLeft = Offset(startX, 0f),
                    size = Size(segW, h),
                    cornerRadius = CornerRadius(2f, 2f),
                    style = Stroke(width = 2f),
                )
            }
        }
        // Playhead: a full-height bar in the text colour (not a speaker hue, not the accent of the
        // play button below), haloed in the background colour so it stays visible over any
        // segment. It covers almost nothing of the speaker under it, and widens while dragged.
        val barW = (if (dragging) 6.dp else 3.dp).toPx()
        val halo = 2.dp.toPx()
        val cx = playX.coerceIn(barW / 2 + halo, w - barW / 2 - halo)
        drawRoundRect(pal.Slate900, topLeft = Offset(cx - barW / 2 - halo, 0f), size = Size(barW + 2 * halo, h), cornerRadius = CornerRadius(barW, barW))
        drawRoundRect(pal.Slate200, topLeft = Offset(cx - barW / 2, 0f), size = Size(barW, h), cornerRadius = CornerRadius(barW / 2, barW / 2))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun UtteranceRow(
    utt: TranscriptEvent.Utterance,
    showSpeaker: Boolean = true,
    active: Boolean,
    highlight: String,
    isEditing: Boolean,
    speakerNames: Map<Int, SpeakerName>,
    editingSpeakerId: Int?,
    onSeek: (Double) -> Unit,
    onBeginEdit: () -> Unit,
    onSaveText: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onBeginSpeakerEdit: (Int) -> Unit,
    onCommitSpeakerName: (Int, String) -> Unit,
    onCancelSpeakerEdit: () -> Unit,
    speakerIds: List<Int>,
    onReassignLine: (Int) -> Unit,
    onMergeSpeaker: (Int) -> Unit,
    onSplitLine: (() -> Unit)? = null,
    topPadding: Dp? = null,
    /** False while a run rewrites the transcript: the line menu and the speaker name are off (an edit would be lost). */
    editable: Boolean = true,
) {
    val pal = LocalPrimeMinutesPalette.current
    // Two shapes:
    //  - READ rows are dense: timestamp + speaker chip rendered INLINE at the head of the text
    //    (the text wraps around them), so a one-line utterance costs one line, not a meta row
    //    plus a text row — ~30% more transcript per screen.
    //  - Any EDITING state (text or speaker name) falls back to the roomy stacked layout, which
    //    the editors were designed for.
    val editingThisSpeaker = editingSpeakerId != null && editingSpeakerId == utt.speaker
    Column(
        Modifier
            .fillMaxWidth()
            // A turn = one visual block: the first line opens it with clear space above
            // (OUTSIDE drawBehind, so the separator stays rail-free); continuation lines
            // are flush, making the speaker rail one unbroken bar over the whole turn.
            .padding(top = topPadding ?: if (showSpeaker) 20.dp else 8.dp)
            .drawBehind {
                // Speaker rail: a thin bar in the speaker's colour on EVERY line of a
                // same-speaker run. The chip is only shown on the run's first line, so
                // the rail is what visually attaches the continuation lines to their
                // speaker — without it, chip-less lines looked unattributed.
                utt.speaker?.let { sid ->
                    drawRect(
                        Color(speakerColorOn(sid, pal.isDark)).copy(alpha = 0.35f),
                        size = Size(2.5.dp.toPx(), size.height),
                    )
                }
                // Playing line: a neutral wash (the text colour at low alpha) — never a hue, which
                // a speaker could also own; the speaker rail above stays untouched.
                if (active) drawRect(pal.Slate200.copy(alpha = if (pal.isEink) 0.12f else 0.07f))
            }
            .padding(vertical = 4.dp, horizontal = 10.dp),
    ) {
        if (isEditing || editingThisSpeaker) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Same header as the reading layout (speaker · time); the chip only while renaming.
                if (!editingThisSpeaker) {
                    utt.speaker?.let { sid ->
                        Text(speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = Color(speakerColorOn(sid, pal.isDark)))
                        Text(" · ", style = MaterialTheme.typography.labelMedium, color = pal.Slate400)
                    }
                    Text(fmt(utt.startSec), style = MaterialTheme.typography.labelMedium, color = pal.Slate400)
                }
                if (editingThisSpeaker) utt.speaker?.let { sid ->
                    SpeakerTag(
                        speakerId = sid,
                        label = speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1),
                        editing = editingSpeakerId == sid,
                        onTap = { onBeginSpeakerEdit(sid) },
                        onCommit = { onCommitSpeakerName(sid, it) },
                        onCancel = onCancelSpeakerEdit,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            if (isEditing) {
                UtteranceTextEditor(initial = utt.text, onSave = onSaveText, onCancel = onCancelEdit)
            } else {
                Text(
                    highlightedTranscript(utt.text, highlight, pal.Sky, pal.Slate200),
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.Slate200,
                    modifier = Modifier.fillMaxWidth().clickable { onSeek(utt.startSec) }.padding(top = 2.dp),
                )
            }
        } else {
            val label = if (!showSpeaker) null
            else utt.speaker?.let { speakerNames[it]?.name ?: stringResource(R.string.speaker_n, it + 1) }
            var menuOpen by remember { mutableStateOf(false) }
            // A run starting under an open menu closes it for good (it must not pop back when the run ends).
            LaunchedEffect(editable) { if (!editable) menuOpen = false }
            // Reading layout: a slim header line (speaker in its colour · time), then the text on the
            // full width with a CJK-friendly line height. Tap seeks; long-press opens the line menu.
            Column(
                Modifier.fillMaxWidth().then(
                    if (editable) Modifier.combinedClickable(
                        onClick = { onSeek(utt.startSec) },
                        onLongClick = { menuOpen = true },
                        onLongClickLabel = stringResource(R.string.cd_line_actions),
                    ) else Modifier.clickable { onSeek(utt.startSec) },
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (label != null && utt.speaker != null) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = Color(speakerColorOn(utt.speaker, pal.isDark)),
                            modifier = if (editable) Modifier.clickable(role = Role.Button) { onBeginSpeakerEdit(utt.speaker) } else Modifier,
                        )
                        Text(" · ", style = MaterialTheme.typography.labelMedium, color = pal.Slate400)
                    }
                    // The playing line also says so in its timestamp (▶, full contrast) — a cue that
                    // does not rely on colour at all.
                    if (active) Text("▶ ${fmt(utt.startSec)}", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, color = pal.Slate200)
                    else Text(fmt(utt.startSec), style = MaterialTheme.typography.labelMedium, color = pal.Slate400)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    highlightedTranscript(utt.text, highlight, pal.Sky, pal.Slate200),
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.Slate200,
                )
                LineMenu(utt.speaker, speakerIds, speakerNames, onBeginEdit, onReassignLine, onMergeSpeaker,
                    expanded = menuOpen && editable, onExpandedChange = { menuOpen = it }, onSplit = onSplitLine)
            }
        }
    }
}

/** Inline-content id for the speaker chip embedded at the head of a transcript line. */
private const val INLINE_CHIP = "speakerChip"

@Composable
internal fun UtteranceTextEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit, minLines: Int = 2) {
    var text by remember(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = primeMinutesTextFieldColors(),
            minLines = minLines,
        )
        Row(Modifier.padding(top = Spacing.xs)) {
            Button(
                onClick = { onSave(text.trim()) },
                enabled = text.trim().isNotEmpty(),
                modifier = Modifier.padding(end = 6.dp),
            ) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

/**
 * Text style of a speaker chip. Shared by [SpeakerTag] and [speakerChipWidth] so the measured
 * placeholder and the rendered chip can never disagree.
 *
 * Tight line box: CJK glyphs at labelMedium's default 16 sp line height plus 3 dp paddings exceeded
 * the 18 sp inline placeholder and the bottom of 語者 was clipped. lineHeight == fontSize with no
 * extra font padding keeps the chip inside the placeholder with the glyphs fully visible.
 */
@Composable
internal fun speakerChipTextStyle() = MaterialTheme.typography.labelMedium.copy(
    // lineHeight == the (already scaled) font size: the literal 12 sp kept the line box frozen while
    // the app's text size grew the glyphs, which clipped 語者 again at large scales.
    lineHeight = MaterialTheme.typography.labelMedium.fontSize,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    fontWeight = FontWeight.Medium,
)

/** Chip padding (10 dp each side) + its 1 dp border each side. */
internal val SPEAKER_CHIP_CHROME = 22.dp

/** Exact inline-placeholder width for a speaker chip: measured label + the chip's own chrome. */
@Composable
internal fun speakerChipWidth(label: String): androidx.compose.ui.unit.TextUnit {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = speakerChipTextStyle()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(label, style, density) {
        val textPx = measurer.measure(label, style, maxLines = 1).size.width
        with(density) { (textPx.toDp() + SPEAKER_CHIP_CHROME).toSp() }
    }
}

@Composable
internal fun SpeakerTag(
    speakerId: Int,
    label: String,
    editing: Boolean,
    onTap: () -> Unit,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val color = Color(speakerColorOn(speakerId, LocalPrimeMinutesPalette.current.isDark))
    val bg = color.copy(alpha = 0.18f)
    if (!editing) {
        Surface(
            color = bg,
            shape = CircleShape,
            border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        ) {
            Text(
                label,
                style = speakerChipTextStyle(),
                color = color,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onTap)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    } else {
        // Whole name selected on open: renaming replaces "Speaker 2" instead of typing in front of it.
        var field by remember(label) {
            mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(label, androidx.compose.ui.text.TextRange(0, label.length)))
        }
        val value = field.text
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            // Was uncolored → default black text + black cursor, invisible on the dark chip. Color
            // both from the speaker color (already theme-adjusted so it's legible on every ground).
            textStyle = MaterialTheme.typography.labelMedium.copy(color = color),
            cursorBrush = SolidColor(color),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(value.trim()) }),
            modifier = Modifier
                .background(bg)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { ev ->
                    when {
                        ev.type == KeyEventType.KeyUp && ev.key == Key.Escape -> { onCancel(); true }
                        ev.type == KeyEventType.KeyUp && ev.key == Key.Enter -> { onCommit(value.trim()); true }
                        else -> false
                    }
                },
        )
        // Visible Save / Cancel (48dp, the minimum touch target): the keyboard's Done was the only way out (and it hides on some IMEs).
        IconButton(onClick = { onCommit(value.trim()) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.save), tint = color)
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel), tint = LocalPrimeMinutesPalette.current.Slate400)
        }
    }
}
