package studio.primeminutes.online

import java.net.URI

/**
 * YouTube and PeerTube links. Their import was removed (0.55): a link shared to the app is
 * recognised only to explain that it is not imported, never fetched.
 */
object VideoLinks {
    private val URL = Regex("""(?:https?://)?[^\s/]+\.[^\s/]+/\S*""", RegexOption.IGNORE_CASE)

    /** True when [text] holds a YouTube link, or a PeerTube video page (`/w/<id>`, `/videos/watch/<id>`, `/videos/embed/<id>`). */
    fun isVideoLink(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return URL.findAll(text).any { m -> classify(m.value) }
    }

    private fun classify(raw: String): Boolean {
        val t = if (raw.contains("://")) raw else "https://$raw"
        val u = runCatching { URI(t.trimEnd('.', ',', ')', '"', '\'')) }.getOrNull() ?: return false
        if (u.scheme != "http" && u.scheme != "https") return false
        val host = u.host?.lowercase() ?: return false
        if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")) return true
        val parts = (u.path ?: "").split('/').filter { it.isNotEmpty() }
        val i = parts.indexOfFirst { it == "w" || it == "watch" || it == "embed" }
        if (i < 0 || i + 1 >= parts.size || (i > 0 && parts[i - 1] != "videos")) return false
        return Regex("^[A-Za-z0-9-]{8,40}$").matches(parts[i + 1])
    }
}
