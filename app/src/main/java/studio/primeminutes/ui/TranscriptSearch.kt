package studio.primeminutes.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import studio.primeminutes.core.embed.MeetingSearch
import studio.primeminutes.core.reader.ReaderProtocol
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import studio.primeminutes.R
import androidx.compose.ui.graphics.Color
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette

/**
 * Find-in-page over the current transcript: a slim bar (shown above the list when search is active)
 * with a query field, a match counter, and prev/next steppers. Pure local string matching — no
 * model, no network. The host owns the query/match state and drives the list scroll; this is just
 * the control surface.
 */
@Composable
fun TranscriptSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    matchCount: Int,
    matchPos: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Search-by-meaning toggle: shown when the session has a dense index and the model is there. */
    semanticAvailable: Boolean = false,
    semantic: Boolean = false,
    onSemantic: () -> Unit = {},
    /** ≈ is on and this meeting's index is still being built: the counter says so, not "no matches". */
    indexing: Boolean = false,
    /** The results list under the bar (in-meeting search): shown / hidden; null = no toggle. */
    resultsOpen: Boolean? = null,
    onToggleResults: () -> Unit = {},
    /** "Ask this meeting" (the query as the question); null when Ask is not available. */
    onAsk: (() -> Unit)? = null,
) {
    val pal = LocalPrimeMinutesPalette.current
    val semanticCd = stringResource(R.string.search_semantic_cd)
    // Opening the bar means "I want to type": focus it and raise the keyboard.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = pal.Slate400, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (matchCount > 0) onNext() }),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = pal.Slate200),
            cursorBrush = SolidColor(pal.Sky),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.search_transcript_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = pal.Slate400,
                    )
                }
                inner()
            },
        )
        if (semanticAvailable) {
            // "≈" — adds the passages that match by meaning to the match set.
            IconButton(onClick = onSemantic, modifier = Modifier.size(32.dp)) {
                Text(
                    stringResource(R.string.search_semantic_marker),
                    color = if (semantic) pal.Sky else pal.Slate400, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = semanticCd },
                )
            }
        }
        if (query.isNotEmpty()) {
            Text(
                if (matchCount == 0) stringResource(if (indexing) R.string.search_semantic_indexing_short else R.string.search_no_matches)
                else stringResource(R.string.search_match_count, matchPos + 1, matchCount),
                style = MaterialTheme.typography.labelMedium,
                color = pal.Slate400,
            )
            IconButton(onClick = onPrev, enabled = matchCount > 0, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.search_prev), tint = pal.Slate200, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onNext, enabled = matchCount > 0, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.search_next), tint = pal.Slate200, modifier = Modifier.size(20.dp))
            }
        }
        if (resultsOpen != null && query.isNotEmpty()) {
            IconButton(onClick = onToggleResults, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (resultsOpen) Icons.Filled.ExpandLess else Icons.AutoMirrored.Filled.List,
                    stringResource(if (resultsOpen) R.string.search_results_hide else R.string.search_results_show),
                    tint = if (resultsOpen) pal.Sky else pal.Slate400, modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onAsk != null) {
            IconButton(onClick = onAsk, modifier = Modifier.size(32.dp).testTag("search-ask-meeting")) {
                Icon(Icons.Filled.AutoAwesome, stringResource(R.string.ask_meeting_title), tint = pal.Sky, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, stringResource(R.string.search_close), tint = pal.Slate400, modifier = Modifier.size(18.dp))
        }
    }
}

/** Render [text] with every (case-insensitive) occurrence of [query] highlighted. Returns plain
 *  text when [query] is blank, so callers can use it unconditionally. */
fun highlightedTranscript(
    text: String,
    query: String,
    highlight: Color,
    textColor: Color,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    // Match on the original text with ignoreCase (NOT a pre-lowercased copy, whose length can differ
    // from the original for some Unicode and misalign the indices). query.length advances each step,
    // so the loop always terminates.
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(query, i, ignoreCase = true)
            if (hit < 0) { append(text.substring(i)); break }
            append(text.substring(i, hit))
            withStyle(SpanStyle(background = highlight.copy(alpha = 0.35f), color = textColor)) {
                append(text.substring(hit, hit + query.length))
            }
            i = hit + query.length
        }
    }
}

/**
 * The in-meeting search results under the bar (docs/design/in-session-search-ask.md): AI notes, then
 * transcript lines, each with its time; ≈ marks a passage found by meaning only. A tap jumps there.
 * [onAsk] adds the "Ask this meeting" row at the end.
 */
@Composable
fun MeetingSearchResults(
    results: List<MeetingSearch.Result>,
    query: String,
    speakerName: (Int) -> String,
    onPick: (MeetingSearch.Result) -> Unit,
    onAsk: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** ≈ is on and this meeting's index is being built: the list leads with that, keyword hits and all. */
    indexing: Boolean = false,
) {
    val pal = LocalPrimeMinutesPalette.current
    val notes = results.filter { it.kind == MeetingSearch.Kind.NOTE }
    val lines = results.filter { it.kind == MeetingSearch.Kind.LINE }
    LazyColumn(
        modifier.fillMaxWidth().heightIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(pal.PanelSurface).testTag("meeting-search-results"),
    ) {
        fun header(label: Int, n: Int) = item(key = "h$label") {
            Text(
                stringResource(label) + " · $n", style = MaterialTheme.typography.labelSmall, color = pal.Slate400,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            )
        }
        fun rows(rs: List<MeetingSearch.Result>) = rs.forEachIndexed { i, r ->
            item(key = "${r.kind}-${r.line}-${r.timeSec}-$i") {
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(r) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        ReaderProtocol.formatTs(r.timeSec.toInt()), style = MaterialTheme.typography.labelMedium,
                        color = pal.Sky, modifier = Modifier.width(52.dp),
                    )
                    if (r.semanticOnly) Text(
                        stringResource(R.string.search_semantic_marker), color = pal.Sky, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 4.dp),
                    )
                    val who = r.speaker?.let { speakerName(it) + "  " } ?: ""
                    Text(
                        buildAnnotatedString {
                            if (who.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = pal.Slate400)) { append(who) }
                            append(highlightedTranscript(snippetAround(r.text, query), query.trim(), pal.Sky, pal.Slate200))
                        },
                        style = MaterialTheme.typography.bodySmall, color = pal.Slate200, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (indexing) item(key = "indexing") {
            Text(
                stringResource(R.string.search_semantic_indexing), style = MaterialTheme.typography.labelSmall,
                color = pal.Slate400, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (notes.isNotEmpty()) { header(R.string.search_results_notes, notes.size); rows(notes) }
        if (lines.isNotEmpty()) { header(R.string.search_results_transcript, lines.size); rows(lines) }
        if (results.size >= MeetingSearch.MAX_RESULTS) item(key = "more") {
            Text(
                stringResource(R.string.search_results_more, MeetingSearch.MAX_RESULTS), style = MaterialTheme.typography.labelSmall,
                color = pal.Slate400, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (onAsk != null) item(key = "ask") {
            Row(
                Modifier.fillMaxWidth().clickable { onAsk() }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AutoAwesome, null, tint = pal.Sky, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ask_meeting_query, query.trim()), style = MaterialTheme.typography.bodySmall,
                    color = pal.Sky, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A long line cut so the match shows in a two-line row: from a little before the first match. */
internal fun snippetAround(text: String, query: String, before: Int = 24): String {
    val i = if (query.isBlank()) -1 else text.indexOf(query.trim(), ignoreCase = true)
    return if (i <= before) text else "…" + text.substring(i - before)
}
