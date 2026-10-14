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
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.reader.ReaderProtocol
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette

/** `[[entryId@sec]]` as the model is asked to write it (and a tolerant `[[id@sec]`/`[id@sec]`). */
private val CITE_RE = Regex("""\[{1,2}([A-Za-z0-9_\-]+)@(\d+)]{1,2}""")

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
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val lang = LocalConfiguration.current.locales[0].toLanguageTag()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val phase by AskEngine.phase.collectAsState()
    val turns by AskEngine.turns.collectAsState()
    val byId = remember(entries) { entries.associateBy { it.id } }
    var followUp by remember { mutableStateOf("") }

    LaunchedEffect(question) { AskEngine.reset(); AskEngine.ask(context, entries, question, lang) }
    DisposableEffect(Unit) { onDispose { AskEngine.release() } }

    val busy = phase is AskEngine.Phase.Retrieving || phase is AskEngine.Phase.Loading || phase is AskEngine.Phase.Answering
    fun send() {
        val q = followUp.trim(); if (q.isEmpty() || busy) return
        followUp = ""; AskEngine.ask(context, entries, q, lang)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = pal.Slate800) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ask_title), style = MaterialTheme.typography.titleLarge,
                    color = pal.Slate200, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.ask_experimental), style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = pal.WarningText,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(pal.WarningText.copy(alpha = 0.13f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                if (busy) IconButton(onClick = { AskEngine.cancel() }) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.ask_cancel), tint = pal.Slate400)
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                turns.forEach { t ->
                    Text(
                        t.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        color = pal.Slate200, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                    if (t.answer.isNotBlank()) Text(
                        answerText(t.answer, byId, pal.Sky, onOpenAt),
                        style = MaterialTheme.typography.bodyMedium, color = pal.Slate200,
                    )
                    if (t.done && t.excerpts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.ask_sources), style = MaterialTheme.typography.labelSmall,
                            color = pal.Slate400, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                        t.excerpts.distinctBy { it.entry.id to it.sec / 60 }.forEach { x ->
                            Text(
                                "${x.entry.title ?: x.entry.id} · ${ReaderProtocol.formatTs(x.sec)}",
                                style = MaterialTheme.typography.labelMedium, color = pal.Sky,
                                modifier = Modifier.padding(vertical = 2.dp)
                                    .clickable { onOpenAt(x.entry, x.sec.toDouble()) },
                            )
                        }
                    }
                }
                val status = when (val p = phase) {
                    AskEngine.Phase.Retrieving -> stringResource(R.string.ask_retrieving)
                    AskEngine.Phase.Loading -> stringResource(R.string.ask_loading)
                    AskEngine.Phase.Answering -> if (turns.lastOrNull()?.answer.isNullOrBlank()) stringResource(R.string.ask_thinking) else null
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
                        if (followUp.isEmpty()) Text(stringResource(R.string.ask_followup_hint), color = pal.Slate400, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { send() }, enabled = followUp.isNotBlank() && !busy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.ask_cd), tint = if (followUp.isNotBlank() && !busy) pal.Sky else pal.Slate400, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The answer with `[[id@sec]]` tags turned into superscript "title · m:ss" links. */
private fun answerText(
    raw: String, byId: Map<String, SessionLibrary.Entry>, color: androidx.compose.ui.graphics.Color,
    onOpenAt: (SessionLibrary.Entry, Double) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in CITE_RE.findAll(raw)) {
        append(raw.substring(last, m.range.first).trimEnd(' '))
        last = m.range.last + 1
        val e = byId[m.groupValues[1]]
        val sec = m.groupValues[2].toIntOrNull() ?: 0
        if (e == null) continue   // an id the model invented: drop the tag silently
        append('⁠')
        withLink(LinkAnnotation.Clickable(
            tag = "cite-${e.id}@$sec",
            styles = TextLinkStyles(style = SpanStyle(color = color, fontSize = 0.7.em, baselineShift = BaselineShift.Superscript)),
            linkInteractionListener = { onOpenAt(e, sec.toDouble()) },
        )) { append("[${(e.title ?: "").take(24)} · ${ReaderProtocol.formatTs(sec)}]") }
    }
    append(raw.substring(last))
}
