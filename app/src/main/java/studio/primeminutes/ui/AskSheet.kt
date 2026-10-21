package studio.primeminutes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val lang = LocalConfiguration.current.locales[0].toLanguageTag()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val phase by AskEngine.phase.collectAsState()
    val turns by AskEngine.turns.collectAsState()
    var followUp by remember { mutableStateOf("") }
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
    fun send() {
        val q = followUp.trim(); if (q.isEmpty() || busy) return
        followUp = ""; AskEngine.ask(context, entries, q, lang, scope)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = pal.Slate800) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Title and chip on one line, the meeting's name under them (in-meeting); the actions keep the right.
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (scope != null) R.string.ask_meeting_title else R.string.ask_title),
                            style = MaterialTheme.typography.titleLarge, color = pal.Slate200, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.ask_experimental), style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = pal.WarningText,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(pal.WarningText.copy(alpha = 0.13f))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                    if (scope != null) Text(
                        scope.title ?: scope.id, style = MaterialTheme.typography.labelMedium, color = pal.Slate400,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (busy) IconButton(onClick = { AskEngine.cancel() }) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.ask_cancel), tint = pal.Slate400)
                }
                // In-meeting: start over and forget this meeting's earlier questions.
                if (scope != null && !busy && (history.isNotEmpty() || turns.isNotEmpty())) IconButton(onClick = {
                    AskEngine.reset(); history = emptyList()
                    scope.dir.let { d -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { AskHistory.clear(d) } }
                }, modifier = Modifier.testTag("ask-meeting-clear")) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.ask_meeting_clear), tint = pal.Slate400)
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                if (history.isNotEmpty()) Text(
                    stringResource(R.string.ask_meeting_earlier), style = MaterialTheme.typography.labelSmall,
                    color = pal.Slate400, modifier = Modifier.padding(top = 10.dp),
                )
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // The earlier questions (saved), then this opening's; the saved ones are only shown.
                (history + turns).forEachIndexed { n, t ->
                    if (n == history.size && history.isNotEmpty()) androidx.compose.material3.HorizontalDivider(
                        Modifier.padding(top = 12.dp), color = pal.Hairline,
                    )
                    Text(
                        t.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        color = pal.Slate200, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                    if (t.answer.isNotBlank()) Text(
                        answerText(t.answer, t.pool, pal.Sky, onOpenAt, withTitle = scope == null,
                            speakerN = { n -> ctx.getString(R.string.speaker_n, n) }),
                        style = MaterialTheme.typography.bodyMedium, color = pal.Slate200,
                    )
                    if (t.done && t.excerpts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.ask_sources), style = MaterialTheme.typography.labelSmall,
                            color = pal.Slate400, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                        t.excerpts.distinctBy { it.entry.id to it.sec / 60 }.forEach { x ->
                            Text(
                                // In-meeting: the time and the passage's first words (the meeting is known; a saved turn has no text).
                                if (scope != null) ReaderProtocol.formatTs(x.sec) + named(x, speakerN = { n -> ctx.getString(R.string.speaker_n, n) }).trim().replace('\n', ' ').let { if (it.isEmpty()) "" else "  $it" }
                                else "${x.entry.title ?: x.entry.id} · ${ReaderProtocol.formatTs(x.sec)}",
                                style = MaterialTheme.typography.labelMedium, color = pal.Sky,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 2.dp)
                                    .clickable { onOpenAt(x.entry, x.at) },
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
                    if (busy) { CircularProgressIndicator(Modifier.size(14.dp), color = pal.Sky, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (phase is AskEngine.Phase.Failed) PrimeMinutesPalette.Red else pal.Slate400)
                }
                Text(
                    stringResource(R.string.ask_disclaimer), style = MaterialTheme.typography.labelSmall,
                    color = pal.Slate400, modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp)).background(pal.PanelSurface)
                    .border(1.dp, pal.Hairline, RoundedCornerShape(12.dp))
                    .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
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
                // 44dp: the sheet's primary action was a 32dp target inside a 44dp row.
                IconButton(onClick = { send() }, enabled = followUp.isNotBlank() && !busy, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.ask_cd), tint = if (followUp.isNotBlank() && !busy) pal.Sky else pal.Slate400, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
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
            styles = TextLinkStyles(style = SpanStyle(color = color, fontSize = 0.7.em, baselineShift = BaselineShift.Superscript)),
            linkInteractionListener = { onOpenAt(e, x.at) },   // the passage's exact start (a tag carries whole seconds)
        )) { append(if (withTitle) "[${(e.title ?: "").take(24)} · ${ReaderProtocol.formatTs(x.sec)}]" else "[${ReaderProtocol.formatTs(x.sec)}]") }
    }
    append(raw.substring(last))
}
