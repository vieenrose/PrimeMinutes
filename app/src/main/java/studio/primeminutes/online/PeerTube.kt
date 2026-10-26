package studio.primeminutes.online

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import studio.primeminutes.R

/**
 * PeerTube video source: a link from any instance resolves, through the instance's public REST API
 * (`GET /api/v1/videos/{id}`), to its smallest file (the audio-only one when there is one), which is then
 * downloaded like a podcast episode ([Podcast.downloadInto]).
 */
object PeerTube {
    data class Video(val title: String, val fileUrl: String, val seconds: Int)

    private val ID = Regex("^[A-Za-z0-9-]{8,40}$")

    /** (instance origin, video id) of `https://host/w/<id>`, `/videos/watch/<id>` or `/videos/embed/<id>`; null otherwise. */
    fun parse(text: String): Pair<String, String>? {
        var t = text.trim()
        if (!t.contains("://")) t = "https://$t"
        val u = runCatching { URI(t) }.getOrNull() ?: return null
        val host = u.host ?: return null
        if (u.scheme != "http" && u.scheme != "https") return null
        val parts = (u.path ?: "").split('/').filter { it.isNotEmpty() }
        val i = parts.indexOfFirst { it == "w" || it == "watch" || it == "embed" }
        if (i < 0 || i + 1 >= parts.size || (i > 0 && parts[i - 1] != "videos")) return null
        val id = parts[i + 1]
        if (!ID.matches(id)) return null
        return "${u.scheme}://$host${if (u.port > 0) ":${u.port}" else ""}" to id
    }

    /** A PeerTube link that is not YouTube's (a `/watch` path alone would also match youtube.com/watch). */
    fun looksLike(text: String): Boolean {
        val p = parse(text) ?: return false
        return !p.first.contains("youtube.") && !p.first.contains("youtu.be")
    }

    /** [ctx] localizes the failure messages, shown on the import item as "Failed · reason". */
    suspend fun resolve(ctx: Context, url: String): Video = withContext(Dispatchers.IO) {
        val (origin, id) = parse(url) ?: error(ctx.getString(R.string.err_peertube_bad_link))
        val conn = (URL("$origin/api/v1/videos/$id").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "PrimeMinutes/1.0")
        }
        val o = try {
            if (conn.responseCode >= 400) error(ctx.getString(R.string.err_peertube_http, conn.responseCode))
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
        if (o.optBoolean("isLive")) error(ctx.getString(R.string.err_peertube_live))
        val files = mutableListOf<JSONObject>()
        o.optJSONArray("files")?.let { a -> for (k in 0 until a.length()) files += a.getJSONObject(k) }
        o.optJSONArray("streamingPlaylists")?.let { pl ->
            for (p in 0 until pl.length()) pl.getJSONObject(p).optJSONArray("files")?.let { a -> for (k in 0 until a.length()) files += a.getJSONObject(k) }
        }
        // Resolution id 0 is an audio-only file; otherwise the lowest resolution (everything is resampled to 16 kHz mono anyway).
        val best = files.mapNotNull { f ->
            val u = f.optString("fileUrl").ifBlank { f.optString("fileDownloadUrl") }
            if (!u.startsWith("http") || u.endsWith(".m3u8")) null
            else (f.optJSONObject("resolution")?.optInt("id", Int.MAX_VALUE) ?: Int.MAX_VALUE) to u
        }.minByOrNull { it.first } ?: error(ctx.getString(R.string.err_peertube_no_file))
        Video(o.optString("name"), best.second, o.optInt("duration"))
    }
}
