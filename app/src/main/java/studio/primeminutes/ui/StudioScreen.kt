package studio.primeminutes.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.shape.CircleShape

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.library.LibraryCollections
import studio.primeminutes.core.library.Provenance
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import studio.primeminutes.core.embed.EmbedHost
import studio.primeminutes.core.embed.EmbedRegistry
import studio.primeminutes.core.embed.Embedder
import studio.primeminutes.core.embed.LibrarySearch
import studio.primeminutes.core.embed.SessionIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import studio.primeminutes.ui.theme.TypeRole
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Shared left/right gutter — header, rows, day labels and banners all align to it. */
private val Gutter = 16.dp

/** Filter over the entry list. Processing is a runtime concept (not an Entry status), so the
 *  filter is limited to the two persistent states plus All. */
private enum class StatusFilter { ALL, NEW, DONE }

/** Where the items came from (their provenance, [studio.primeminutes.core.library.Provenance]): a derived view, never a place. */
private enum class SourceFilter { ALL, MEETINGS, PODCASTS, RECEIVED }

private fun SourceFilter.category() = when (this) {
    SourceFilter.ALL -> null
    SourceFilter.MEETINGS -> studio.primeminutes.core.library.Provenance.Category.MEETING
    SourceFilter.PODCASTS -> studio.primeminutes.core.library.Provenance.Category.PODCAST
    SourceFilter.RECEIVED -> studio.primeminutes.core.library.Provenance.Category.RECEIVED
}

/** A row's single derived state (computed once at the list call site) — one priority definition, so
 *  the color and glyph decoders can't silently drift out of order. Selection is an orthogonal overlay. */
/** [NoSpeech]: processed, but nothing was said — not "Done" (no check mark, no AI notes). */
/** [Downloading] / [DownloadFailed]: a podcast / YouTube item whose audio is still to come. */
private enum class RowStatus { New, Queued, Processing, Done, NoSpeech, Downloading, DownloadFailed }

/** One priority definition for a row's status (color + glyph decode from it). */
private fun rowStatus(e: SessionLibrary.Entry, processingId: String?, queuedIds: Set<String>, foregroundId: String? = null): RowStatus = when {
    e.download != null -> if (e.download.error != null) RowStatus.DownloadFailed else RowStatus.Downloading
    e.id == processingId || e.id == foregroundId -> RowStatus.Processing
    e.status == SessionLibrary.Status.DONE && e.noSpeech -> RowStatus.NoSpeech
    e.status == SessionLibrary.Status.DONE -> RowStatus.Done
    e.id in queuedIds -> RowStatus.Queued
    else -> RowStatus.New
}

/** The Done filter lists the meetings with results: a silent recording is not one of them. */
private fun isDone(e: SessionLibrary.Entry) = e.status == SessionLibrary.Status.DONE && !e.noSpeech

/** Two shared, locale-bound formatters (composition is main-thread, so a single instance is safe)
 *  — avoids re-allocating a DateFormat per row/day-header composition. */
private var TIME_FMT: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
private var DATE_FMT: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

/**
 * The studio home — Prime Minutes 2.0 "session shelf". Sessions grouped by day; status carried by a
 * distinct glyph SHAPE (check = done, clock = queued, waveform bars = processing, equalizer = new)
 * plus color, so state reads on a monochrome e-ink panel too. Long-press enters multi-select for
 * batch delete; a per-row ⋮ surfaces the manage actions without hunting for the long-press. Status
 * filter chips + title search narrow a large library. One accent action pinned at the bottom: Record.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    entries: List<SessionLibrary.Entry>,
    queuedIds: Set<String>,
    processingId: String?,
    processingLabel: String,
    processingFraction: Float,
    isRecording: Boolean,
    recSeconds: Int,
    foregroundRun: Boolean,
    foregroundLabel: String,
    onResumeSession: () -> Unit,
    /** The screen's own run's progress (its row's bar when it processes a library item). */
    foregroundFraction: Float = 0f,
    pendingCount: Int,
    onRecord: () -> Unit,
    onResumeCapture: () -> Unit,
    onOpen: (SessionLibrary.Entry) -> Unit,
    onWatchLive: (SessionLibrary.Entry) -> Unit,
    onProcessNow: (SessionLibrary.Entry) -> Unit,
    /** Continue a stopped run from its saved checkpoint (shown only for entries that have one). */
    onResume: (SessionLibrary.Entry) -> Unit = {},
    onRemoveFromQueue: (SessionLibrary.Entry) -> Unit,
    onStopProcessing: () -> Unit,
    onProcessAll: () -> Unit,
    onRename: (SessionLibrary.Entry, String) -> Unit,
    onShare: (SessionLibrary.Entry) -> Unit,
    /** "Share meeting": the processed meeting as one enriched file (Quick Share / AirDrop / any app). */
    onDelete: (SessionLibrary.Entry) -> Unit,
    onDeleteMany: (List<SessionLibrary.Entry>) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    updateBanner: @Composable () -> Unit = {},
    /** Semantic search: the model is downloaded (dense pass available). */
    searchModelReady: Boolean = false,
    /** Download fraction while the search model is being fetched; null otherwise. */
    searchDownload: Float? = null,
    onDownloadSearchModel: () -> Unit = {},
    /** Meetings whose index is being built right now (shows the "Indexing…" chip). */
    indexingIds: Set<String> = emptySet(),
    /** Open a meeting at a time (a search passage was tapped). */
    onOpenAt: (SessionLibrary.Entry, Double) -> Unit = { e, _ -> onOpen(e) },
    /** Ask (experimental): null when the feature is off; the query becomes the question, asked over the levels given
     *  (the first: the screen's scope, then wider, [LibraryCollections.askLevels]). */
    onAsk: ((String, List<LibraryCollections.AskLevel>) -> Unit)? = null,
    initialQuery: String = "",
    /** DEV (debug builds): typed into the search box one character at a time, for README animations. */
    typeQuery: String = "",
    /** The podcast / YouTube download running now (its item's row shows it). */
    downloadState: studio.primeminutes.online.ImportDownloads.State = studio.primeminutes.online.ImportDownloads.State.Idle,
    /** The library item the screen's own run (not the queue) is processing: its row is live. */
    foregroundId: String? = null,
    /** A download item was tapped: its screen (download state, Resume / Retry / Delete). */
    onOpenDownload: (SessionLibrary.Entry) -> Unit = {},
    onRetryDownload: (SessionLibrary.Entry) -> Unit = {},
    /** The people of the directory with the meetings they are linked in: the "meetings with X" filter. */
    people: List<Pair<studio.primeminutes.core.people.Person, Set<String>>> = emptyList(),
    /** A channel header of the Podcasts view was tapped: its channel page (key: [studio.primeminutes.core.library.Provenance.groupKey]). */
    onOpenChannel: (String) -> Unit = {},
    /** The library's collections (channels are derived, the user's are stored) and their edits. */
    collections: LibraryCollections.Doc = LibraryCollections.Doc(),
    onEditCollections: ((LibraryCollections.Doc) -> LibraryCollections.Doc) -> Unit = {},
) {
    // Times and dates follow the interface language (it can differ from the device's).
    val uiLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    remember(uiLocale) {
        TIME_FMT = DateFormat.getTimeInstance(DateFormat.SHORT, uiLocale)
        DATE_FMT = DateFormat.getDateInstance(DateFormat.MEDIUM, uiLocale)
    }
    val pal = LocalPrimeMinutesPalette.current
    // Tactile parity with iOS (`Haptics.toggle` on the record switch, `Haptics.destructive` on the
    // delete confirms): a recording that starts, and a delete that lands, were visual-only here.
    val haptics = LocalHapticFeedback.current
    var actionsFor by remember { mutableStateOf<SessionLibrary.Entry?>(null) }
    var renameFor by remember { mutableStateOf<SessionLibrary.Entry?>(null) }
    // Collections: the picker (meeting ids to add), a collection's actions / rename / delete, a new one's name, the filter panel.
    var pickerFor by remember { mutableStateOf<List<String>?>(null) }
    var collActionsFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deletingColl by remember { mutableStateOf(false) }
    var newCollFor by remember { mutableStateOf<List<String>?>(null) }
    var filterPanel by remember { mutableStateOf(false) }
    // A single delete is held back while its Undo snackbar shows: the row hides at once and the files
    // go only when the snackbar times out (a killed app in that window keeps the meeting — the safe side).
    val pendingDeletes = remember { mutableStateMapOf<String, Unit>() }
    val deletedMsg = stringResource(R.string.deleted_one)
    val undoLabel = stringResource(R.string.undo)
    fun requestDelete(e: SessionLibrary.Entry) {
        // The swipe can report its dismiss more than once: one snackbar per row, or the second
        // one would delete after the first was undone.
        if (e.id in pendingDeletes) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        pendingDeletes[e.id] = Unit
        AppSnackbar.showAction(String.format(deletedMsg, e.title ?: SessionLibrary.defaultTitle(e.createdAt, uiLocale)), undoLabel) { undone ->
            pendingDeletes.remove(e.id)
            if (!undone) onDelete(e)
        }
    }
    // Many at once: the same held-back delete with one Undo for the batch (it replaced a confirm dialog).
    val deletedManyMsg = stringResource(R.string.deleted_many)
    fun requestDeleteMany(victims: List<SessionLibrary.Entry>) {
        val fresh = victims.filter { it.id !in pendingDeletes }
        if (fresh.isEmpty()) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        fresh.forEach { pendingDeletes[it.id] = Unit }
        AppSnackbar.showAction(String.format(deletedManyMsg, fresh.size), undoLabel) { undone ->
            fresh.forEach { pendingDeletes.remove(it.id) }
            if (!undone) onDeleteMany(fresh)
        }
    }
    // A meeting held back for Undo no longer counts as waiting to be processed (back on Undo).
    @Suppress("NAME_SHADOWING")
    val pendingCount = (pendingCount - entries.count { it.id in pendingDeletes && it.status == SessionLibrary.Status.RECORDED && it.wavFile.exists() && it.id !in queuedIds }).coerceAtLeast(0)
    @Suppress("NAME_SHADOWING")
    val entries = if (pendingDeletes.isEmpty()) entries else entries.filter { it.id !in pendingDeletes }
    // A row's tap: a live run opens its live view (the queue's, or the screen's own run), a download item its
    // screen, a downloaded item starts processing, a saved meeting opens; the rest offer their actions.
    fun onRowTap(e: SessionLibrary.Entry, status: RowStatus) {
        when {
            status == RowStatus.Processing -> if (e.id == foregroundId) onResumeSession() else onWatchLive(e)
            status == RowStatus.Downloading || status == RowStatus.DownloadFailed -> onOpenDownload(e)
            status == RowStatus.Done || status == RowStatus.NoSpeech -> onOpen(e)
            e.sourceOnly && e.id !in queuedIds -> onProcessNow(e)
            else -> actionsFor = e
        }
    }
    // Feed live text ONLY to the live rows — otherwise every tick changes the args of every visible row
    // and defeats skipping (a full e-ink refresh per tick).
    @Composable fun rowLabel(e: SessionLibrary.Entry, status: RowStatus): String = when (status) {
        RowStatus.Processing -> if (e.id == foregroundId) foregroundLabel else processingLabel
        RowStatus.Downloading, RowStatus.DownloadFailed -> downloadLine(e, downloadState)
        else -> if (e.sourceOnly) stringResource(R.string.dl_downloaded_ready) else ""
    }
    fun rowFraction(e: SessionLibrary.Entry, status: RowStatus): Float = when (status) {
        RowStatus.Processing -> if (e.id == foregroundId) foregroundFraction else processingFraction
        RowStatus.Downloading -> (downloadState as? studio.primeminutes.online.ImportDownloads.State.Running)
            ?.takeIf { it.entryId == e.id && !it.paused }?.progress ?: 0f
        else -> 0f
    }
    var query by remember { mutableStateOf(initialQuery) }
    var statusFilter by remember { mutableStateOf(StatusFilter.ALL) }
    var sourceFilter by remember { mutableStateOf(SourceFilter.ALL) }
    /** The person whose meetings the list is narrowed to (null: everyone). */
    var personFilter by remember { mutableStateOf<String?>(null) }
    val personIds = people.firstOrNull { it.first.id == personFilter }?.second
    LaunchedEffect(people) { if (personFilter != null && people.none { it.first.id == personFilter }) personFilter = null }
    /** The collection page open (its key, [LibraryCollections.keyOf]); null: the library. Part of the scope. */
    var openKey by remember { mutableStateOf<String?>(null) }
    // Scope = the active filters (source, collection, person, status), tied to the screen: Back restores the previous one.
    val scopeBack = remember { mutableStateListOf<ScopeSnap>() }
    fun restoreScope(sn: ScopeSnap) { openKey = sn.openKey; sourceFilter = sn.source; statusFilter = sn.status; personFilter = sn.person }
    fun clearScope() { scopeBack.clear(); restoreScope(ScopeSnap(null, SourceFilter.ALL, StatusFilter.ALL, null)) }
    val scoped = openKey != null || sourceFilter != SourceFilter.ALL || statusFilter != StatusFilter.ALL || personFilter != null
    fun openCollection(key: String) {
        scopeBack.add(ScopeSnap(openKey, sourceFilter, statusFilter, personFilter))
        openKey = key; sourceFilter = SourceFilter.ALL; statusFilter = StatusFilter.ALL; query = ""
        // Opening a channel clears its "N new".
        if (LibraryCollections.kindOf(key) == LibraryCollections.Kind.CHANNEL) {
            val now = System.currentTimeMillis(); onEditCollections { LibraryCollections.markSeen(it, key, now) }
        }
    }
    // A collection deleted while its page is open: back to the library.
    LaunchedEffect(collections, openKey) {
        val k = openKey ?: return@LaunchedEffect
        if (LibraryCollections.kindOf(k) == LibraryCollections.Kind.USER && collections.collections.none { "uc:${it.id}" == k }) clearScope()
    }

    // Multi-select for batch delete. A Set (not a List) so membership/removal is O(1) per row — after
    // Select-all that matters. Value is Unit; keys are entry ids.
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Unit>() }
    fun exitSelection() { selectionMode = false; selected.clear() }
    BackHandler(selectionMode) { exitSelection() }
    // Back with a library search typed clears it first (as in a session); only a back with no
    // query leaves the app. The keyboard, when up, takes the first back itself.
    BackHandler(!selectionMode && query.isNotEmpty()) { query = "" }
    BackHandler(!selectionMode && query.isEmpty() && scoped) {
        if (scopeBack.isNotEmpty()) restoreScope(scopeBack.removeAt(scopeBack.lastIndex)) else clearScope()
    }

    val context = LocalContext.current
    LaunchedEffect(typeQuery) {
        if (typeQuery.isBlank()) return@LaunchedEffect
        if (searchModelReady) EmbedHost.preload(context, 1)   // what focusing the box does
        kotlinx.coroutines.delay(2_500)
        for (i in 1..typeQuery.length) { query = typeQuery.take(i); kotlinx.coroutines.delay(220) }
    }
    // Hybrid search: the lexical pass answers at once (titles, summaries, notes); after a short idle
    // the query is embedded and the dense pass is fused in. Entries change → refresh the text cache.
    var hits by remember { mutableStateOf<List<LibrarySearch.Hit>>(emptyList()) }
    // The query whose dense pass is still running: until it lands, an empty list means "searching",
    // not "no match" (the first query of a session also waits for the model to load).
    var densePending by remember { mutableStateOf<String?>(null) }
    // The meta line's speaker dots read the search cache: re-read once a refresh has filled it.
    var speakerTick by remember { mutableStateOf(0) }
    LaunchedEffect(entries) { LibrarySearch.refresh(context, entries); speakerTick++ }
    val speakerIdsOf = remember(entries, speakerTick) {
        entries.associate { e -> e.id to (LibrarySearch.speakers(e)?.second?.sorted() ?: emptyList()) }
    }
    LaunchedEffect(query, entries, searchModelReady) {
        val q = query.trim()
        if (q.isBlank()) { hits = emptyList(); densePending = null; return@LaunchedEffect }
        val lex = LibrarySearch.lexical(context, entries, q)
        hits = lex
        if (!searchModelReady) { densePending = null; return@LaunchedEffect }
        densePending = q
        delay(300)
        val qv = try { EmbedHost.use(context, 1) { it.embed(Embedder.Prompts.query(q)) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        if (qv != null) hits = LibrarySearch.fuse(lex, LibrarySearch.dense(context, entries, qv))
        // Cleared only when the pass ends: a cancelled pass leaves it to the effect that replaces it
        // (relaunched for the same query when the entries change, it flashed "no match" for a frame).
        densePending = null
    }
    val want = when (statusFilter) {   // map the filter to a target status once, not per entry
        StatusFilter.ALL -> null
        StatusFilter.NEW -> SessionLibrary.Status.RECORDED
        StatusFilter.DONE -> SessionLibrary.Status.DONE
    }
    // A stray space is not a query: blank-after-trim falls through to the full list (like
    // iOS) instead of a no-match screen from zero lexical hits.
    val searching = query.trim().isNotEmpty()
    val wantSource = sourceFilter.category()
    fun inSource(e: SessionLibrary.Entry) = wantSource == null || e.category == wantSource
    fun inCollection(e: SessionLibrary.Entry) = openKey == null || LibraryCollections.keyOf(e, collections) == openKey
    fun wanted(e: SessionLibrary.Entry) = (personIds == null || e.id in personIds) && inSource(e) && inCollection(e) &&
        when (want) { null -> true; SessionLibrary.Status.DONE -> isDone(e); else -> e.status == want }
    // Search runs over the whole library; the scope keeps its own hits, the rest is counted ("n more in All").
    val shownHits = remember(hits, statusFilter, sourceFilter, personIds, openKey, collections) { hits.filter { wanted(it.entry) } }
    val hitsElsewhere = if (scoped) hits.size - shownHits.size else 0
    val shown = remember(entries, searching, shownHits, statusFilter, sourceFilter, personIds, openKey, collections) {
        if (searching) shownHits.map { it.entry }
        else entries.filter(::wanted).let { l ->
            // A channel page lists its episodes newest published first.
            if (openKey?.let(LibraryCollections::kindOf) == LibraryCollections.Kind.CHANNEL)
                l.sortedByDescending { it.source?.published?.takeIf { p -> p > 0 }?.times(1000) ?: it.createdAt } else l
        }
    }
    // The library's rows: items outside any collection mixed with the collection rows by their latest item
    // (the Podcasts filter: channel rows only). Grouped ONCE by day (LinkedHashMap keeps the order) instead of
    // allocating a Calendar per row on every recomposition inside the LazyColumn lambda.
    val rows = remember(entries, collections, statusFilter, sourceFilter, personIds, openKey) {
        if (openKey != null) emptyList()
        else LibraryCollections.rows(entries, collections, podcastsOnly = sourceFilter == SourceFilter.PODCASTS, keep = ::wanted)
    }
    val groups = remember(rows) { rows.groupBy { dayStart(it.date) } }
    val visibleItems = if (searching || openKey != null) shown else rows.mapNotNull { (it as? LibraryCollections.Row.Item)?.entry }
    val openColl = remember(openKey, entries, collections) {
        openKey?.let { k -> LibraryCollections.build(entries, collections).firstOrNull { it.key == k } }
    }
    // A suggestion card (≥ 3 meetings with the same title once numbers and dates are removed), on the plain library only.
    val suggestion = remember(entries, collections) { LibraryCollections.suggestion(entries, collections) }
    fun askLevels() = LibraryCollections.askLevels(entries, collections, openKey, sourceFilter.category())
    fun ask(q: String) { onAsk?.invoke(q, askLevels()) }
    // Source counts over the whole library; a source chip shows only when there are two sources or more.
    val sourceCounts = remember(entries) { entries.groupingBy { it.category }.eachCount() }
    val showSources = sourceCounts.size >= 2
    LaunchedEffect(sourceCounts) {
        val c = sourceFilter.category()
        if (c != null && (sourceCounts[c] ?: 0) == 0) sourceFilter = SourceFilter.ALL
    }
    val inSourceEntries = remember(entries, sourceFilter) { entries.filter(::inSource) }
    // Day anchors: remember keyed on an hourly bucket so StudioScreen's per-progress-tick
    // recompositions don't re-allocate two Calendars each, while the anchors still self-correct
    // across midnight (the key rolls over ~hourly). Yesterday via CALENDAR (a day isn't 86.4e6 ms — DST).
    val dayTick = System.currentTimeMillis() / 3_600_000L
    val today = remember(dayTick) { dayStart(System.currentTimeMillis()) }
    val yesterday = remember(dayTick) {
        Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
    }
    // The status chips count within the chosen source (source × status narrow together).
    val doneCount = remember(inSourceEntries) { inSourceEntries.count(::isDone) }
    val newCount = remember(inSourceEntries) { inSourceEntries.count { it.status == SessionLibrary.Status.RECORDED } }
    // The active filter's chip stops rendering once its count hits 0 (e.g. the last New session was
    // processed, or all Done were batch-deleted) — reset to ALL so we don't strand the list on an
    // empty 'no match' screen with no way back except guessing to tap All.
    LaunchedEffect(newCount, doneCount) {
        if ((statusFilter == StatusFilter.NEW && newCount == 0) || (statusFilter == StatusFilter.DONE && doneCount == 0)) {
            statusFilter = StatusFilter.ALL
        }
    }

    @Composable fun libraryRow(e: SessionLibrary.Entry) {
        val status = rowStatus(e, processingId, queuedIds, foregroundId)
        SwipeToDelete(enabled = !selectionMode && status != RowStatus.Processing, onDelete = { requestDelete(e) }) {
            SessionRow(
                entry = e, status = status,
                // Feed live progress ONLY to the processing row — otherwise every tick
                // changes the args of every visible row and defeats skipping (a full
                // e-ink refresh per tick).
                processingLabel = rowLabel(e, status),
                processingFraction = rowFraction(e, status),
                selectionMode = selectionMode,
                selected = e.id in selected,
                onClick = {
                    if (selectionMode) {
                        if (e.id in selected) selected.remove(e.id) else selected[e.id] = Unit
                    } else onRowTap(e, status)
                },
                // Redesign: no per-row ⋮ — long-press opens the row's actions (select is one of them).
                onLongClick = { if (!selectionMode) actionsFor = e },
                onManage = { actionsFor = e },
                onRename = { renameFor = e },
                onSelect = { selectionMode = true; selected[e.id] = Unit },
                onDeleteAction = if (status != RowStatus.Processing) ({ requestDelete(e) }) else null,
                speakers = speakerIdsOf[e.id].orEmpty(),
            )
        }
    }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var queueOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(pal.Slate900Grad).statusBarsPadding().navigationBarsPadding()) {
    Column(
        Modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
    ) {
        if (selectionMode) {
            SelectionHeader(
                count = selected.size,
                onClose = { exitSelection() },
                // Podcast episodes stay in their channel: only meetings go into a collection.
                onAddTo = entries.filter { it.id in selected && it.category != Provenance.Category.PODCAST }.map { it.id }
                    .takeIf { it.isNotEmpty() }?.let { ids -> { pickerFor = ids } },
                // The rows on screen only: the library's collection rows keep their meetings out of a select-all.
                onSelectAll = { selected.clear(); visibleItems.forEach { selected[it.id] = Unit } },
                onDelete = {
                    if (selected.isNotEmpty()) {
                        val victims = entries.filter { it.id in selected }
                        exitSelection()
                        requestDeleteMany(victims)
                    }
                },
            )
        } else {
            // M3 top app bar: brand, Add, Settings; it slides away as the list scrolls up (enterAlways).
            // Redesign: a large 「會議」 title; ＋ imports (file, podcast, YouTube); ⋮ holds Select and Settings.
            var libMenu by remember { mutableStateOf(false) }
            if (openKey != null) CollectionHeader(
                key = openKey!!, coll = openColl, name = openColl?.name ?: openKey?.let { LibraryCollections.nameOf(it, entries, collections) },
                onBack = { if (scopeBack.isNotEmpty()) restoreScope(scopeBack.removeAt(scopeBack.lastIndex)) else clearScope() },
                onAsk = if (onAsk != null) ({ ask("") }) else null,
                onRecordInto = { LibraryCollections.target = openKey!!.removePrefix("uc:"); onRecord() },
                onImportInto = { LibraryCollections.target = openKey!!.removePrefix("uc:"); onImport() },
                onRename = { collActionsFor = openKey; renaming = true },
                onDelete = { collActionsFor = openKey; deletingColl = true },
                onChannelInfo = { onOpenChannel(openKey!!.removePrefix("ch:")) },
            ) else TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.library_title), maxLines = 1,
                        style = TypeRole.largeTitle(), color = pal.Slate200,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                actions = {
                    IconButton(onClick = { LibraryCollections.target = null; onImport() }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_audio), tint = pal.Slate200) }
                    Box {
                        IconButton(onClick = { libMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options), tint = pal.Slate200) }
                        androidx.compose.material3.DropdownMenu(expanded = libMenu, onDismissRequest = { libMenu = false }) {
                            if (shown.isNotEmpty()) androidx.compose.material3.DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)) },
                                text = { Text(stringResource(R.string.menu_select)) },
                                onClick = { libMenu = false; selectionMode = true },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) },
                                text = { Text(stringResource(R.string.cd_settings)) },
                                onClick = { libMenu = false; onSettings() },
                            )
                        }
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                scrollBehavior = topBarScroll,
            )

            // Search over the library (lexical at once, by meaning after a short pause): an M3 docked
            // search bar that never expands — the results are the list below, as before.
            DockedSearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query, onQueryChange = { query = it },
                        // The keyboard's action asks the query when Ask is on (it was Send before).
                        onSearch = { if (it.isNotBlank()) ask(it) },
                        expanded = false, onExpandedChange = {},
                        placeholder = {
                            Text(when {
                                openKey == null && !scoped -> stringResource(R.string.search_all_hint, entries.size)
                                openKey == null -> stringResource(R.string.studio_search_hint)
                                LibraryCollections.kindOf(openKey!!) == LibraryCollections.Kind.CHANNEL -> stringResource(R.string.search_in_channel)
                                else -> stringResource(R.string.search_in_collection)
                            }, maxLines = 1)
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear_search))
                                }
                                if (indexingIds.isNotEmpty()) Chip(stringResource(R.string.studio_indexing), pal.Slate400)
                                if (onAsk != null) {
                                    // Ask, labelled (an unlabelled paper plane was missed): with a query it asks it, empty
                                    // it opens the Ask sheet on its own question field.
                                    Spacer(Modifier.width(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clip(CircleShape)
                                            .clickable { ask(query) }
                                            .semantics(mergeDescendants = true) { role = Role.Button }
                                            .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Icon(Icons.Filled.QuestionAnswer, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(Spacing.xs))
                                        Text(stringResource(R.string.ask_title), color = pal.Sky, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                    Spacer(Modifier.width(Spacing.sm))
                                }
                            }
                        },
                        modifier = Modifier.onFocusChanged { f ->
                            // Typing is imminent: load the embedder now so the first dense pass is not
                            // delayed by the model load.
                            if (f.isFocused && searchModelReady) EmbedHost.preload(context, 1)
                        },
                    )
                },
                expanded = false, onExpandedChange = {},
                colors = SearchBarDefaults.colors(containerColor = pal.Fill),
                shape = CircleShape,
                tonalElevation = 0.dp, shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
            ) {}
            // The semantic-search model download lives in Settings > Search (redesign).

            // The scope as one pill (hidden at All): what search and Ask act on; tap: the filters, ✕: back to All.
            if (scoped) {
                val sourceName = when (sourceFilter) {
                    SourceFilter.ALL -> null
                    SourceFilter.MEETINGS -> stringResource(R.string.ask_scope_meetings)
                    SourceFilter.PODCASTS -> stringResource(R.string.ask_scope_podcasts)
                    SourceFilter.RECEIVED -> stringResource(R.string.ask_scope_received)
                }
                val statusName = when (statusFilter) {
                    StatusFilter.ALL -> null
                    StatusFilter.NEW -> stringResource(R.string.pill_new)
                    StatusFilter.DONE -> stringResource(R.string.cd_status_done)
                }
                val collName = openKey?.let { openColl?.name ?: LibraryCollections.nameOf(it, entries, collections) ?: stringResource(R.string.podcasts_other) }
                val collCount = openColl?.let { c -> stringResource(if (c.kind == LibraryCollections.Kind.CHANNEL) R.string.coll_count_episodes else R.string.coll_count_meetings, c.items.size) }
                val parts = listOfNotNull(collName, sourceName, people.firstOrNull { it.first.id == personFilter }?.first?.name, statusName)
                ScopePill(parts.joinToString(" · ") + (collCount?.let { " · $it" } ?: ""), onOpen = { filterPanel = true }, onClear = { clearScope() })
            }

            // An available app update — shown here on the home screen (where the user lands), not
            // only inside an open session. Renders nothing when there's no update / it's dismissed.
            Box(Modifier.padding(horizontal = Gutter)) { updateBanner() }

            // The first-use model download, with or without meetings in the list (it used to show only
            // on the empty home, so "Download now" over a full library looked like it did nothing).
            val prefetch by studio.primeminutes.core.models.ModelPrefetch.status.collectAsState()
            prefetch?.let { st ->
                studio.primeminutes.ui.components.ModelDownloadCard(
                    st, onCancel = { studio.primeminutes.core.models.ModelPrefetch.cancel() },
                    modifier = Modifier.padding(start = Gutter, end = Gutter, top = Spacing.sm),
                )
            }

            // Status filter chips — pairs with multi-select for "clear all junk" (New → Select all → Delete).
            // A chip that would list exactly what "All" lists is noise, so it is left out; with
            // nothing left to choose between, so is the whole row.
            // One row, source chips then status chips (the study's merge: a third chip row was too much with
            // People below). All clears both; a source chip picks a source (tap again: all sources); New / Done
            // narrow within it, toggled.
            val showNew = newCount > 0 && (newCount < inSourceEntries.size || statusFilter == StatusFilter.NEW)
            val showDone = doneCount > 0 && (doneCount < inSourceEntries.size || statusFilter == StatusFilter.DONE)
            if (openKey == null && (showSources || showNew || showDone)) {
                Row(
                    // Scrolls sideways at large font scales instead of squeezing the last chip to a column.
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = Spacing.sm).testTag("source-filter"),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(stringResource(R.string.filter_all, entries.size), sourceFilter == SourceFilter.ALL && statusFilter == StatusFilter.ALL) {
                        sourceFilter = SourceFilter.ALL; statusFilter = StatusFilter.ALL
                    }
                    if (showSources) {
                        for ((f, res) in listOf(SourceFilter.MEETINGS to R.string.filter_meetings, SourceFilter.PODCASTS to R.string.filter_podcasts,
                                SourceFilter.RECEIVED to R.string.filter_received)) {
                            val n = sourceCounts[f.category()!!] ?: 0
                            if (n > 0) FilterChip(stringResource(res, n), sourceFilter == f) { sourceFilter = if (sourceFilter == f) SourceFilter.ALL else f }
                        }
                    }
                    if (showSources && (showNew || showDone)) Box(Modifier.width(1.dp).height(24.dp).background(pal.Hairline))
                    if (showNew) FilterChip(stringResource(R.string.filter_new, newCount), statusFilter == StatusFilter.NEW) {
                        statusFilter = if (statusFilter == StatusFilter.NEW) StatusFilter.ALL else StatusFilter.NEW
                    }
                    if (showDone) FilterChip(stringResource(R.string.filter_done, doneCount), statusFilter == StatusFilter.DONE) {
                        statusFilter = if (statusFilter == StatusFilter.DONE) StatusFilter.ALL else StatusFilter.DONE
                    }
                }
            }

            // "Meetings with X": one chip per person of the directory, the most present first.
            if (openKey == null && people.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    Modifier.fillMaxWidth().padding(top = Spacing.sm).testTag("people-filter"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item { FilterChip(stringResource(R.string.people_filter_all), personFilter == null) { personFilter = null } }
                    items(people, key = { it.first.id }) { (p, ids) ->
                        FilterChip("${p.name} · ${ids.size}", personFilter == p.id) { personFilter = if (personFilter == p.id) null else p.id }
                    }
                }
            }

            // The queue as one slim bar (count, current step, progress); tap: the full queue. The screen's
            // own run over something not in the library (a fresh import) shows here too and returns to it.
            val queueCount = queuedIds.size + (if (processingId != null && processingId !in queuedIds) 1 else 0)
            val ownRun = foregroundRun && !isRecording && entries.none { it.id == foregroundId }
            if (queueCount > 0 || ownRun) {
                QueueBar(
                    title = if (queueCount > 0) stringResource(R.string.queue_title) + " · " + stringResource(R.string.queue_bar_count, queueCount)
                        else stringResource(R.string.studio_processing_banner, foregroundLabel.ifBlank { stringResource(R.string.status_processing) }),
                    step = if (queueCount > 0) processingLabel else "",
                    fraction = if (queueCount > 0) processingFraction else foregroundFraction,
                    active = processingId != null || ownRun,
                    onClick = { if (queueCount > 0) queueOpen = true else onResumeSession() },
                )
            }
        }

        if (shown.isEmpty() && !isRecording) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val firstRun = query.isBlank() && !scoped
                if (firstRun) {
                    // First run: a clear invitation, not a faint glyph in a void. Record is the big
                    // button below; importing a file is the other way in, so it gets a button here.
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_brand), contentDescription = null,
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        stringResource(R.string.studio_empty_title),
                        color = pal.Slate200, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                Text(
                    when {
                        firstRun -> stringResource(R.string.library_empty)
                        densePending != null -> stringResource(R.string.studio_searching)
                        searching && scoped -> stringResource(R.string.scope_none_here, query.trim())
                        else -> stringResource(R.string.studio_no_match)
                    },
                    color = pal.Slate400, style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                // A no-match from a search + filters was a dead end: the only way out was clearing the
                // field by hand and finding the filter chips behind the empty list.
                val filtered = query.trim().isNotEmpty() || statusFilter != StatusFilter.ALL || sourceFilter != SourceFilter.ALL || personFilter != null
                if (!firstRun && densePending == null && searching && scoped) {
                    // Nothing in the scope: searching all of the library is the way on.
                    if (hitsElsewhere > 0) Text(stringResource(R.string.scope_elsewhere, hitsElsewhere).removeSuffix(" ›"),
                        color = pal.Slate400, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.sm))
                    Spacer(Modifier.height(Spacing.xl))
                    Button(onClick = { val q = query; clearScope(); query = q }, modifier = Modifier.testTag("scope-search-all")) {
                        Text(stringResource(R.string.scope_search_all))
                    }
                } else if (openKey != null && !firstRun && densePending == null && !filtered) {
                    Text(stringResource(R.string.coll_empty), color = pal.Slate400, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                } else if (!firstRun && densePending == null && filtered) {
                    Spacer(Modifier.height(Spacing.xl))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { query = ""; statusFilter = StatusFilter.ALL; sourceFilter = SourceFilter.ALL; personFilter = null },
                        border = androidx.compose.foundation.BorderStroke(1.dp, pal.Slate600),
                        modifier = Modifier.testTag("studio-clear-filters"),
                    ) { Text(stringResource(R.string.studio_clear_filters), color = pal.Sky) }
                }
                if (firstRun) {
                    // The first recording downloads the speech + summary models: say how much before
                    // it starts, so nobody burns a mobile plan without knowing.
                    var pendingBytes by remember { mutableStateOf(0L) }
                    val prefetch by studio.primeminutes.core.models.ModelPrefetch.progress.collectAsState()
                    LaunchedEffect(prefetch == null) {
                        pendingBytes = withContext(Dispatchers.IO) { studio.primeminutes.core.models.ModelPrefetch.pending(context).total }
                    }
                    // While it downloads, the card at the top says how far (with Cancel).
                    if (prefetch == null && pendingBytes > 0) {
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            stringResource(R.string.first_run_download_notice, studio.primeminutes.core.models.SizeFormat.bytes(pendingBytes)),
                            color = pal.Slate400, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(Spacing.xl))
                    androidx.compose.material3.OutlinedButton(
                        onClick = onImport,
                        border = androidx.compose.foundation.BorderStroke(1.dp, pal.Slate600),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.studio_empty_import), color = pal.Sky)
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                // Hairline-separated rows (no cards); the bottom pad clears the record FAB.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 120.dp),
            ) {
                if (searching && hitsElsewhere > 0) item(key = "elsewhere", contentType = "elsewhere") {
                    Text(
                        stringResource(R.string.scope_elsewhere, hitsElsewhere), color = pal.Sky, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { val q = query; clearScope(); query = q }
                            .heightIn(min = 44.dp).padding(horizontal = Gutter, vertical = Spacing.sm).testTag("scope-elsewhere"),
                    )
                }
                if (searching) items(shownHits, key = { it.entry.id }, contentType = { "hit" }) { h ->
                    val e = h.entry
                    val status = rowStatus(e, processingId, queuedIds, foregroundId)
                    Column {
                        SwipeToDelete(enabled = !selectionMode && status != RowStatus.Processing, onDelete = { requestDelete(e) }) {
                        SessionRow(
                            entry = e, status = status,
                            processingLabel = rowLabel(e, status),
                            processingFraction = rowFraction(e, status),
                            selectionMode = selectionMode, selected = e.id in selected,
                            onClick = {
                                if (selectionMode) {
                                    if (e.id in selected) selected.remove(e.id) else selected[e.id] = Unit
                                } else onRowTap(e, status)
                            },
                            onLongClick = { if (!selectionMode) actionsFor = e },
                            onManage = { actionsFor = e },
                            onRename = { renameFor = e },
                            onSelect = { selectionMode = true; selected[e.id] = Unit },
                            onDeleteAction = if (status != RowStatus.Processing) ({ requestDelete(e) }) else null,
                            speakers = speakerIdsOf[e.id].orEmpty(),
                            // Under All a hit says which collection it sits in (a podcast already names its channel).
                            collection = if (openKey == null) LibraryCollections.keyOf(e, collections)?.takeIf { it.startsWith("uc:") }
                                ?.let { LibraryCollections.nameOf(it, entries, collections) } else null,
                        )
                        }
                        if (status == RowStatus.Done) h.passages.forEach { p ->
                            PassageRow(p, LibrarySearch.speakers(e)) {
                                if (p.kind == SessionIndex.Kind.MEETING) onOpen(e) else onOpenAt(e, p.timeSec)
                            }
                        }
                    }
                } else if (openKey != null) items(shown, key = { it.id }, contentType = { "row" }) { e -> libraryRow(e) }
                else {
                    if (!scoped) suggestion?.let { sg ->
                        item(key = "suggest-${sg.key}", contentType = "suggest") {
                            SuggestionCard(sg,
                                onCreate = { val id = LibraryCollections.newId(); onEditCollections { LibraryCollections.create(it, sg.name, sg.ids, id, System.currentTimeMillis()) } },
                                onSkip = { onEditCollections { LibraryCollections.dismiss(it, sg.key) } })
                        }
                    }
                    groups.forEach { (day, list) ->
                    stickyHeader(key = "day-$day", contentType = "header") {
                        Text(
                            dayLabel(day, today, yesterday),
                            style = TypeRole.groupHeader(), color = pal.Ink3,
                            modifier = Modifier.fillMaxWidth().background(pal.Slate900Grad).padding(start = Gutter, top = Spacing.lg, bottom = Spacing.xs)
                                .semantics { heading() },
                        )
                    }
                    items(list, key = { r -> when (r) { is LibraryCollections.Row.Item -> r.entry.id; is LibraryCollections.Row.Coll -> r.collection.key } },
                        contentType = { r -> if (r is LibraryCollections.Row.Coll) "coll" else "row" }) { r ->
                        when (r) {
                            is LibraryCollections.Row.Item -> libraryRow(r.entry)
                            is LibraryCollections.Row.Coll -> {
                                val c = r.collection
                                val live = c.items.filter { it.id == processingId || it.id == foregroundId || it.id in queuedIds }
                                val frac = when {
                                    c.items.any { it.id == processingId } -> processingFraction
                                    c.items.any { it.id == foregroundId } -> foregroundFraction
                                    else -> 0f
                                }
                                CollectionRow(c, newCount = c.newCount(collections), processing = live.size, fraction = frac,
                                    onClick = { if (!selectionMode) openCollection(c.key) },
                                    onLongClick = { if (!selectionMode && c.kind == LibraryCollections.Kind.USER) collActionsFor = c.key })
                            }
                        }
                    }
                    }
                }
            }
        }

    }
        // Redesign: the record button is a red round FAB on a fade (it also returns to a running capture).
        if (!selectionMode) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(pal.Slate900.copy(alpha = 0f), pal.Slate900, pal.Slate900)))
                    .padding(top = Spacing.xl, bottom = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pendingCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.heightIn(min = 48.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onProcessAll).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    ) {
                        Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.source_process_all, pendingCount), color = pal.Sky, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val recLabel = stringResource(if (isRecording) R.string.studio_return_capture else R.string.record_fab)
                androidx.compose.material3.Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isRecording) onResumeCapture() else {
                            // On a collection page the new recording goes into it.
                            LibraryCollections.target = openKey?.takeIf { it.startsWith("uc:") }?.removePrefix("uc:")
                            onRecord()
                        }
                    },
                    shape = CircleShape, color = pal.Record,
                    shadowElevation = if (pal.isEink) 0.dp else 3.dp,
                    modifier = Modifier.size(72.dp).semantics { contentDescription = recLabel }.testTag("record-fab"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // A white dot (record) or a white square (a capture is running: back to it).
                        if (isRecording) Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
                        else Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White))
                    }
                }
                Text(
                    if (isRecording) fmtRec(recSeconds) else recLabel,
                    style = TypeRole.meta(), color = if (isRecording) pal.Record else pal.Slate400,
                    modifier = Modifier.padding(top = Spacing.xs).clearAndSetSemantics {},
                )
            }
        }
    }

    // --- The full queue: what is processing, what waits, and what can be done about each ---
    if (queueOpen) {
        val q = entries.filter { it.id == processingId || it.id in queuedIds }
            .sortedBy { if (it.id == processingId) 0 else 1 }
        ModalBottomSheet(onDismissRequest = { queueOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = pal.Slate800) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
                Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleMedium, color = pal.Slate200, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).semantics { heading() })
                q.forEachIndexed { i, e ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = pal.Hairline, modifier = Modifier.padding(horizontal = Spacing.lg))
                    val live = e.id == processingId
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .then(if (live) Modifier.clickable(role = Role.Button) { queueOpen = false; onWatchLive(e) } else Modifier)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title ?: SessionLibrary.defaultTitle(e.createdAt, uiLocale), style = TypeRole.rowTitle(), color = pal.Slate200,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (live && processingLabel.isNotBlank()) Text(processingLabel, style = TypeRole.meta(), color = pal.Slate400, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (live) {
                            TextButton(onClick = { queueOpen = false; onWatchLive(e) }) { Text(stringResource(R.string.action_watch_live), color = pal.Sky) }
                            IconButton(onClick = { onStopProcessing() }) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.action_stop_processing), tint = pal.Record) }
                        } else {
                            if (processingId == null) IconButton(onClick = { queueOpen = false; onProcessNow(e) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.action_process_now), tint = pal.Sky)
                            }
                            IconButton(onClick = { onRemoveFromQueue(e) }) {
                                Icon(Icons.Filled.RemoveCircleOutline, contentDescription = stringResource(R.string.action_remove_from_queue), tint = pal.Slate400)
                            }
                        }
                    }
                }
                if (pendingCount > 0) ActionRow(Icons.Filled.PlaylistPlay, stringResource(R.string.source_process_all, pendingCount)) { queueOpen = false; onProcessAll() }
            }
        }
    }

    // --- Collections: the picker for meetings, a new / renamed one's name, a collection's actions, the filter panel ---
    pickerFor?.let { ids ->
        ModalBottomSheet(onDismissRequest = { pickerFor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = pal.Slate800) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xxl).testTag("collection-picker")) {
                Text(stringResource(R.string.coll_add_to), style = MaterialTheme.typography.titleMedium, color = pal.Slate200, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).semantics { heading() })
                collections.collections.sortedByDescending { it.createdAt }.forEach { c ->
                    val n = entries.count { collections.members[it.id] == c.id }
                    ActionRow(Icons.Filled.Layers, c.name + " · " + stringResource(R.string.coll_count_meetings, n)) {
                        pickerFor = null; exitSelection(); onEditCollections { LibraryCollections.move(it, ids, c.id) }
                    }
                }
                ActionRow(Icons.Filled.Add, stringResource(R.string.coll_new)) { pickerFor = null; newCollFor = ids }
                Text(stringResource(R.string.coll_move_note), style = MaterialTheme.typography.labelSmall, color = pal.Slate400,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
            }
        }
    }
    val renameId = collActionsFor?.takeIf { renaming && it.startsWith("uc:") }?.removePrefix("uc:")
    if (newCollFor != null || renameId != null) {
        var name by remember(renameId, newCollFor) { mutableStateOf(renameId?.let { id -> collections.collections.firstOrNull { it.id == id }?.name } ?: "") }
        fun close() { newCollFor = null; renaming = false; collActionsFor = null }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text(stringResource(if (renameId != null) R.string.coll_rename else R.string.coll_new_title)) },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.coll_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("collection-name"))
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val ids = newCollFor.orEmpty(); val n = name
                    if (renameId != null) onEditCollections { LibraryCollections.rename(it, renameId, n) }
                    else { val id = LibraryCollections.newId(); onEditCollections { LibraryCollections.create(it, n, ids, id, System.currentTimeMillis()) } }
                    close(); exitSelection()
                }, modifier = Modifier.testTag("collection-name-save")) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val deleteId = collActionsFor?.takeIf { deletingColl && it.startsWith("uc:") }?.removePrefix("uc:")
    if (deleteId != null) AlertDialog(
        onDismissRequest = { deletingColl = false; collActionsFor = null },
        title = { Text(stringResource(R.string.coll_delete)) },
        text = { Text(stringResource(R.string.coll_delete_body)) },
        confirmButton = {
            TextButton(onClick = {
                deletingColl = false; collActionsFor = null
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onEditCollections { LibraryCollections.delete(it, deleteId) }
            }, modifier = Modifier.testTag("collection-delete-confirm")) { Text(stringResource(R.string.action_delete), color = PrimeMinutesPalette.Red) }
        },
        dismissButton = { TextButton(onClick = { deletingColl = false; collActionsFor = null }) { Text(stringResource(R.string.cancel)) } },
    )
    collActionsFor?.takeIf { !renaming && !deletingColl }?.let { key ->
        ModalBottomSheet(onDismissRequest = { collActionsFor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = pal.Slate800) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
                Text(LibraryCollections.nameOf(key, entries, collections) ?: "", style = MaterialTheme.typography.titleMedium, color = pal.Slate200,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).semantics { heading() })
                ActionRow(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.coll_rename)) { renaming = true }
                ActionRow(Icons.Filled.Delete, stringResource(R.string.coll_delete), PrimeMinutesPalette.Red) { deletingColl = true }
            }
        }
    }
    if (filterPanel) ModalBottomSheet(onDismissRequest = { filterPanel = false }, containerColor = pal.Slate800) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xxl).testTag("filter-panel")) {
            Text(stringResource(R.string.scope_filters), style = MaterialTheme.typography.titleMedium, color = pal.Slate200, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).semantics { heading() })
            @Composable fun chipRow(content: @Composable () -> Unit) = Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically,
            ) { content() }
            if (openKey == null && showSources) chipRow {
                for ((f, res) in listOf(SourceFilter.MEETINGS to R.string.filter_meetings, SourceFilter.PODCASTS to R.string.filter_podcasts,
                        SourceFilter.RECEIVED to R.string.filter_received)) {
                    val n = sourceCounts[f.category()!!] ?: 0
                    if (n > 0) FilterChip(stringResource(res, n), sourceFilter == f) { sourceFilter = if (sourceFilter == f) SourceFilter.ALL else f }
                }
            }
            chipRow {
                FilterChip(stringResource(R.string.filter_new, newCount), statusFilter == StatusFilter.NEW) {
                    statusFilter = if (statusFilter == StatusFilter.NEW) StatusFilter.ALL else StatusFilter.NEW
                }
                FilterChip(stringResource(R.string.filter_done, doneCount), statusFilter == StatusFilter.DONE) {
                    statusFilter = if (statusFilter == StatusFilter.DONE) StatusFilter.ALL else StatusFilter.DONE
                }
            }
            if (people.isNotEmpty()) chipRow {
                people.forEach { (p, ids) ->
                    FilterChip("${p.name} · ${ids.size}", personFilter == p.id) { personFilter = if (personFilter == p.id) null else p.id }
                }
            }
        }
    }

    // --- Row management sheet ---
    actionsFor?.let { sel ->
        // Re-derive from the LIVE list: the entry can change status while the sheet is open (a
        // background drain finishes it → recomposition with fresh entries), and branching on the
        // captured snapshot would keep offering "Process now" on an item that is already DONE.
        val e = entries.firstOrNull { it.id == sel.id } ?: sel
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, sheetState = sheetState, containerColor = pal.Slate800) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
                Text(
                    e.title ?: SessionLibrary.defaultTitle(e.createdAt, uiLocale),
                    style = MaterialTheme.typography.titleMedium, color = pal.Slate200, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).semantics { heading() },
                )
                if (e.download != null) {
                    // A download item: its own actions only (nothing to process or share yet).
                    if (e.download.error != null) ActionRow(Icons.Filled.Refresh, stringResource(R.string.retry)) { actionsFor = null; onRetryDownload(e) }
                    else ActionRow(Icons.Filled.Download, stringResource(R.string.cd_status_downloading)) { actionsFor = null; onOpenDownload(e) }
                    ActionRow(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename)) { renameFor = e; actionsFor = null }
                    ActionRow(Icons.Filled.Delete, stringResource(R.string.action_delete), PrimeMinutesPalette.Red) { actionsFor = null; requestDelete(e) }
                } else {
                // Long-press selects, and nothing in the UI says so; this is the path for a mouse, a
                // stylus, and TalkBack (the row's onLongClickLabel is announced, not shown).
                ActionRow(Icons.Filled.CheckCircle, stringResource(R.string.action_select)) { actionsFor = null; selectionMode = true; selected[e.id] = Unit }
                if (e.id == processingId) {
                    ActionRow(Icons.Filled.PlaylistPlay, stringResource(R.string.action_watch_live)) { actionsFor = null; onWatchLive(e) }
                    ActionRow(Icons.Filled.Stop, stringResource(R.string.action_stop_processing), PrimeMinutesPalette.Red) { actionsFor = null; onStopProcessing() }
                }
                // Queued (waiting), not the one being processed → let the user pull it back out.
                if (e.id != processingId && e.id in queuedIds) {
                    // …and, when nothing is being processed, start it. The drain otherwise only
                    // restarts on a cold launch, so a queue orphaned by the service being killed
                    // (device sleep) had NO way forward from here — the only actions were remove
                    // and delete. enqueue() dedupes, so this is safe on an already-queued item.
                    if (processingId == null) {
                        ActionRow(Icons.Filled.PlaylistPlay, stringResource(R.string.action_process_now)) { actionsFor = null; onProcessNow(e) }
                    }
                    ActionRow(Icons.Filled.RemoveCircleOutline, stringResource(R.string.action_remove_from_queue)) { actionsFor = null; onRemoveFromQueue(e) }
                }
                if (e.id != processingId && SessionLibrary.hasProgress(e)) {
                    ActionRow(Icons.Filled.PlayArrow, stringResource(R.string.action_resume)) { actionsFor = null; onResume(e) }
                }
                if (e.status != SessionLibrary.Status.DONE && e.id != processingId && e.id !in queuedIds) {
                    ActionRow(Icons.Filled.PlaylistPlay, stringResource(R.string.action_process_now)) { actionsFor = null; onProcessNow(e) }
                }
                if (e.status == SessionLibrary.Status.DONE) {
                    ActionRow(Icons.Filled.PlayArrow, stringResource(R.string.action_open)) { actionsFor = null; onOpen(e) }
                }
                ActionRow(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename)) { renameFor = e; actionsFor = null }
                // Podcast episodes stay in their channel; a meeting moves between collections (one at a time).
                if (e.category != Provenance.Category.PODCAST) {
                    ActionRow(Icons.Filled.Layers, stringResource(R.string.coll_move_to)) { actionsFor = null; pickerFor = listOf(e.id) }
                    if (LibraryCollections.keyOf(e, collections) != null) ActionRow(Icons.Filled.LayersClear, stringResource(R.string.coll_remove_from)) {
                        actionsFor = null; onEditCollections { LibraryCollections.removeItems(it, listOf(e.id)) }
                    }
                }
                ActionRow(Icons.Filled.Share, stringResource(R.string.share_title)) { actionsFor = null; onShare(e) }
                ActionRow(Icons.Filled.Delete, stringResource(R.string.action_delete), PrimeMinutesPalette.Red) { actionsFor = null; requestDelete(e) }
                }
            }
        }
    }

    renameFor?.let { e ->
        // The whole name starts selected: typing replaces it, and the cursor is not left at the start.
        var field by remember(e.id) {
            val t = e.title ?: SessionLibrary.defaultTitle(e.createdAt, uiLocale)
            mutableStateOf(TextFieldValue(t, TextRange(0, t.length)))
        }
        val name = field.text
        val focus = remember { FocusRequester() }
        LaunchedEffect(e.id) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(
                    value = field, onValueChange = { field = it }, singleLine = true,
                    modifier = Modifier.focusRequester(focus),
                    // The dialog opens with this field focused, and its only job is the new name —
                    // the return key was a dead generic Enter (singleLine, no action), so the user
                    // had to hunt the 確定 button. Done now commits, same as that button.
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        renameFor = null
                        val t = field.text.trim()
                        if (t.isNotBlank()) onRename(e, t)
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = pal.Slate200, unfocusedTextColor = pal.Slate200,
                        focusedBorderColor = pal.Sky, unfocusedBorderColor = pal.Slate700,
                        cursorColor = pal.Sky,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { renameFor = null; if (name.isNotBlank()) onRename(e, name.trim()) }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** One search passage under its meeting row: time · speaker · snippet; "≈" marks a passage found
 *  by meaning only. Tapping opens the meeting at that utterance. */
@Composable
private fun PassageRow(
    p: LibrarySearch.Passage,
    /** The meeting's custom speaker names and transcript speaker ids ([LibrarySearch.speakers]). */
    speakers: Pair<Map<Int, String>, Set<Int>>?,
    onClick: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pal = LocalPrimeMinutesPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = Gutter + 12.dp, end = Gutter)
            // A passage opens its meeting at that line: a button, like every other tap row here
            // (TalkBack read these as plain text with no tap cue).
            .clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (p.semanticOnly) Text(
            stringResource(R.string.search_semantic_marker), color = pal.Sky, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(16.dp).semantics { contentDescription = "" },
        ) else Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            val speaker = p.speaker?.takeIf { it.isNotBlank() }
                ?: p.speakerId?.let { stringResource(R.string.speaker_n, it + 1) }   // as the transcript names it
            val head = buildString {
                if (p.kind != SessionIndex.Kind.MEETING) {
                    val t = p.timeSec.toInt(); append(studio.primeminutes.fmtSec(t))
                }
                speaker?.let { if (isNotEmpty()) append(" · "); append(it) }
            }
            if (head.isNotEmpty()) Text(head, color = pal.Slate400, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A note names speakers by the reader's S-numbers: show them as the transcript does.
            val snippet = if (p.kind == SessionIndex.Kind.CHUNK || speakers == null) p.snippet
                else studio.primeminutes.core.reader.SpeakerRefs.resolve(p.snippet,
                    { id -> speakers.first[id] ?: ctx.getString(R.string.speaker_n, id + 1) }, speakers.second.ifEmpty { null })
            Text(snippet, color = pal.Slate200, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A library row that deletes on an end-to-start swipe (the caller shows Undo). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(enabled: Boolean, onDelete: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    // The swipe never settles on "dismissed": the delete fires and the row springs back (it is
    // hidden at once while the Undo snackbar shows). A saved dismissed state would come back with
    // the row after an Undo and delete it a second time.
    val latest = androidx.compose.runtime.rememberUpdatedState(onDelete)
    @Suppress("DEPRECATION")
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) latest.value()
        false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(LocalPrimeMinutesPalette.current.Record).padding(horizontal = Gutter),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = Color.White) }
        },
    ) { content() }
}

/** Contextual header shown during multi-select: exit · count · select-all · delete. */
@Composable
private fun SelectionHeader(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit, onAddTo: (() -> Unit)? = null) {
    val pal = LocalPrimeMinutesPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_exit_selection), tint = pal.Slate200) }
        Text(
            stringResource(R.string.selection_count, count),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = pal.Slate200,
            modifier = Modifier.weight(1f).padding(start = Spacing.xs).semantics { heading() },
        )
        TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all), color = pal.Sky, fontWeight = FontWeight.Bold) }
        if (onAddTo != null) IconButton(onClick = onAddTo, modifier = Modifier.testTag("selection-add-to")) {
            Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.coll_add_to), tint = pal.Sky)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = PrimeMinutesPalette.Red) }
    }
}

/**
 * One library row (redesign): title (2 lines max) over a meta line — time · length · speaker dots ·
 * count — with hairline separators, no card and no ⋮. A status pill appears only while the row is
 * not done. Long-press opens its actions, an end-to-start swipe deletes; TalkBack gets every action
 * as a custom action (rename, select, delete, more).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    entry: SessionLibrary.Entry,
    status: RowStatus,
    processingLabel: String,
    processingFraction: Float,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onManage: () -> Unit,
    onRename: () -> Unit = {},
    onSelect: () -> Unit = {},
    onDeleteAction: (() -> Unit)? = null,
    speakers: List<Int> = emptyList(),
    /** The user collection it sits in, named in its meta line (search hits under All). */
    collection: String? = null,
) {
    val pal = LocalPrimeMinutesPalette.current
    val uiLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val processing = status == RowStatus.Processing
    val downloading = status == RowStatus.Downloading
    val statusLabel = stringResource(when (status) {
        RowStatus.New -> R.string.cd_status_new
        RowStatus.Queued -> R.string.cd_status_queued
        RowStatus.Processing -> R.string.cd_status_processing
        RowStatus.Done -> R.string.cd_status_done
        RowStatus.NoSpeech -> R.string.status_no_speech
        RowStatus.Downloading -> R.string.cd_status_downloading
        RowStatus.DownloadFailed -> R.string.cd_status_download_failed
    })
    val pct = (processingFraction * 100).toInt()
    // The pill: text always (never colour alone); none once the row is done.
    val pill: Pair<String, Color>? = when (status) {
        RowStatus.Done -> null
        RowStatus.Queued -> stringResource(R.string.pill_queued) to pal.Slate400
        RowStatus.Processing -> (if (pct > 0) stringResource(R.string.pill_processing, pct) else stringResource(R.string.pill_working)) to PrimeMinutesPalette.Success
        RowStatus.Downloading -> (if (pct > 0) stringResource(R.string.pill_downloading, pct) else statusLabel) to pal.Sky
        RowStatus.DownloadFailed -> stringResource(R.string.pill_failed) to pal.ErrorText
        RowStatus.NoSpeech -> stringResource(R.string.pill_no_speech) to pal.Slate400
        RowStatus.New -> stringResource(if (entry.sourceOnly) R.string.pill_ready else R.string.pill_new) to pal.WarningText
    }
    val src = entry.source
    val podcast = entry.category == studio.primeminutes.core.library.Provenance.Category.PODCAST
    val received = entry.category == studio.primeminutes.core.library.Provenance.Category.RECEIVED
    val channel = src?.channelTitle?.takeIf { podcast }
    val receivedLabel = stringResource(R.string.label_received)
    val title = entry.title ?: SessionLibrary.defaultTitle(entry.createdAt, uiLocale)
    val speakersLabel = if (speakers.isNotEmpty()) stringResource(R.string.speakers_count, speakers.size) else null
    val rowDesc = listOfNotNull(title, channel, statusLabel, speakersLabel, receivedLabel.takeIf { received }).joinToString(", ")
    val longLabel = stringResource(R.string.action_more)
    val renameLabel = stringResource(R.string.action_rename)
    val selectLabel = stringResource(R.string.action_select)
    val deleteLabel = stringResource(R.string.action_delete)
    val isSelected = selected
    Column(
        Modifier
            .fillMaxWidth()
            // Opaque ground: the swipe's delete background must stay hidden until the row moves.
            .background(pal.Slate900)
            .background(if (selected) pal.ActiveTint else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longLabel)
            .semantics(mergeDescendants = true) {
                contentDescription = rowDesc
                if (selectionMode) this.selected = isSelected
                // The per-row ⋮ is gone: every action stays one TalkBack gesture away.
                if (!selectionMode) customActions = listOfNotNull(
                    androidx.compose.ui.semantics.CustomAccessibilityAction(renameLabel) { onRename(); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction(selectLabel) { onSelect(); true },
                    onDeleteAction?.let { d -> androidx.compose.ui.semantics.CustomAccessibilityAction(deleteLabel) { d(); true } },
                    androidx.compose.ui.semantics.CustomAccessibilityAction(longLabel) { onManage(); true },
                )
            }
            .padding(horizontal = Gutter),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.md)) {
            when {
                selectionMode -> {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .background(if (selected) pal.Sky else Color.Transparent)
                            .border(1.5.dp, if (selected) pal.Sky else pal.Slate600, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Icons.Filled.Check, null, tint = pal.OnSky, modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.width(Spacing.md))
                }
                // A podcast episode keeps its channel's cover.
                podcast -> {
                    studio.primeminutes.ui.components.ChannelArtwork(
                        studio.primeminutes.online.ArtworkCache.keyOf(src?.seriesId, src?.artworkUrl), src?.artworkUrl, 40.dp)
                    Spacer(Modifier.width(Spacing.md))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = pal.Slate200, style = TypeRole.rowTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = buildString {
                        collection?.takeIf { !podcast }?.let { append(it); append(" · ") }
                        if (podcast) {
                            channel?.let { append(it); append(" · ") }
                            append(DATE_FMT.format(Date(src?.published?.takeIf { it > 0 }?.times(1000) ?: entry.createdAt)))
                        } else append(TIME_FMT.format(Date(entry.createdAt)))
                        if (entry.durationSec > 0 || entry.download == null) {
                            append(" · ")
                            append(studio.primeminutes.fmtSec(entry.durationSec))
                        }
                        if (status == RowStatus.DownloadFailed && processingLabel.isNotBlank()) { append(" · "); append(processingLabel) }
                    }
                    Text(meta, color = if (status == RowStatus.DownloadFailed) pal.ErrorText else pal.Slate400,
                        style = TypeRole.meta(), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (speakers.isNotEmpty()) {
                        Text("  ·  ", color = pal.Slate400, style = TypeRole.meta())
                        SpeakerDots(speakers)
                        Text(" " + speakersLabel, color = pal.Slate400, style = TypeRole.meta(), maxLines = 1)
                    }
                }
                if (received) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Filled.MoveToInbox, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(receivedLabel, color = pal.Sky, style = TypeRole.meta(), fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            if (pill != null && !selectionMode) {
                Spacer(Modifier.width(Spacing.sm))
                StatusPill(pill.first, pill.second)
            }
        }
        if ((downloading || processing) && (processingFraction > 0f || pal.isEink)) {
            LinearProgressIndicator(
                progress = { processingFraction }, color = if (processing) PrimeMinutesPalette.Success else pal.Sky, trackColor = pal.Slate700,
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm).height(3.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        androidx.compose.material3.HorizontalDivider(color = pal.Hairline, thickness = 1.dp)
    }
}

/** Up to four speaker dots in their tints (identity is also said in words next to them). */
@Composable
private fun SpeakerDots(ids: List<Int>) {
    val pal = LocalPrimeMinutesPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        ids.take(4).forEach { id ->
            Box(Modifier.size(width = 9.dp, height = 7.dp).clip(RoundedCornerShape(2.dp))
                .background(Color(studio.primeminutes.data.speakerColorOn(id, pal.isDark))))
        }
    }
}

/** A 22 dp status pill: tinted fill, text in the hue (≥ 4.5:1). */
@Composable
internal fun StatusPill(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
        modifier = Modifier.heightIn(min = 22.dp).clip(CircleShape).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** The queue as one grouped block: dot · title · chevron, a thin progress line, the current step. */
@Composable
private fun QueueBar(title: String, step: String, fraction: Float, active: Boolean, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Column(
        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = Spacing.sm)
            .clip(MaterialTheme.shapes.large).background(pal.Fill)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.queue_open), onClick = onClick)
            .testTag("queue-bar")
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) PrimeMinutesPalette.Success else pal.Slate400))
            Spacer(Modifier.width(Spacing.sm))
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = pal.Slate200,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = pal.Slate400)
        }
        if (active) {
            val bar = Modifier.fillMaxWidth().padding(top = Spacing.sm).height(3.dp).clip(RoundedCornerShape(2.dp))
            if (fraction > 0f || pal.isEink) LinearProgressIndicator(progress = { fraction }, color = PrimeMinutesPalette.Success, trackColor = pal.Slate700, modifier = bar)
            else LinearProgressIndicator(color = PrimeMinutesPalette.Success, trackColor = pal.Slate700, modifier = bar)
        }
        if (step.isNotBlank()) Text(step, style = TypeRole.meta(), color = pal.Slate400, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs))
    }
}

private fun fmtRec(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

private fun statusIcon(status: RowStatus): ImageVector = when (status) {
    RowStatus.Done -> Icons.Filled.CheckCircle
    RowStatus.NoSpeech -> Icons.Filled.MicOff
    RowStatus.Queued -> Icons.Filled.Schedule
    RowStatus.Downloading -> Icons.Filled.Download
    RowStatus.DownloadFailed -> Icons.Filled.ErrorOutline
    else -> Icons.Filled.GraphicEq
}

/** A 36dp rounded tile holding a status glyph — soft-tinted, [filled] for the selection check, or
 *  [outlined] with a hairline for the faint New/unselected states that need an edge on e-ink. */
@Composable
private fun GlyphTile(color: Color, filled: Boolean = false, outlined: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(MaterialTheme.shapes.medium)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .then(if (outlined) Modifier.border(1.5.dp, color.copy(alpha = 0.5f), MaterialTheme.shapes.medium) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Four mini waveform bars — the processing-state glyph (motion-free but distinctive). */
@Composable
private fun WaveBars(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        listOf(9, 17, 12, 19).forEach { h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    // Redesign chip: a 30 dp pill (48 dp target) — ink-filled when on, a hairline outline when off.
    val pal = LocalPrimeMinutesPalette.current
    Box(
        Modifier.heightIn(min = 48.dp).semantics { selected = on; role = Role.Tab }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) pal.Slate900 else pal.Slate400,
            modifier = Modifier.heightIn(min = 30.dp).clip(CircleShape)
                .background(if (on) pal.Slate200 else Color.Transparent)
                .border(1.dp, if (on) pal.Slate200 else pal.Hairline, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.13f)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun Banner(icon: ImageVector, tint: Color, text: String, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = Spacing.sm)
            .clip(MaterialTheme.shapes.medium).background(tint.copy(alpha = 0.13f)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(text, color = pal.Slate200, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    ListItem(
        headlineContent = { Text(label, color = tint ?: pal.Slate200) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint ?: pal.Sky) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // TalkBack read the sheet's action rows (Rename, Delete, Process now, …) as plain text:
        // they are buttons, like every other row here.
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

private fun dayStart(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun dayLabel(day: Long, today: Long, yesterday: Long): String = when (day) {
    today -> stringResource(R.string.day_today)
    yesterday -> stringResource(R.string.day_yesterday)
    else -> DATE_FMT.format(Date(day)).uppercase()
}

/** The scope as it was (Back restores it). */
private data class ScopeSnap(val openKey: String?, val source: SourceFilter, val status: StatusFilter, val person: String?)

/** The scope as one pill under the search field: tap opens the filters, ✕ goes back to All. */
@Composable
private fun ScopePill(text: String, onOpen: () -> Unit, onClear: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = Spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(CircleShape).background(pal.ActiveTint).clickable(role = Role.Button, onClick = onOpen)
                .heightIn(min = 36.dp).padding(start = Spacing.md).testTag("scope-pill"),
        ) {
            Text(stringResource(R.string.scope_label, text), style = MaterialTheme.typography.labelLarge, color = pal.Sky,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp).testTag("scope-clear")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_scope_clear), tint = pal.Sky, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A collection's mark: a channel's cover, a stack for the user's collections. */
@Composable
private fun CollectionMark(c: LibraryCollections.Collection?, kind: LibraryCollections.Kind, size: androidx.compose.ui.unit.Dp) {
    val pal = LocalPrimeMinutesPalette.current
    if (kind == LibraryCollections.Kind.CHANNEL) {
        val art = c?.artwork
        studio.primeminutes.ui.components.ChannelArtwork(
            art?.let { studio.primeminutes.online.ArtworkCache.keyOf(it.seriesId, it.artworkUrl) }, art?.artworkUrl, size)
    } else Box(Modifier.size(size).clip(MaterialTheme.shapes.medium).background(pal.Sky.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Layers, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(size * 0.55f))
    }
}

/** One collection in the library: mark, name, count, latest item, "N new" (channels), its processing state. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionRow(c: LibraryCollections.Collection, newCount: Int, processing: Int, fraction: Float, onClick: () -> Unit, onLongClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val channel = c.kind == LibraryCollections.Kind.CHANNEL
    val name = c.name ?: stringResource(if (channel) R.string.podcasts_other else R.string.coll_user)
    val count = stringResource(if (channel) R.string.coll_count_episodes else R.string.coll_count_meetings, c.items.size)
    val latest = c.latestItem
    val uiLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val latestLine = latest?.let { stringResource(R.string.coll_latest, it.title ?: SessionLibrary.defaultTitle(it.createdAt, uiLocale), DATE_FMT.format(Date(it.createdAt))) }
    val pct = (fraction * 100).toInt()
    val procLine = if (processing > 0) stringResource(if (channel) R.string.coll_processing_episodes else R.string.coll_processing_meetings, processing, pct) else null
    val kindLabel = stringResource(if (channel) R.string.coll_channel else R.string.coll_user)
    val badge = if (newCount > 0) stringResource(R.string.coll_new_badge, newCount) else null
    Column(
        Modifier.fillMaxWidth().background(pal.Slate900)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(name, kindLabel, count, badge, procLine, latestLine).joinToString(", ") }
            .padding(horizontal = Gutter).testTag("collection-row"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.md)) {
            CollectionMark(c, c.kind, 40.dp)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = pal.Slate200, style = TypeRole.rowTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(count, color = pal.Slate400, style = TypeRole.meta(), maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
                Text(procLine ?: latestLine ?: kindLabel, color = if (procLine != null) PrimeMinutesPalette.Success else pal.Slate400,
                    style = TypeRole.meta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) {
                Spacer(Modifier.width(Spacing.sm))
                StatusPill(badge, pal.Sky)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = pal.Slate400)
        }
        if (processing > 0) LinearProgressIndicator(
            progress = { fraction }, color = PrimeMinutesPalette.Success, trackColor = pal.Slate700,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm).height(3.dp).clip(RoundedCornerShape(2.dp)),
        )
        androidx.compose.material3.HorizontalDivider(color = pal.Hairline, thickness = 1.dp)
    }
}

/** A collection page's top: back, name and counts, Ask this collection, and (the user's) record / import into it. */
@Composable
private fun CollectionHeader(
    key: String, coll: LibraryCollections.Collection?, name: String?,
    onBack: () -> Unit, onAsk: (() -> Unit)?, onRecordInto: () -> Unit, onImportInto: () -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit, onChannelInfo: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    val kind = LibraryCollections.kindOf(key)
    val channel = kind == LibraryCollections.Kind.CHANNEL
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = pal.Slate200) }
        Spacer(Modifier.weight(1f))
        if (!channel || key != "ch:") Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.cd_more_options), tint = pal.Slate200) }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (channel) androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.coll_channel_info)) }, onClick = { menu = false; onChannelInfo() })
                else {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.coll_rename)) }, onClick = { menu = false; onRename() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.coll_delete), color = PrimeMinutesPalette.Red) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        CollectionMark(coll, kind, 56.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(name ?: stringResource(if (channel) R.string.podcasts_other else R.string.coll_user), style = TypeRole.largeTitle(), color = pal.Slate200,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            val n = coll?.items?.size ?: 0
            val total = coll?.totalSec?.takeIf { it > 0 }?.let { stringResource(R.string.coll_total, studio.primeminutes.fmtSec(it)) }
            Text(listOfNotNull(stringResource(if (channel) R.string.coll_channel else R.string.coll_user),
                stringResource(if (channel) R.string.coll_count_episodes else R.string.coll_count_meetings, n), total).joinToString(" · "),
                style = TypeRole.meta(), color = pal.Slate400, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (onAsk != null) Button(onClick = onAsk, modifier = Modifier.testTag("collection-ask")) {
            Icon(Icons.Filled.QuestionAnswer, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(if (channel) R.string.ask_this_channel else R.string.ask_this_collection), maxLines = 1)
        }
        if (!channel) Box {
            var into by remember { mutableStateOf(false) }
            androidx.compose.material3.OutlinedButton(onClick = { into = true }, border = androidx.compose.foundation.BorderStroke(1.dp, pal.Slate600),
                modifier = Modifier.testTag("collection-record-into")) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = pal.Record, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.coll_record_into), color = pal.Sky, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.DropdownMenu(expanded = into, onDismissRequest = { into = false }) {
                androidx.compose.material3.DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Mic, null) }, text = { Text(stringResource(R.string.record_fab)) },
                    onClick = { into = false; onRecordInto() })
                androidx.compose.material3.DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_audio)) },
                    onClick = { into = false; onImportInto() })
            }
        }
    }
}

/** "N meetings look alike — make a collection?" (once per group: Skip is for good). */
@Composable
private fun SuggestionCard(sg: LibraryCollections.Suggestion, onCreate: () -> Unit, onSkip: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = Spacing.sm).clip(MaterialTheme.shapes.medium)
            .background(pal.ActiveTint).padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs).testTag("collection-suggestion"),
    ) {
        Icon(Icons.Filled.Layers, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(stringResource(R.string.coll_suggest, sg.ids.size, sg.name), style = MaterialTheme.typography.bodyMedium, color = pal.Slate200, modifier = Modifier.weight(1f))
        TextButton(onClick = onCreate, modifier = Modifier.testTag("collection-suggestion-create")) { Text(stringResource(R.string.coll_create), color = pal.Sky, fontWeight = FontWeight.Bold) }
        TextButton(onClick = onSkip) { Text(stringResource(R.string.coll_skip), color = pal.Slate400) }
    }
}
