package studio.primeminutes.ui

import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import studio.primeminutes.ui.confirm
import studio.primeminutes.ui.reject
import studio.primeminutes.ui.toggleOn
import studio.primeminutes.ui.toggleOff
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import studio.primeminutes.R
import studio.primeminutes.core.ask.AskEngine
import studio.primeminutes.core.embed.LibrarySearch
import studio.primeminutes.core.reader.SpeakerRefs
import studio.primeminutes.core.ask.AskHistory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.reader.ReaderProtocol
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import studio.primeminutes.ui.theme.Spacing
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import studio.primeminutes.core.library.LibraryCollections

/** `[[entryId@sec]]` as AskEngine publishes the model's `[[k]]` tags (and a tolerant `[[id@sec]`/`[id@sec]`). */
private val CITE_RE = Regex("""\[{1,2}([A-Za-z0-9_\-]+)@(\d+)]{1,2}""")
/** A tag still being written (streaming) or cut by the token cap: hidden rather than shown raw. */
private val OPEN_TAG_RE = Regex("""\[\[[^\]\n]*$""")
/** A bracketed tag that is no citation (the model sometimes writes `[[no excerpt]]`). */
private val BOGUS_TAG_RE = Regex("""\[\[[^@\]\n]*]]""")
/** Markdown bullets (`* `, `- `) the reader sometimes writes; the answer is plain text. */
private val BULLET_RE = Regex("""(?m)^[ \t]*[*-][ \t]+""")

/**
 * Ask (RAG, experimental): one conversation over the whole library. Opens with [question]
 * already asked; follow-ups go through the field at the bottom. Citations render as
 * "title · m:ss" and open the meeting there. Dismissing the sheet frees the reader model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(
    question: String,
    entries: List<SessionLibrary.Entry>,
    onOpenAt: (SessionLibrary.Entry, Double) -> Unit,
    onDismiss: () -> Unit,
    /** The in-meeting Ask: questions over this meeting only, its earlier questions shown above. */
    scope: SessionLibrary.Entry? = null,
    /** Where it may look (≤ 3 levels, the first = where it was opened): a switch at the top; empty: [entries] / [scope]. */
    levels: List<LibraryCollections.AskLevel> = emptyList(),
) {
    var levelAt by remember { mutableStateOf(0) }
    val level = levels.getOrNull(levelAt)
    @Suppress("NAME_SHADOWING") val entries = level?.entries ?: entries
    @Suppress("NAME_SHADOWING") val scope = if (level != null) level.meeting else scope
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val lang = LocalConfiguration.current.locales[0].toLanguageTag()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val phase by AskEngine.phase.collectAsState()
    val turns by AskEngine.turns.collectAsState()
    var followUp by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    // Typing is the reason this sheet opens: the question field takes focus once the sheet settles
    // (iOS does the same in `fc2f9c65`).
    val askFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); runCatching { askFocus.requestFocus() } }

    // An empty question (the Ask button with no query): the sheet opens on its own question field.
    // The in-meeting Ask's earlier questions (AskHistory), read once per opening; new answers join
    // AskEngine.turns and are saved by the engine.
    var history by remember { mutableStateOf<List<AskEngine.Turn>>(emptyList()) }
    LaunchedEffect(scope?.id) {
        history = scope?.let { e -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AskHistory.turns(e, AskHistory.load(e.dir)) } } ?: emptyList()
    }
    // Opened on an empty field: load the search model while the question is typed (its cold load was most of the retrieval).
    LaunchedEffect(Unit) { if (question.isBlank() && studio.primeminutes.core.embed.EmbedHost.ready(context)) studio.primeminutes.core.embed.EmbedHost.preload(context, 2) }
    LaunchedEffect(question) { AskEngine.reset(); if (question.isNotBlank()) AskEngine.ask(context, entries, question, lang, scope) }
    val startedAt by AskEngine.startedAt.collectAsState()
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(phase.working) {
        while (phase.working) { now = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(500) }
    }
    DisposableEffect(Unit) { onDispose { AskEngine.release() } }

    val busy = phase.working
    // A long task ended → feel it, the way the export and the copy paths do (iOS `Haptics.success()`
    // fires at the same moment). Armed on the working edge, so opening a sheet that already holds
    // yesterday's answers stays silent.
    val askHaptics = LocalHapticFeedback.current
    var askWasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) askWasBusy = true
        else if (askWasBusy) { askWasBusy = false; askHaptics.confirm() }
    }
    // Another scope: the conversation starts over there, the last question asked again.
    fun switchLevel(i: Int) {
        if (i == levelAt || busy) return
        val q = AskEngine.turns.value.lastOrNull()?.question ?: question
        levelAt = i
        AskEngine.reset()
        val l = levels[i]
        if (q.isNotBlank()) AskEngine.ask(context, l.entries, q, lang, l.meeting)
    }
    fun send() {
        val q = followUp.trim(); if (q.isEmpty() || busy) return
        followUp = ""; AskEngine.ask(context, entries, q, lang, scope)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = pal.Slate800) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Title and chip on one line, the meeting's name under them (in-meeting); the actions keep the right.
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (scope != null) R.string.ask_meeting_title else R.string.ask_title),
                            style = studio.primeminutes.ui.theme.TypeRole.meetingTitle(), color = pal.Slate200,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.ask_experimental), style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = pal.WarningText,
                            modifier = Modifier.clip(CircleShape).background(pal.WarningTint)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                    if (scope != null) Text(
                        scope.title?.let { shownGen(it) } ?: scope.id, style = MaterialTheme.typography.labelMedium, color = pal.Slate400,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (busy) IconButton(onClick = { AskEngine.cancel() }) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.ask_cancel), tint = pal.Slate400)
                }
                // Start over: a new conversation (library-wide; closing the sheet would also unload the
                // reader), and in-meeting it also forgets this meeting's earlier questions (iOS: both).
                // In-meeting the saved questions are deleted for good, so that path asks first (iOS
                // confirmationDialog); the library-wide reset only drops this sheet's conversation.
                fun startOver() {
                    AskEngine.reset(); history = emptyList()
                    scope?.dir?.let { d -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { AskHistory.clear(d) } }
                    followUp = ""; runCatching { askFocus.requestFocus() }
                }
                if (confirmClear) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.DeleteSweep, contentDescription = null) },
                    title = { Text(stringResource(R.string.ask_meeting_clear)) },
                    text = { Text(stringResource(R.string.ask_meeting_clear_body)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmClear = false; startOver() }, modifier = Modifier.testTag("ask-meeting-clear-confirm")) {
                            Text(stringResource(R.string.ask_meeting_clear_action), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
                    },
                )
                if (!busy && (history.isNotEmpty() || turns.isNotEmpty())) IconButton(onClick = {
                    if (scope != null) confirmClear = true else startOver()
                }, modifier = Modifier.testTag(if (scope != null) "ask-meeting-clear" else "ask-reset")) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(if (scope != null) R.string.ask_meeting_clear else R.string.ask_reset), tint = pal.Slate400)
                }
            }
            if (levels.size > 1) AskScopeSwitch(levels, levelAt, enabled = !busy, onPick = ::switchLevel)
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                if (history.isNotEmpty()) Text(
                    stringResource(R.string.ask_meeting_earlier), style = MaterialTheme.typography.labelSmall,
                    color = pal.Slate400, modifier = Modifier.padding(top = 10.dp),
                )
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // The answer, the passages and the titles in the app's script; the questions stay as typed.
                val cc = LocalScreenScript.current
                val gen: (String) -> String = { cc?.gen?.invoke(it) ?: it }
                // The earlier questions (saved), then this opening's; the saved ones are only shown.
                (history + turns).forEachIndexed { n, t ->
                    if (n == history.size && history.isNotEmpty()) androidx.compose.material3.HorizontalDivider(
                        Modifier.padding(top = Spacing.md), color = pal.Hairline,
                    )
                    // Redesign: the question is a right-aligned bubble (fill, 16 dp, tail corner 4 dp); the answer reads
                    // as prose under it, full width, with the summary's citation pills.
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            t.question, style = MaterialTheme.typography.bodyMedium, color = pal.Slate200,
                            modifier = Modifier.fillMaxWidth(0.82f).wrapContentWidth(Alignment.End)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                                .background(pal.Fill).padding(horizontal = 14.dp, vertical = 10.dp).testTag("ask-question-bubble"),
                        )
                    }
                    // Selectable (long-press, then copy) like iOS's `.textSelection`; a tap on a citation still opens it.
                    if (t.answer.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            answerText(gen(t.answer), t.pool, pal.Sky, onOpenAt, withTitle = scope == null, title = gen,
                                speakerN = { n -> ctx.getString(R.string.speaker_n, n) }),
                            style = studio.primeminutes.ui.theme.TypeRole.readingBody(), color = pal.Slate200,
                        )
                    }
                    if (t.done && t.excerpts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.ask_sources), style = MaterialTheme.typography.labelSmall,
                            color = pal.Slate400, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                        t.excerpts.distinctBy { it.entry.id to it.sec / 60 }.forEach { x ->
                            Text(
                                // In-meeting: the time and the passage's first words (the meeting is known; a saved turn has no text).
                                if (scope != null) ReaderProtocol.formatTs(x.sec) + named(x.copy(text = cc?.tr?.invoke(x.text) ?: x.text), speakerN = { n -> ctx.getString(R.string.speaker_n, n) }).trim().replace('\n', ' ').let { if (it.isEmpty()) "" else "  $it" }
                                else "${x.entry.title?.let(gen) ?: x.entry.id} · ${ReaderProtocol.formatTs(x.sec)}",
                                style = MaterialTheme.typography.labelMedium, color = pal.Sky,
                                // Two lines: at large font a long meeting title ate the timestamp, so sources looked identical.
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(role = Role.Button) { onOpenAt(x.entry, x.at) }
                                    .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(vertical = 2.dp),
                            )
                        }
                    }
                }
                // The stage and the seconds since the question: a 20 s wait with a bare spinner looked hung.
                val secs = AskEngine.elapsedSec(startedAt, now)
                val status = when (val p = phase) {
                    AskEngine.Phase.Retrieving -> stringResource(if (scope != null) R.string.ask_meeting_stage_searching else R.string.ask_stage_searching, secs)
                    is AskEngine.Phase.Reading -> stringResource(R.string.ask_stage_reading, p.excerpts, secs)
                    AskEngine.Phase.Answering -> stringResource(R.string.ask_stage_answering, secs)
                    AskEngine.Phase.Busy -> stringResource(R.string.ask_busy)
                    is AskEngine.Phase.Failed -> stringResource(R.string.ask_failed, p.error)
                    AskEngine.Phase.Idle -> null
                }
                if (status != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    if (busy) { CircularProgressIndicator(Modifier.size(14.dp), color = pal.Sky, strokeWidth = 2.dp); Spacer(Modifier.width(Spacing.sm)) }
                    // Polite live region: the stage and its seconds change under a streaming answer,
                    // so a TalkBack user hears "searching 3 s" → "reading 5 s" → the failure without
                    // hunting for the line (the transcript search's counter does the same).
                    Text(
                        status, style = MaterialTheme.typography.bodySmall,
                        color = if (phase is AskEngine.Phase.Failed) PrimeMinutesPalette.Red else pal.Slate400,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(
                    stringResource(R.string.ask_disclaimer), style = MaterialTheme.typography.labelSmall,
                    color = pal.Slate400, modifier = Modifier.padding(top = Spacing.md),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)
                    // Redesign: the composer is a fill pill.
                    .clip(CircleShape).background(pal.Fill)
                    .heightIn(min = 48.dp).padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                BasicTextField(
                    value = followUp, onValueChange = { followUp = it }, singleLine = true,
                    textStyle = TextStyle(color = pal.Slate200, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    cursorBrush = SolidColor(pal.Sky),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        if (followUp.isEmpty()) Text(stringResource(if (turns.isNotEmpty()) R.string.ask_followup_hint else if (scope != null) R.string.ask_meeting_hint else R.string.ask_open_hint), color = pal.Slate400, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    },
                    modifier = Modifier.weight(1f).focusRequester(askFocus),
                )
                // 48dp target: the sheet's primary action was a 32dp icon inside a 44dp row.
                IconButton(onClick = { send() }, enabled = followUp.isNotBlank() && !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.ask_cd), tint = if (followUp.isNotBlank() && !busy) pal.Sky else pal.Slate400, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The Ask sheet's scope switch: one segment per level, its name and item count. */
@Composable
private fun AskScopeSwitch(levels: List<LibraryCollections.AskLevel>, at: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Row(Modifier.fillMaxWidth().padding(top = Spacing.sm).clip(CircleShape).background(pal.Fill).padding(3.dp).testTag("ask-scope")) {
        levels.forEachIndexed { i, l ->
            val on = i == at
            val name = stringResource(when (l.level) {
                LibraryCollections.Level.MEETING -> R.string.ask_scope_meeting
                LibraryCollections.Level.CHANNEL -> R.string.ask_scope_channel
                LibraryCollections.Level.COLLECTION -> R.string.ask_scope_collection
                LibraryCollections.Level.PODCASTS -> R.string.ask_scope_podcasts
                LibraryCollections.Level.MEETINGS -> R.string.ask_scope_meetings
                LibraryCollections.Level.RECEIVED -> R.string.ask_scope_received
                LibraryCollections.Level.ALL -> R.string.ask_scope_all
            })
            Text(
                if (l.meeting != null) name else stringResource(R.string.ask_scope_count, name, l.entries.size),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) pal.Slate200 else pal.Slate400,
                modifier = Modifier.weight(1f).clip(CircleShape).background(if (on) pal.Slate800 else androidx.compose.ui.graphics.Color.Transparent)
                    .semantics { selected = on; role = Role.Tab }
                    .clickable(enabled = enabled) { onPick(i) }.padding(vertical = 8.dp, horizontal = 4.dp),
            )
        }
    }
    val l = levels[at]
    if (l.level != LibraryCollections.Level.ALL && l.meeting == null) Text(
        stringResource(R.string.ask_scope_only, l.entries.size), style = MaterialTheme.typography.labelSmall, color = pal.Slate400,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

/** An excerpt's text with its S-numbers (a note passage) named as its meeting names them (display only). */
private fun named(x: AskEngine.Excerpt, speakerN: (Int) -> String): String {
    val (names, known) = LibrarySearch.speakers(x.entry) ?: return x.text
    return SpeakerRefs.resolve(x.text, { id -> names[id] ?: speakerN(id + 1) }, known.ifEmpty { null })
}

/** The answer with `[[id@sec]]` tags turned into superscript "title · m:ss" links. */
private fun answerText(
    raw: String, excerpts: List<AskEngine.Excerpt>, color: androidx.compose.ui.graphics.Color,
    onOpenAt: (SessionLibrary.Entry, Double) -> Unit,
    /** The meeting's title in the chip; the in-meeting Ask shows the time alone. */
    withTitle: Boolean = true,
    /** The localized "Speaker N" (1-based) for a speaker without a custom name. */
    speakerN: ((Int) -> String)? = null,
    /** A meeting title as shown (the app's script). */
    title: (String) -> String = { it },
): AnnotatedString = buildAnnotatedString {
    var raw = BULLET_RE.replace(BOGUS_TAG_RE.replace(OPEN_TAG_RE.replace(raw, ""), ""), "• ").trimEnd()
    // The model writes speakers as its S-numbers (from the notes): name them as each cited meeting does.
    if (speakerN != null) raw = SpeakerRefs.resolveCited(raw, CITE_RE) { m ->
        val e = AskEngine.cite(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0, excerpts)?.entry ?: return@resolveCited null
        val (names, known) = LibrarySearch.speakers(e) ?: return@resolveCited null
        SpeakerRefs.Labels({ id -> names[id] ?: speakerN(id + 1) }, known.ifEmpty { null })
    }
    var last = 0
    for (m in CITE_RE.findAll(raw)) {
        append(raw.substring(last, m.range.first).trimEnd(' '))
        last = m.range.last + 1
        // Checked against the excerpts the model was shown in this conversation: a tag pointing anywhere else is dropped silently.
        val x = AskEngine.cite(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0, excerpts) ?: continue
        val e = x.entry
        append('⁠')
        withLink(LinkAnnotation.Clickable(
            tag = "cite-${e.id}@${x.sec}",
            // Redesign: the same citation pill as the summary (tinted fill, SemiBold accent, slightly raised).
            styles = TextLinkStyles(style = SpanStyle(color = color, background = color.copy(alpha = 0.12f), fontWeight = FontWeight.SemiBold,
                fontSize = 0.68.em, baselineShift = BaselineShift(0.25f))),
            linkInteractionListener = { onOpenAt(e, x.at) },   // the passage's exact start (a tag carries whole seconds)
        )) { append(if (withTitle) "\u2009${title(e.title ?: "").take(24)} · ${ReaderProtocol.formatTs(x.sec)}\u2009" else "\u2009${ReaderProtocol.formatTs(x.sec)}\u2009") }
    }
    append(raw.substring(last))
}
