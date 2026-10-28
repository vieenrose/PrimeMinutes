package studio.primeminutes.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.primeminutes.R
import studio.primeminutes.online.Episode
import studio.primeminutes.online.ImportDownloads
import studio.primeminutes.online.Podcast
import studio.primeminutes.online.PodcastSeries
import studio.primeminutes.ui.components.DownloadStatusBar
import studio.primeminutes.ui.components.GradientButton
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.primeMinutesTextFieldColors
import studio.primeminutes.ui.theme.Spacing

/**
 * Podcast search/browse/download — Android counterpart of the web app's Podcast tab.
 * Search series (iTunes) → pick → list episodes (RSS) → "Transcribe" downloads the enclosure
 * and hands its file:// Uri to [onEpisodeReady], reusing the existing pipeline.
 */
@Composable
fun PodcastPanel(onStarted: () -> Unit, onOpenShow: (String) -> Unit = {}) {
    val pal = LocalPrimeMinutesPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var series by remember { mutableStateOf<List<PodcastSeries>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var selected by remember { mutableStateOf<PodcastSeries?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusRes by remember { mutableIntStateOf(R.string.dl_searching) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    val subs by studio.primeminutes.online.Subscriptions.all.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { studio.primeminutes.online.Subscriptions.load(context) } }
    if (pasteOpen) PasteFeedDialog(onDismiss = { pasteOpen = false }, onSubscribed = { pasteOpen = false; onOpenShow(it) })

    Column(
        // Scrollable: series/episode lists routinely run past one screenful, and without this the
        // rows below the fold were unreachable (the sheet itself doesn't scroll its content).
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val search: () -> Unit = {
            if (query.isNotBlank() && !busy) scope.launch {
                busy = true; error = null; selected = null; episodes = emptyList()
                statusRes = R.string.dl_searching
                series = runCatching { Podcast.searchSeries(query) }
                    .getOrElse { error = it.userMessage(context) ?: context.getString(R.string.podcast_failed); emptyList() }
                busy = false
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.podcast_search_hint)) },
                singleLine = true,
                // The keyboard's action key searches, like the button.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
                colors = primeMinutesTextFieldColors(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.sm))
            GradientButton(
                stringResource(R.string.podcast_search),
                enabled = query.isNotBlank() && !busy,
                onClick = search,
            )
        }
        TextButton(onClick = { pasteOpen = true }, modifier = Modifier.testTag("sub-paste")) {
            Icon(Icons.Filled.Link, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sub_paste))
        }
        if (busy) {
            DownloadStatusBar(statusRes, null)
        }
        error?.let { Text(stringResource(R.string.status_error, it), color = PrimeMinutesPalette.Red) }

        val sel = selected
        if (sel == null) {
            // Empty search results were just an empty column: say why, announced politely so a
            // TalkBack user typing a query hears "no series" land (the library's no-match row).
            if (!busy && series.isEmpty()) Text(
                stringResource(R.string.podcast_no_series), color = pal.Slate400, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            series.forEach { s ->
                RowCard(onClick = {
                    scope.launch {
                        busy = true; error = null; selected = s
                        statusRes = R.string.dl_loading_episodes
                        episodes = runCatching { Podcast.fetchEpisodes(s.feedUrl) }
                            .getOrElse { error = it.userMessage(context) ?: context.getString(R.string.podcast_failed); emptyList() }
                        busy = false
                    }
                }) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, color = pal.Slate200)
                        Text("${s.artist} · ${pluralStringResource(R.plurals.podcast_episodes, s.episodeCount, s.episodeCount)}",
                            style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
                    }
                }
            }
        } else {
            TextButton(onClick = { selected = null; episodes = emptyList() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(sel.title)
            }
            // Subscribe (offline write once the feed is read; "Transcribe" below is unchanged).
            val subId = studio.primeminutes.online.SubscriptionLogic.idOf(sel.feedUrl)
            if (subs.any { it.id == subId }) {
                TextButton(onClick = { onOpenShow(subId) }, modifier = Modifier.testTag("sub-subscribed")) {
                    Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sub_subscribed))
                }
            } else {
                androidx.compose.material3.OutlinedButton(enabled = !busy, modifier = Modifier.testTag("sub-subscribe"), onClick = {
                    scope.launch {
                        busy = true; statusRes = R.string.sub_checking
                        studio.primeminutes.online.SubscriptionActions.subscribe(context, sel)
                            .onFailure { e -> error = (e as? studio.primeminutes.online.SubscriptionActions.CheckFailed)?.error?.let { context.getString(it.label()) } ?: context.getString(R.string.podcast_failed) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.sub_subscribe)) }
            }
            // A feed with no enclosures: say so instead of leaving just the Transcribe button gone.
            if (!busy && episodes.isEmpty()) Text(
                stringResource(R.string.podcast_no_episodes), color = pal.Slate400, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            episodes.forEach { e ->
                RowCard(onClick = null) {
                    Row(
                        Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyMedium, color = pal.Slate200)
                            val date = studio.primeminutes.online.FeedParser.parseDate(e.published).takeIf { it > 0 }
                                ?.let { studio.primeminutes.core.library.SessionLibrary.shortDate(context, it * 1000) }
                            val meta = listOfNotNull(e.durationText.ifBlank { null }, date).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(meta, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
                            }
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        GradientButton(stringResource(R.string.podcast_transcribe), enabled = !busy, onClick = {
                            error = null
                            // The item appears in the library at once and shows its own download.
                            ImportDownloads.submit(context, e.title, studio.primeminutes.core.library.SessionLibrary.PendingDownload.PODCAST, e.audioUrl)
                            onStarted()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun RowCard(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Surface(
        color = pal.InsetSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, pal.Hairline),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) { content() }
}
