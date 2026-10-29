package studio.primeminutes.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.online.CheckError
import studio.primeminutes.online.FeedEpisode
import studio.primeminutes.online.FeedFetcher
import studio.primeminutes.online.ImportDownloads
import studio.primeminutes.online.Subscription
import studio.primeminutes.online.SubscriptionActions
import studio.primeminutes.online.Subscriptions
import studio.primeminutes.ui.components.GradientButton
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/** Status string of a failed check. */
fun CheckError.label(): Int = when (this) {
    CheckError.GONE -> R.string.sub_err_gone
    CheckError.PAYWALLED -> R.string.sub_err_paywalled
    CheckError.UNPARSABLE -> R.string.sub_err_unparsable
    CheckError.NO_AUDIO -> R.string.sub_err_no_audio
    CheckError.RETRY_LATER -> R.string.sub_err_retry
}

private fun Subscription.errorRes(): Int? = checkError?.let { runCatching { CheckError.valueOf(it).label() }.getOrNull() }

/** Shares "Show — feedUrl" through the system share sheet. */
fun shareShow(ctx: android.content.Context, s: Subscription) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${s.title} — ${s.feedUrl}")
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.sub_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Subscribed shows (full screen, like Settings): the list, then a show's page. Checking is manual (or once when a
 * page opens after 24 h); a new episode is queued like a single-episode import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(onClose: () -> Unit, onOpenEntry: (SessionLibrary.Entry) -> Unit, initialId: String? = null) {
    val ctx = LocalContext.current
    val pal = LocalPrimeMinutesPalette.current
    val scope = rememberCoroutineScope()
    val subs by Subscriptions.all.collectAsState()
    val checking by SubscriptionActions.checking.collectAsState()
    var openId by remember { mutableStateOf(initialId) }
    var pasteOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Subscriptions.load(ctx) } }
    val moved by SubscriptionActions.moved.collectAsState()
    LaunchedEffect(moved) {
        val m = moved ?: return@LaunchedEffect
        if (openId != null) openId = m
        AppSnackbar.show(ctx, R.string.sub_moved); SubscriptionActions.consumeMoved()
    }
    val open = subs.firstOrNull { it.id == openId }
    // The library items of the open channel (subscribed or not: an episode imported from search has its channel too).
    val changes by ImportDownloads.changes.collectAsState()
    var channelEntries by remember { mutableStateOf<List<SessionLibrary.Entry>>(emptyList()) }
    LaunchedEffect(openId, changes, subs) {
        val k = openId
        channelEntries = if (k == null) emptyList() else withContext(Dispatchers.IO) {
            SessionLibrary.list(ctx).filter { studio.primeminutes.core.library.Provenance.groupKey(it.source) == k }
        }
    }
    val channelSrc = channelEntries.firstNotNullOfOrNull { it.source?.takeIf { s -> s.channelTitle != null } } ?: channelEntries.firstOrNull()?.source
    // Opened on a channel (from the library, or a search hit): Back returns there, not to the list of shows.
    fun back() { if (openId != null && openId != initialId) openId = null else onClose() }
    androidx.activity.compose.BackHandler { back() }
    val barScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(barScroll.nestedScrollConnection).testTag("subs-screen"),
        containerColor = pal.Slate800,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(open?.title ?: channelSrc?.channelTitle?.takeIf { openId != null } ?: stringResource(R.string.sub_source), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (openId == null) {
                        IconButton(onClick = { pasteOpen = true }) { Icon(Icons.Filled.Link, contentDescription = stringResource(R.string.sub_paste)) }
                        IconButton(enabled = checking.isEmpty() && subs.any { !it.paused }, onClick = { scope.launch { SubscriptionActions.checkAll(ctx) } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sub_check_all))
                        }
                    } else if (open != null) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sub_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(if (open.paused) R.string.sub_resume else R.string.sub_pause)) },
                                onClick = { menu = false; SubscriptionActions.setPaused(ctx, open.id, !open.paused) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sub_share)) }, onClick = { menu = false; shareShow(ctx, open) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sub_delete)) }, onClick = {
                                // The channel page stays (its library items are kept), now with Subscribe.
                                menu = false; deleteWithUndo(ctx, subs, open)
                            })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pal.Slate800, scrolledContainerColor = pal.Slate800,
                    titleContentColor = pal.Slate200, navigationIconContentColor = pal.Slate200, actionIconContentColor = pal.Slate200,
                ),
                scrollBehavior = barScroll,
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            val k = openId
            if (k == null) ShowList(subs, checking, onOpen = { openId = it })
            else ChannelPage(k, open, channelSrc, channelEntries, k in checking, onOpenEntry)
        }
    }
    if (pasteOpen) PasteFeedDialog(onDismiss = { pasteOpen = false }, onSubscribed = { pasteOpen = false; openId = it })
}

private fun deleteWithUndo(ctx: android.content.Context, subs: List<Subscription>, s: Subscription) {
    val index = subs.indexOfFirst { it.id == s.id }
    Subscriptions.remove(ctx, s.id)
    AppSnackbar.showAction(ctx.getString(R.string.sub_deleted) + " · " + ctx.getString(R.string.sub_delete_note), ctx.getString(R.string.undo)) { undo ->
        if (undo) Subscriptions.restore(ctx, s, index)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShowList(subs: List<Subscription>, checking: Set<String>, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPrimeMinutesPalette.current
    if (subs.isEmpty()) {
        Text(stringResource(R.string.sub_empty), color = pal.Slate400, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(Spacing.lg).testTag("subs-empty"))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(subs, key = { it.id }) { s ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Surface(
                    color = pal.InsetSurface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, pal.Hairline),
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { menu = true }).testTag("sub-row"),
                ) {
                    Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        studio.primeminutes.ui.components.ChannelArtwork(studio.primeminutes.online.ArtworkCache.keyOf(s.id, s.artworkUrl), s.artworkUrl, 40.dp)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge, color = pal.Slate200, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (s.artist.isNotBlank()) Text(s.artist, style = MaterialTheme.typography.bodySmall, color = pal.Slate400, maxLines = 1)
                            val sub = when {
                                s.id in checking -> stringResource(R.string.sub_checking)
                                s.paused -> stringResource(R.string.sub_paused)
                                s.errorRes() != null -> stringResource(s.errorRes()!!)
                                s.lastChecked == 0L -> stringResource(R.string.sub_never_checked)
                                else -> pluralStringResource(R.plurals.podcast_episodes, s.episodes.size, s.episodes.size) + " · " +
                                    stringResource(R.string.sub_last_checked, SessionLibrary.shortDate(ctx, s.lastChecked * 1000))
                            }
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (s.errorRes() != null && !s.paused) PrimeMinutesPalette.Red else pal.Slate400)
                        }
                        val n = s.newEpisodes.size
                        if (n > 0) {
                            Spacer(Modifier.width(Spacing.sm))
                            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                                Text(pluralStringResource(R.plurals.sub_new_count, n, n), color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(if (s.paused) R.string.sub_resume else R.string.sub_pause)) },
                        onClick = { menu = false; SubscriptionActions.setPaused(ctx, s.id, !s.paused) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sub_share)) }, onClick = { menu = false; shareShow(ctx, s) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sub_delete)) }, onClick = { menu = false; deleteWithUndo(ctx, subs, s) })
                }
            }
        }
    }
}

/**
 * A channel's page (it replaced the subscription show page): artwork, title, subscription controls, the feed's
 * episodes not in the library yet ("New in feed", subscribed only), then its episodes in the library. [key]: the
 * channel's group key (its subscription id); [s]: its subscription, null when not subscribed; [src]: the channel
 * snapshot its library items carry.
 */
@Composable
private fun ChannelPage(
    key: String, s: Subscription?, src: studio.primeminutes.core.library.Provenance?, entries: List<SessionLibrary.Entry>,
    checking: Boolean, onOpenEntry: (SessionLibrary.Entry) -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPrimeMinutesPalette.current
    val scope = rememberCoroutineScope()
    val changes by ImportDownloads.changes.collectAsState()
    var index by remember { mutableStateOf<Map<String, SessionLibrary.Entry>>(emptyMap()) }
    var subscribing by remember { mutableStateOf(false) }
    LaunchedEffect(s?.id) { if (s != null) SubscriptionActions.checkIfStale(ctx, s.id) }
    // The "imported" scan: which feed episodes already have a library item (by guid, else audio URL).
    LaunchedEffect(s?.episodes, changes) {
        val eps = s?.episodes ?: run { index = emptyMap(); return@LaunchedEffect }
        index = withContext(Dispatchers.IO) {
            val ix = SubscriptionActions.libraryIndex(ctx)
            eps.mapNotNull { ep -> SubscriptionActions.entryFor(ix, ep)?.let { id -> SessionLibrary.byId(ctx, id)?.let { ep.guid to it } } }.toMap()
        }
    }
    val seen = remember(s?.seenGuids) { s?.seenGuids?.toHashSet() ?: emptySet() }
    val fresh = remember(s?.episodes, index) { s?.episodes?.filter { it.guid !in index } ?: emptyList() }
    val library = remember(entries) { entries.sortedByDescending { it.source?.published?.takeIf { p -> p > 0 }?.times(1000) ?: it.createdAt } }
    val title = s?.title ?: src?.channelTitle ?: stringResource(R.string.podcasts_other)
    val artist = s?.artist?.ifBlank { null } ?: src?.channelArtist
    val artUrl = s?.artworkUrl?.ifBlank { null } ?: src?.artworkUrl
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg).testTag("sub-page"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    studio.primeminutes.ui.components.ChannelArtwork(studio.primeminutes.online.ArtworkCache.keyOf(key, artUrl), artUrl, 72.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        artist?.let { Text(it, color = pal.Slate200, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        val counts = listOfNotNull(
                            stringResource(R.string.channel_in_library, entries.size),
                            s?.let { pluralStringResource(R.plurals.podcast_episodes, it.episodes.size, it.episodes.size) },
                        ).joinToString(" · ")
                        Text(counts, color = pal.Slate400, style = MaterialTheme.typography.bodyMedium)
                        if (s != null) Text(if (s.paused) stringResource(R.string.sub_paused) else if (s.lastChecked == 0L) stringResource(R.string.sub_never_checked)
                            else stringResource(R.string.sub_last_checked, SessionLibrary.shortDate(ctx, s.lastChecked * 1000, withTime = true)),
                            color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                    }
                }
                s?.errorRes()?.let { Text(stringResource(R.string.sub_error, stringResource(it)), color = PrimeMinutesPalette.Red, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.padding(2.dp))
                if (s != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("sub-subscribed")) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.CheckCircle, contentDescription = null, tint = pal.Sky,
                            modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.sub_subscribed), color = pal.Sky, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    GradientButton(stringResource(if (checking) R.string.sub_checking else R.string.sub_check), enabled = !checking,
                        onClick = { scope.launch { SubscriptionActions.check(ctx, s.id) } })
                } else src?.feedUrl?.let { feed ->
                    // Imported from a search without subscribing (or unsubscribed since): subscribe from here.
                    androidx.compose.material3.OutlinedButton(enabled = !subscribing, modifier = Modifier.testTag("sub-subscribe"), onClick = {
                        scope.launch {
                            subscribing = true
                            SubscriptionActions.subscribe(ctx, studio.primeminutes.online.PodcastSeries(title, artist ?: "", feed, 0, artUrl ?: ""))
                                .onFailure { e -> AppSnackbar.show(ctx, (e as? SubscriptionActions.CheckFailed)?.error?.label() ?: R.string.podcast_failed) }
                            subscribing = false
                        }
                    }) { Text(stringResource(if (subscribing) R.string.sub_checking else R.string.sub_subscribe)) }
                }
                Spacer(Modifier.padding(2.dp))
            }
        }
        if (fresh.isNotEmpty()) {
            item(key = "h-new") { ChannelSectionLabel(stringResource(R.string.channel_section_new)) }
            items(fresh, key = { "f-" + it.guid }) { ep -> EpisodeRow(s!!, ep, isNew = ep.guid !in seen, entry = null, onOpenEntry) }
        }
        if (library.isNotEmpty()) {
            item(key = "h-lib") { ChannelSectionLabel(stringResource(R.string.channel_section_library)) }
            items(library, key = { "e-" + it.id }) { e -> LibraryEpisodeRow(e, onOpenEntry) }
        }
    }
}

@Composable
private fun ChannelSectionLabel(text: String) {
    val pal = LocalPrimeMinutesPalette.current
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = pal.Slate200,
        modifier = Modifier.padding(top = Spacing.md, bottom = 2.dp).semantics { heading() })
}

/** An episode of the channel in the library: title, "date · length", its state; tapping opens it. */
@Composable
private fun LibraryEpisodeRow(e: SessionLibrary.Entry, onOpenEntry: (SessionLibrary.Entry) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPrimeMinutesPalette.current
    val dl by ImportDownloads.state.collectAsState()
    val running = dl as? ImportDownloads.State.Running
    val status: String = when {
        e.download?.error != null -> stringResource(R.string.dl_failed_reason, e.download.error)
        e.download != null && running?.entryId == e.id -> stringResource(running.stageRes)
        e.download != null -> stringResource(R.string.dl_waiting)
        e.status == SessionLibrary.Status.DONE -> stringResource(R.string.cd_status_done)
        else -> stringResource(R.string.cd_status_new)
    }
    Surface(color = pal.InsetSurface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, pal.Hairline),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpenEntry(e) }.testTag("channel-entry")) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.title ?: SessionLibrary.defaultTitle(ctx, e.createdAt), style = MaterialTheme.typography.bodyLarge, color = pal.Slate200,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                SessionLibrary.shortDate(ctx, e.source?.published?.takeIf { it > 0 }?.times(1000) ?: e.createdAt),
                e.durationSec.takeIf { it > 0 }?.let { studio.primeminutes.fmtSec(it) },
                status,
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = if (e.download?.error != null) PrimeMinutesPalette.Red else pal.Slate400)
        }
    }
}

@Composable
private fun EpisodeRow(s: Subscription, ep: FeedEpisode, isNew: Boolean, entry: SessionLibrary.Entry?, onOpenEntry: (SessionLibrary.Entry) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPrimeMinutesPalette.current
    val scope = rememberCoroutineScope()
    val dl by ImportDownloads.state.collectAsState()
    Surface(color = pal.InsetSurface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, pal.Hairline), modifier = Modifier.fillMaxWidth().testTag("sub-episode")) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ep.title, style = MaterialTheme.typography.bodyLarge, color = pal.Slate200)
            val meta = listOfNotNull(
                ep.durationSec.takeIf { it > 0 }?.let { studio.primeminutes.online.formatDuration(it.toString()) },
                ep.published.takeIf { it > 0 }?.let { SessionLibrary.shortDate(ctx, it * 1000) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val running = dl as? ImportDownloads.State.Running
                val status: String? = when {
                    entry == null -> if (isNew) stringResource(R.string.sub_new) else null
                    entry.download?.error != null -> stringResource(R.string.dl_failed_reason, entry.download.error)
                    entry.download != null && running?.entryId == entry.id -> stringResource(running.stageRes)
                    entry.download != null -> stringResource(R.string.dl_waiting)
                    entry.status == SessionLibrary.Status.DONE -> stringResource(R.string.sub_imported)
                    else -> stringResource(R.string.sub_queued)
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium,
                        color = if (entry == null && isNew) MaterialTheme.colorScheme.primary else pal.Slate400, modifier = Modifier.weight(1f))
                } ?: Spacer(Modifier.weight(1f))
                if (entry == null) GradientButton(stringResource(R.string.sub_download), onClick = {
                    scope.launch {
                        when (val r = SubscriptionActions.queue(ctx, s.id, ep)) {
                            is SubscriptionActions.Queue.Started -> Unit
                            is SubscriptionActions.Queue.Already -> { AppSnackbar.show(ctx, R.string.sub_already); withContext(Dispatchers.IO) { SessionLibrary.byId(ctx, r.entryId) }?.let(onOpenEntry) }
                            SubscriptionActions.Queue.NoSpace -> AppSnackbar.show(ctx, R.string.sub_no_space)
                        }
                    }
                }) else TextButton(onClick = { onOpenEntry(entry) }) { Text(stringResource(R.string.sub_open)) }
            }
        }
    }
}

/** "Paste RSS link": previews the feed, then subscribes (same write path as a search hit). */
@Composable
fun PasteFeedDialog(onDismiss: () -> Unit, onSubscribed: (String) -> Unit, initial: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var url by remember { mutableStateOf(initial ?: clip.getText()?.text?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var preview by remember { mutableStateOf<FeedFetcher.Result.Ok?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sub_paste)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(url, { url = it; preview = null; error = null }, label = { Text(stringResource(R.string.sub_paste_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("sub-paste-field"))
                if (busy) Text(stringResource(R.string.sub_checking))
                error?.let { Text(stringResource(it), color = PrimeMinutesPalette.Red) }
                preview?.let { p ->
                    Text(p.feed.title, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(p.feed.artist.ifBlank { null }, pluralStringResource(R.plurals.podcast_episodes, p.feed.episodes.size, p.feed.episodes.size)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val p = preview
            TextButton(enabled = !busy && url.isNotBlank(), onClick = {
                scope.launch {
                    if (p == null) {
                        busy = true
                        when (val r = SubscriptionActions.preview(url)) {
                            is FeedFetcher.Result.Ok -> preview = r
                            is FeedFetcher.Result.Failed -> error = r.error.label()
                            else -> error = R.string.sub_err_retry
                        }
                        busy = false
                    } else {
                        val s = SubscriptionActions.subscribe(ctx, if (p.moved) p.finalUrl else url.trim(), p.feed)
                        onSubscribed(s.id)
                    }
                }
            }) { Text(stringResource(if (p == null) R.string.sub_preview else R.string.sub_subscribe)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
