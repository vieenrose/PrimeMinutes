package studio.primeminutes.online

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.primeminutes.core.library.SessionLibrary

/** Subscribe / check / queue (UI-facing, serialised by one mutex: a check and a submit never race into two rows). */
object SubscriptionActions {
    private val mutex = Mutex()
    private val _checking = MutableStateFlow<Set<String>>(emptySet())
    /** Ids being checked now. */
    val checking: StateFlow<Set<String>> = _checking
    /** One-shot notices for the screen: a subscription that moved (old id → new id). */
    private val _moved = MutableStateFlow<String?>(null)
    val moved: StateFlow<String?> = _moved
    fun consumeMoved() { _moved.value = null }

    /** A preview of a feed (subscribe screen): fetched, nothing saved. */
    suspend fun preview(feedUrl: String): FeedFetcher.Result = FeedFetcher.fetch(feedUrl.trim())

    /** Saves [feed] as a subscription with the first-subscribe baseline (every current episode counts as seen). */
    suspend fun subscribe(ctx: Context, feedUrl: String, feed: Feed, country: String = Podcast.searchCountry(), fallbackTitle: String = "", fallbackArtist: String = "", fallbackArtwork: String = ""): Subscription =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                Subscriptions.byId(ctx, SubscriptionLogic.idOf(feedUrl))?.let { return@withLock it }
                val f = feed.copy(title = feed.title.ifBlank { fallbackTitle }, artist = feed.artist.ifBlank { fallbackArtist },
                    artworkUrl = feed.artworkUrl.ifBlank { fallbackArtwork })
                SubscriptionLogic.baseline(feedUrl, f, country, System.currentTimeMillis() / 1000).also {
                    Subscriptions.upsert(ctx, it)
                    // The channel page and its episodes' rows draw the artwork offline from here on.
                    ImportDownloads.cacheArtwork(ctx, studio.primeminutes.core.library.Provenance(studio.primeminutes.core.library.Provenance.PODCAST, seriesId = it.id, artworkUrl = it.artworkUrl.ifBlank { null }))
                }
            }
        }

    /** Subscribes from a search hit (fetches the feed first). */
    suspend fun subscribe(ctx: Context, s: PodcastSeries): Result<Subscription> = when (val r = preview(s.feedUrl)) {
        is FeedFetcher.Result.Ok -> Result.success(subscribe(ctx, if (r.moved) r.finalUrl else s.feedUrl, r.feed, fallbackTitle = s.title, fallbackArtist = s.artist, fallbackArtwork = s.artworkUrl))
        is FeedFetcher.Result.Failed -> Result.failure(CheckFailed(r.error))
        is FeedFetcher.Result.NotModified -> Result.failure(CheckFailed(CheckError.RETRY_LATER))
    }

    class CheckFailed(val error: CheckError) : Exception(error.name)

    /** Checks one subscription; returns its id after the check (changed when the feed moved for good). */
    suspend fun check(ctx: Context, id: String): String = withContext(Dispatchers.IO) {
        val sub = Subscriptions.byId(ctx, id) ?: return@withContext id
        _checking.value += id
        try {
            val r = FeedFetcher.fetch(sub.feedUrl, sub.etag, sub.lastModified)
            mutex.withLock {
                val cur = Subscriptions.byId(ctx, id) ?: return@withLock id
                val now = System.currentTimeMillis() / 1000
                val next = when (r) {
                    is FeedFetcher.Result.Ok -> SubscriptionLogic.merge(cur, r.feed, now).copy(etag = r.etag, lastModified = r.lastModified)
                        .let { if (r.moved) SubscriptionLogic.rekey(it, r.finalUrl).copy(etag = r.etag, lastModified = r.lastModified) else it }
                    is FeedFetcher.Result.NotModified -> cur.copy(lastChecked = now, checkError = null).let { if (r.moved) SubscriptionLogic.rekey(it, r.finalUrl) else it }
                    is FeedFetcher.Result.Failed -> cur.copy(checkError = r.error.name)
                }
                Subscriptions.update(ctx) { l -> l.map { if (it.id == id) next else it }.distinctBy { it.id } }
                if (next.id != id) _moved.value = next.id
                next.id
            }
        } finally { _checking.value -= id }
    }

    /** Checks every subscription that is not paused, one after the other. */
    suspend fun checkAll(ctx: Context) {
        for (s in Subscriptions.load(ctx).filter { !it.paused }) check(ctx, s.id)
    }

    /** Opening a show page: checks it once if the last check is over 24 h old (skipped when paused). */
    suspend fun checkIfStale(ctx: Context, id: String) {
        val s = Subscriptions.byId(ctx, id) ?: return
        if (!s.paused && System.currentTimeMillis() / 1000 - s.lastChecked > 24 * 3600) check(ctx, id)
    }

    fun setPaused(ctx: Context, id: String, paused: Boolean) { Subscriptions.update(ctx) { l -> l.map { if (it.id == id) it.copy(paused = paused) else it } } }

    /** Where an episode stands in the library: its item id when imported or queued (by guid, else by audio URL). */
    fun libraryIndex(ctx: Context): Map<String, String> {
        val m = HashMap<String, String>()
        for ((id, url, guid) in SessionLibrary.sources(ctx)) { if (url.isNotBlank()) m["url:$url"] = id; guid?.let { m["guid:$it"] = id } }
        return m
    }

    fun entryFor(index: Map<String, String>, ep: FeedEpisode): String? = index["guid:${ep.guid}"] ?: index["url:${ep.audioUrl}"]

    /** The channel snapshot an episode of [s] is imported with (title, artist, artwork, its date and length). */
    fun channelOf(s: Subscription?, seriesId: String, published: Long, durationSec: Int) = studio.primeminutes.core.library.Provenance(
        studio.primeminutes.core.library.Provenance.PODCAST, seriesId = seriesId,
        channelTitle = s?.title?.ifBlank { null }, channelArtist = s?.artist?.ifBlank { null }, artworkUrl = s?.artworkUrl?.ifBlank { null }, feedUrl = s?.feedUrl,
        published = published, durationSec = durationSec,
    )

    sealed interface Queue { data class Started(val entryId: String) : Queue; data class Already(val entryId: String) : Queue; data object NoSpace : Queue }

    /**
     * "Download and transcribe": dedupes against the library (same guid or URL → the existing item), checks the
     * space the episode needs, then submits it like a single-episode import and marks it seen.
     */
    suspend fun queue(ctx: Context, subId: String, ep: FeedEpisode, forceLowSpace: Boolean = false): Queue = withContext(Dispatchers.IO) {
        mutex.withLock {
            entryFor(libraryIndex(ctx), ep)?.let { existing ->
                SessionLibrary.byId(ctx, existing)?.let { return@withLock Queue.Already(existing) }
            }
            val need = SubscriptionLogic.spaceNeeded(ep.lengthBytes)
            val free = runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (forceLowSpace || (need > 0 && free < need)) return@withLock Queue.NoSpace
            val sub = Subscriptions.byId(ctx, subId)
            val e = SessionLibrary.createDownloadEntry(ctx, ep.title,
                SessionLibrary.PendingDownload(SessionLibrary.PendingDownload.PODCAST, ep.audioUrl, seriesId = subId, guid = ep.guid), ep.durationSec,
                channel = channelOf(sub, subId, ep.published, ep.durationSec))
                ?: return@withLock Queue.NoSpace
            Subscriptions.update(ctx) { l -> l.map { if (it.id == subId) SubscriptionLogic.markSeen(it, ep.guid, e.id) else it } }
            ImportDownloads.changed(); ImportDownloads.kick(ctx)
            ImportDownloads.cacheArtwork(ctx, e.source)
            Queue.Started(e.id)
        }
    }
}
