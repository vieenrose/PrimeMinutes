package studio.primeminutes.core.library

import org.json.JSONObject
import java.io.File

/**
 * Where a library item came from (its `source.json`, kept for good): what the library's source
 * filters (Meetings / Podcasts / Received) and the podcast rows read. Additive and optional: an item
 * saved before a field simply lacks it, and an item with no source.json at all is a meeting.
 *
 * Field names are shared with iOS (docs: library redesign study §5).
 */
data class Provenance(
    val kind: String,
    /** The podcast episode's audio URL ("" for a meeting). */
    val sourceUrl: String = "",
    /** The subscription id (`podcast-<fnv1a(feedUrl)>`) of the episode's channel, when known. */
    val seriesId: String? = null,
    val guid: String? = null,
    /** Snapshot of the channel at import, so unsubscribing never orphans an episode. */
    val channelTitle: String? = null,
    val channelArtist: String? = null,
    val artworkUrl: String? = null,
    /** The channel's RSS feed (subscribing from its channel page). */
    val feedUrl: String? = null,
    /** The episode's publication date, epoch seconds (0: unknown). */
    val published: Long = 0,
    /** The episode's length in the feed, seconds (0: unknown). */
    val durationSec: Int = 0,
    /** A received meeting: when it arrived here (epoch ms, 0: unknown). */
    val receivedAt: Long = 0,
) {
    /** The library filter this item sits under. */
    val category: Category get() = categoryOf(kind)

    fun toJson(): JSONObject = JSONObject().put("kind", kind).apply {
        if (sourceUrl.isNotEmpty()) put("sourceUrl", sourceUrl)
        seriesId?.let { put("seriesId", it) }; guid?.let { put("guid", it) }
        channelTitle?.let { put("channelTitle", it) }; channelArtist?.let { put("channelArtist", it) }
        artworkUrl?.let { put("artworkUrl", it) }; feedUrl?.let { put("feedUrl", it) }
        if (published > 0) put("published", published)
        if (durationSec > 0) put("durationSec", durationSec)
        if (receivedAt > 0) put("receivedAt", receivedAt)
    }

    enum class Category { MEETING, PODCAST, RECEIVED }

    /** The channel facts a subscription knows (title, artist, artwork; its episodes' guid → (published, durationSec)). */
    data class Channel(val id: String, val title: String, val artist: String, val artworkUrl: String, val feedUrl: String = "",
                       val episodes: Map<String, Pair<Long, Int>> = emptyMap())

    companion object {
        const val RECORDING = "recording"
        const val FILE = "file"
        const val PODCAST = "podcast"
        const val RECEIVED = "received"
        /** Imported by versions before 0.55 only. */
        const val YOUTUBE = "youtube"
        const val PEERTUBE = "peertube"

        /** Kinds from an online source (shared as text only by default, the audio being its publisher's). */
        val ONLINE = setOf(PODCAST, YOUTUBE, PEERTUBE)

        /**
         * No provenance (an item older than the field), a recording or a file → Meetings. A legacy YouTube /
         * PeerTube item sits with the podcasts (its own channel-less group), the only online view left.
         */
        fun categoryOf(kind: String?): Category = when (kind) {
            PODCAST, YOUTUBE, PEERTUBE -> Category.PODCAST
            RECEIVED -> Category.RECEIVED
            else -> Category.MEETING
        }

        fun fromJson(o: JSONObject) = Provenance(
            kind = o.optString("kind").ifBlank { RECORDING },
            sourceUrl = o.optString("sourceUrl"),
            seriesId = o.optString("seriesId").ifBlank { null },
            guid = o.optString("guid").ifBlank { null },
            channelTitle = o.optString("channelTitle").ifBlank { null },
            channelArtist = o.optString("channelArtist").ifBlank { null },
            artworkUrl = o.optString("artworkUrl").ifBlank { null },
            feedUrl = o.optString("feedUrl").ifBlank { null },
            published = o.optLong("published"),
            durationSec = o.optInt("durationSec"),
            receivedAt = o.optLong("receivedAt"),
        )

        fun read(dir: File): Provenance? =
            File(dir, SessionLibrary.SOURCE).takeIf { it.isFile }?.let { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }

        fun write(dir: File, p: Provenance) = File(dir, SessionLibrary.SOURCE).writeTextAtomic(p.toJson().toString())

        /**
         * Lazy backfill: a podcast episode imported before the channel snapshot takes it from its subscription
         * ([channels] by id; episodes by guid → (published, durationSec)). Null when nothing changes.
         */
        fun backfill(p: Provenance, channels: Map<String, Channel>): Provenance? {
            if (p.kind != PODCAST) return null
            val c = p.seriesId?.let { channels[it] } ?: return null
            val ep = p.guid?.let { c.episodes[it] }
            val next = p.copy(
                channelTitle = p.channelTitle ?: c.title.ifBlank { null },
                channelArtist = p.channelArtist ?: c.artist.ifBlank { null },
                artworkUrl = p.artworkUrl ?: c.artworkUrl.ifBlank { null },
                feedUrl = p.feedUrl ?: c.feedUrl.ifBlank { null },
                published = if (p.published > 0) p.published else ep?.first ?: 0,
                durationSec = if (p.durationSec > 0) p.durationSec else ep?.second ?: 0,
            )
            return next.takeIf { it != p }
        }

        /** The channel group key of a podcast item: its series, else its title, else "" (Other podcasts). */
        fun groupKey(p: Provenance?): String = p?.seriesId ?: p?.channelTitle?.let { "title:$it" } ?: ""
    }
}
