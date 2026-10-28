package studio.primeminutes.online

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import studio.primeminutes.core.library.writeTextAtomic
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Podcast series subscriptions (docs/design/podcast-series.md, v1 "manual check").
 * A subscription is a saved feed link: checking it is one user-visible GET, and a new episode goes
 * through the existing single-episode import ([ImportDownloads.submit]); the pipeline is untouched.
 */

/** One episode of a subscribed feed. [guid] is the identity key (guid → link → enclosure URL → sha1(title+pubDate)). */
data class FeedEpisode(
    val guid: String,
    val title: String,
    val audioUrl: String,
    val published: Long = 0,        // epoch seconds, 0 = unknown
    val durationSec: Int = 0,
    val lengthBytes: Long = 0,
    val queuedEntryId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("guid", guid).put("title", title).put("audioUrl", audioUrl)
        .put("published", published).put("durationSec", durationSec).put("lengthBytes", lengthBytes)
        .apply { queuedEntryId?.let { put("queuedEntryId", it) } }

    companion object {
        fun fromJson(o: JSONObject) = FeedEpisode(
            o.getString("guid"), o.optString("title"), o.optString("audioUrl"), o.optLong("published"),
            o.optInt("durationSec"), o.optLong("lengthBytes"), o.optString("queuedEntryId").ifBlank { null },
        )
    }
}

/** A parsed feed: channel metadata + its newest audio episodes (newest first, at most [FeedParser.MAX_EPISODES]). */
data class Feed(val title: String, val artist: String, val artworkUrl: String, val episodes: List<FeedEpisode>)

/** A saved subscription (subscriptions.json). Field names are shared with iOS. */
data class Subscription(
    val feedUrl: String,
    val title: String,
    val artist: String = "",
    val artworkUrl: String = "",
    val country: String = "",
    val addedAt: Long = 0,
    val lastChecked: Long = 0,
    val checkError: String? = null,
    val auto: Boolean = false,          // v2, never shown in v1
    val paused: Boolean = false,
    val seenGuids: List<String> = emptyList(),
    val episodes: List<FeedEpisode> = emptyList(),
    val etag: String? = null,
    val lastModified: String? = null,
) {
    val id: String get() = SubscriptionLogic.idOf(feedUrl)

    /** Episodes not seen yet (the "new" badge count). */
    val newEpisodes: List<FeedEpisode> get() { val seen = seenGuids.toHashSet(); return episodes.filter { it.guid !in seen } }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("feedUrl", feedUrl).put("country", country)
        .put("title", title).put("artist", artist).put("artworkUrl", artworkUrl).put("addedAt", addedAt)
        .put("lastChecked", lastChecked).put("auto", auto).put("paused", paused)
        .put("seenGuids", JSONArray(seenGuids)).put("episodes", JSONArray(episodes.map { it.toJson() }))
        .apply { checkError?.let { put("checkError", it) }; etag?.let { put("etag", it) }; lastModified?.let { put("lastModified", it) } }

    companion object {
        fun fromJson(o: JSONObject): Subscription {
            val seen = o.optJSONArray("seenGuids"); val eps = o.optJSONArray("episodes")
            return Subscription(
                feedUrl = o.getString("feedUrl"), title = o.optString("title"), artist = o.optString("artist"),
                artworkUrl = o.optString("artworkUrl"), country = o.optString("country"), addedAt = o.optLong("addedAt"),
                lastChecked = o.optLong("lastChecked"), checkError = o.optString("checkError").ifBlank { null },
                auto = o.optBoolean("auto"), paused = o.optBoolean("paused"),
                seenGuids = seen?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                episodes = eps?.let { a -> (0 until a.length()).mapNotNull { runCatching { FeedEpisode.fromJson(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
                etag = o.optString("etag").ifBlank { null }, lastModified = o.optString("lastModified").ifBlank { null },
            )
        }
    }
}

/** Why a check failed (each maps to one status string; see the plan's failure table). */
enum class CheckError { GONE, PAYWALLED, UNPARSABLE, NO_AUDIO, RETRY_LATER }

/** Pure rules (JVM-tested): identity, baseline, merge, eviction, rekey, HTTP classification. */
object SubscriptionLogic {
    const val MAX_SEEN = 2000

    fun idOf(feedUrl: String): String = "podcast-" + fnv1a(feedUrl)

    /** 64-bit FNV-1a in hex (same as iOS Podcast.stableHash). */
    fun fnv1a(s: String): String {
        var h = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
        for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return java.lang.Long.toUnsignedString(h, 16)   // no zero padding: identical to iOS String(h, radix: 16)
    }

    fun identity(guid: String, link: String, audioUrl: String, title: String, pubDate: String): String = when {
        guid.isNotBlank() -> "guid:" + guid.trim()
        link.isNotBlank() -> "link:" + link.trim()
        audioUrl.isNotBlank() -> "url:" + audioUrl.trim()
        else -> "sha1:" + MessageDigest.getInstance("SHA-1").digest((title + pubDate).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** First subscription: every episode in the feed counts as seen (no backfill flood). */
    fun baseline(feedUrl: String, feed: Feed, country: String, now: Long) = Subscription(
        feedUrl = feedUrl, title = feed.title, artist = feed.artist, artworkUrl = feed.artworkUrl, country = country,
        addedAt = now, lastChecked = now, seenGuids = feed.episodes.map { it.guid }.distinct().take(MAX_SEEN),
        episodes = feed.episodes,
    )

    /**
     * A successful check: the episode list becomes the feed's window (keeping each episode's queued entry), the
     * header follows the channel, [lastChecked] moves, the error clears. Future-dated episodes are never new: they
     * are marked seen. Seen guids are kept (capped by [evict]).
     */
    fun merge(sub: Subscription, feed: Feed, now: Long): Subscription {
        val queued = sub.episodes.associate { it.guid to it.queuedEntryId }
        val eps = feed.episodes.map { e -> queued[e.guid]?.let { e.copy(queuedEntryId = it) } ?: e }
        val future = eps.filter { it.published > now + 3600 }.map { it.guid }
        return sub.copy(
            title = feed.title.ifBlank { sub.title }, artist = feed.artist.ifBlank { sub.artist },
            artworkUrl = feed.artworkUrl.ifBlank { sub.artworkUrl }, lastChecked = now, checkError = null,
            episodes = eps, seenGuids = evict(sub.seenGuids + future.filter { it !in sub.seenGuids }, eps),
        )
    }

    /** Marks [guid] seen (the user queued or dismissed it). */
    fun markSeen(sub: Subscription, guid: String, entryId: String?): Subscription = sub.copy(
        seenGuids = evict(if (guid in sub.seenGuids) sub.seenGuids else sub.seenGuids + guid, sub.episodes),
        episodes = sub.episodes.map { if (it.guid == guid && entryId != null) it.copy(queuedEntryId = entryId) else it },
    )

    /**
     * The cap protects the file size and is not a sliding window: over [MAX_SEEN], only the oldest guids that are
     * no longer listed in [episodes] are dropped, so a listed episode that was seen never becomes new again.
     */
    fun evict(seen: List<String>, episodes: List<FeedEpisode>, max: Int = MAX_SEEN): List<String> {
        if (seen.size <= max) return seen
        val listed = episodes.map { it.guid }.toHashSet()
        var drop = seen.size - max
        return seen.filter { g -> if (drop > 0 && g !in listed) { drop--; false } else true }
    }

    /** A permanent move (301/308): the feed URL (and so the id) changes; guids, episodes and sessions do not. */
    fun rekey(sub: Subscription, newFeedUrl: String) = sub.copy(feedUrl = newFeedUrl, etag = null, lastModified = null)

    fun classify(status: Int): CheckError? = when (status) {
        in 200..299, 304 -> null
        404, 410 -> CheckError.GONE
        401, 402 -> CheckError.PAYWALLED
        403 -> CheckError.PAYWALLED
        429 -> CheckError.RETRY_LATER
        else -> CheckError.RETRY_LATER
    }

    /**
     * Follows redirects by hand ([step]: url → (status, Location)), at most [maxHops]. Returns the final URL, the
     * final status and whether every hop was permanent (301/308): only then does the subscription rekey.
     * Null on a loop, too many hops or a non-http Location.
     */
    fun followRedirects(start: String, maxHops: Int = 5, step: (String) -> Pair<Int, String?>): Hop? {
        var url = start; var permanent = true; val visited = mutableSetOf(start)
        repeat(maxHops + 1) {
            val (status, loc) = step(url)
            if (status !in 300..399 || status == 304) return Hop(url, status, permanent && url != start)
            val next = runCatching { URL(URL(url), loc ?: return null).toString() }.getOrNull() ?: return null
            if (!next.startsWith("http://") && !next.startsWith("https://")) return null
            if (!visited.add(next)) return null
            if (status != 301 && status != 308) permanent = false
            url = next
        }
        return null
    }

    data class Hop(val url: String, val status: Int, val movedPermanently: Boolean)

    /** Disk full on an import download (matched on the message, not the exception type: network IOExceptions are transient). */
    fun isNoSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
            t = t.cause
        }
        return false
    }

    /** Estimated space an episode needs (source + 16 kHz mono WAV + margin); 0 when the feed gives no length. */
    fun spaceNeeded(lengthBytes: Long): Long = if (lengthBytes <= 0) 0 else lengthBytes * 3 + 200L * 1024 * 1024
}

/** RSS parser for subscriptions (namespaces off: tags keep their prefix, e.g. `itunes:image`). */
object FeedParser {
    const val MAX_EPISODES = 60
    const val MAX_FEED_BYTES = 2L * 1024 * 1024

    private val dateFormats = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z")

    fun parseDate(s: String): Long {
        val t = s.trim(); if (t.isEmpty()) return 0
        for (f in dateFormats) runCatching { return SimpleDateFormat(f, Locale.US).parse(t)!!.time / 1000 }
        return 0
    }

    fun parseDuration(s: String): Int {
        val p = s.trim().split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
        return p.fold(0.0) { acc, v -> acc * 60 + v }.toInt()
    }

    fun parse(input: InputStream, limit: Int = MAX_EPISODES): Feed {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        var chTitle = ""; var chAuthor = ""; var chArt = ""
        val eps = mutableListOf<FeedEpisode>()
        var inItem = false; var depthImage = false
        var title = ""; var guid = ""; var link = ""; var audio = ""; var pub = ""; var dur = ""; var len = 0L
        var sawItem = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && eps.size < limit) {
            val n = parser.name.orEmpty()
            when (event) {
                XmlPullParser.START_TAG -> when {
                    n.equals("item", true) || n.equals("entry", true) -> { inItem = true; sawItem = true; title = ""; guid = ""; link = ""; audio = ""; pub = ""; dur = ""; len = 0 }
                    inItem -> when {
                        n.equals("title", true) -> title = text(parser)
                        n.equals("guid", true) || n.equals("id", true) -> guid = text(parser)
                        n.equals("link", true) || n.equals("atom:link", true) -> {
                            val rel = parser.getAttributeValue(null, "rel").orEmpty(); val href = parser.getAttributeValue(null, "href").orEmpty()
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            if (rel.equals("enclosure", true) && audio.isBlank() && type.startsWith("audio/", true) && href.isNotBlank()) {
                                audio = href; len = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0
                            } else if (href.isBlank()) link = text(parser) else if (link.isBlank()) link = href
                        }
                        n.equals("pubDate", true) || n.equals("published", true) -> pub = text(parser)
                        n.equals("itunes:duration", true) -> dur = text(parser)
                        n.equals("enclosure", true) || n.equals("media:content", true) -> {
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val u = parser.getAttributeValue(null, "url").orEmpty()
                            if (audio.isBlank() && type.startsWith("audio/", true) && u.isNotBlank()) {
                                audio = u
                                len = (parser.getAttributeValue(null, "length") ?: parser.getAttributeValue(null, "fileSize"))?.toLongOrNull() ?: 0
                            }
                        }
                    }
                    n.equals("title", true) && chTitle.isEmpty() && !depthImage -> chTitle = text(parser)
                    n.equals("itunes:author", true) && chAuthor.isEmpty() -> chAuthor = text(parser)
                    n.equals("itunes:image", true) && chArt.isEmpty() -> chArt = parser.getAttributeValue(null, "href").orEmpty()
                    n.equals("image", true) -> depthImage = true
                    depthImage && n.equals("url", true) && chArt.isEmpty() -> chArt = text(parser)
                }
                XmlPullParser.END_TAG -> when {
                    n.equals("image", true) -> depthImage = false
                    n.equals("item", true) || n.equals("entry", true) -> {
                        inItem = false
                        if (audio.isNotBlank()) eps += FeedEpisode(
                            SubscriptionLogic.identity(guid, link, audio, title, pub), title.ifBlank { "Untitled Episode" }, audio,
                            parseDate(pub), parseDuration(dur), len,
                        )
                    }
                }
            }
            event = try { parser.next() } catch (ex: Exception) { if (!sawItem && chTitle.isEmpty()) throw ex else break }
        }
        if (!sawItem && chTitle.isEmpty()) throw IllegalArgumentException("not an RSS feed")
        return Feed(chTitle, chAuthor, chArt, eps.distinctBy { it.guid }.sortedByDescending { it.published })
    }

    private fun text(p: XmlPullParser) = runCatching { p.nextText().trim() }.getOrDefault("")
}

/** The saved subscriptions (filesDir/subscriptions.json), one process-wide lock around read-modify-write. */
object Subscriptions {
    private val lock = Any()
    private val _all = MutableStateFlow<List<Subscription>>(emptyList())
    val all: StateFlow<List<Subscription>> = _all
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "subscriptions.json")

    fun load(ctx: Context): List<Subscription> = synchronized(lock) {
        if (!loaded) {
            _all.value = runCatching {
                val a = JSONObject(file(ctx).readText()).getJSONArray("subscriptions")
                (0 until a.length()).mapNotNull { runCatching { Subscription.fromJson(a.getJSONObject(it)) }.getOrNull() }
            }.getOrDefault(emptyList())
            loaded = true
        }
        _all.value
    }

    /** Applies [f] to the list atomically and saves it. */
    fun update(ctx: Context, f: (List<Subscription>) -> List<Subscription>): List<Subscription> = synchronized(lock) {
        load(ctx)
        val next = f(_all.value)
        file(ctx).writeTextAtomic(JSONObject().put("version", 1).put("subscriptions", JSONArray(next.map { it.toJson() })).toString())
        _all.value = next
        next
    }

    fun byId(ctx: Context, id: String) = load(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, s: Subscription) = update(ctx) { l -> if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s }

    fun remove(ctx: Context, id: String) = update(ctx) { l -> l.filterNot { it.id == id } }

    /** Re-adds a removed subscription at its old position (snackbar Undo). */
    fun restore(ctx: Context, s: Subscription, index: Int) = update(ctx) { l -> if (l.any { it.id == s.id }) l else l.toMutableList().apply { add(index.coerceIn(0, size), s) } }

    internal fun resetForTest() = synchronized(lock) { loaded = false; _all.value = emptyList() }
}

/** Network side of a check: manual redirects, conditional GET, 2 MB cap. */
object FeedFetcher {
    sealed interface Result {
        data class Ok(val feed: Feed, val finalUrl: String, val moved: Boolean, val etag: String?, val lastModified: String?) : Result
        data class NotModified(val finalUrl: String, val moved: Boolean) : Result
        data class Failed(val error: CheckError) : Result
    }

    suspend fun fetch(feedUrl: String, etag: String? = null, lastModified: String? = null): Result = withContext(Dispatchers.IO) {
        if (!feedUrl.startsWith("http://") && !feedUrl.startsWith("https://")) return@withContext Result.Failed(CheckError.UNPARSABLE)
        var conn: HttpURLConnection? = null
        try {
            val hop = SubscriptionLogic.followRedirects(feedUrl) { u ->
                conn?.disconnect()
                val c = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "PrimeMinutes/1.0")
                    if (u == feedUrl) { etag?.let { setRequestProperty("If-None-Match", it) }; lastModified?.let { setRequestProperty("If-Modified-Since", it) } }
                }
                conn = c
                c.responseCode to c.getHeaderField("Location")
            } ?: return@withContext Result.Failed(CheckError.RETRY_LATER)
            val c = conn!!
            if (hop.status == 304) return@withContext Result.NotModified(hop.url, hop.movedPermanently)
            SubscriptionLogic.classify(hop.status)?.let { return@withContext Result.Failed(it) }
            val bytes = c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                while (true) { val r = s.read(buf); if (r < 0) break; out.write(buf, 0, r); if (out.size() > FeedParser.MAX_FEED_BYTES) break }
                out.toByteArray()
            }
            val feed = runCatching { FeedParser.parse(bytes.inputStream()) }.getOrElse { return@withContext Result.Failed(CheckError.UNPARSABLE) }
            if (feed.episodes.isEmpty()) return@withContext Result.Failed(CheckError.NO_AUDIO)
            Result.Ok(feed, hop.url, hop.movedPermanently, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"))
        } catch (e: java.net.UnknownHostException) { Result.Failed(CheckError.GONE)
        } catch (e: javax.net.ssl.SSLException) { Result.Failed(CheckError.GONE)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Result.Failed(CheckError.RETRY_LATER)
        } finally { conn?.disconnect() }
    }
}
