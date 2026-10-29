package studio.primeminutes.online

import android.content.Context
import android.util.Log
import studio.primeminutes.core.library.Provenance
import studio.primeminutes.core.library.SessionLibrary

/**
 * The library's provenance upkeep, run lazily once per process when the library opens (never blocking it):
 *  - an episode imported before the channel snapshot gets its channel title, artist and artwork URL (and
 *    its date and length) from its subscription in subscriptions.json, written into its source.json;
 *  - channel artwork not cached yet is fetched once (best-effort, [ArtworkCache]);
 *  - cached artwork no item or subscription uses any more is deleted.
 */
object LibrarySources {
    private const val TAG = "LibrarySources"
    @Volatile private var ran = false

    /** True when an item's source.json changed (the list should re-read). Runs once per process. */
    suspend fun upkeepOnce(ctx: Context): Boolean {
        if (ran) return false
        ran = true
        return runCatching { upkeep(ctx) }.onFailure { Log.w(TAG, "library sources upkeep failed", it) }.getOrDefault(false)
    }

    private suspend fun upkeep(ctx: Context): Boolean {
        val subs = Subscriptions.load(ctx)
        val channels = subs.associate { s ->
            s.id to Provenance.Channel(s.id, s.title, s.artist, s.artworkUrl, s.feedUrl,
                s.episodes.associate { it.guid to (it.published to it.durationSec) })
        }
        var changed = 0
        val keep = HashSet<String>()
        val fetch = LinkedHashMap<String, String?>()
        for (e in SessionLibrary.list(ctx)) {
            var p = e.source ?: continue
            Provenance.backfill(p, channels)?.let { next ->
                runCatching { Provenance.write(e.dir, next) }.onSuccess { changed++; p = next }
            }
            if (p.category != Provenance.Category.PODCAST) continue
            ArtworkCache.keyOf(p.seriesId, p.artworkUrl)?.let { k -> keep += k; if (fetch[k] == null) fetch[k] = p.artworkUrl }
        }
        for (s in subs) ArtworkCache.keyOf(s.id, s.artworkUrl)?.let { k -> keep += k; if (fetch[k] == null) fetch[k] = s.artworkUrl.ifBlank { null } }
        ArtworkCache.prune(ctx, keep)
        if (changed > 0) Log.i(TAG, "backfilled the channel of $changed episode(s)")
        var fetched = 0
        for ((k, url) in fetch) if (ArtworkCache.cached(ctx, k) == null && ArtworkCache.ensure(ctx, k, url) != null) fetched++
        if (fetched > 0) ImportDownloads.changed()
        return changed > 0
    }
}
