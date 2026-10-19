package studio.primeminutes.core.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An imported file's creation date, from its own metadata (MP4 `creation_time`, the date a phone recorder writes):
 * the meeting's start for the "Email participants" time line. Never the file's modified time or the import time,
 * which are not when the meeting happened: a file without a date gives null, and the time is then left out.
 */
object MediaDate {
    /** Epoch ms of [uri]'s media creation date, or null when it has none (or an implausible one). */
    fun captureStartMs(context: Context, uri: Uri, nowMs: Long = System.currentTimeMillis()): Long? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE), nowMs)
        } catch (t: Throwable) { null } finally { runCatching { mmr.release() } }
    }

    // "20261019T082812.000Z" (MediaMetadataRetriever's form of MP4 creation_time), or ISO 8601 "2026-10-19T08:28:12Z".
    private val COMPACT = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(?:\.\d+)?(Z)?$""")
    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""")

    /**
     * The metadata date [raw] as epoch ms. A date alone (an MP3's year tag) has no time of day and gives null, as
     * does an MP4 creation_time left at its zero (1904/1970) or one in the future. No zone: the device's.
     */
    fun parse(raw: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = raw?.trim().orEmpty()
        val ms = runCatching {
            COMPACT.matchEntire(t)?.destructured?.let { (y, mo, d, h, mi, s, z) ->
                val ldt = LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                (if (z.isNotEmpty()) ldt.toInstant(ZoneOffset.UTC) else ldt.atZone(zone).toInstant()).toEpochMilli()
            } ?: ISO.matchEntire(t)?.let { m ->
                val (y, mo, d, h, mi) = m.destructured
                val s = m.groupValues[6].ifEmpty { "0" }
                val z = m.groupValues[7]
                val ldt = LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                when {
                    z.isEmpty() -> ldt.atZone(zone).toInstant().toEpochMilli()
                    z == "Z" -> ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
                    else -> OffsetDateTime.of(ldt, ZoneOffset.of(if (z.length == 5) z.substring(0, 3) + ":" + z.substring(3) else z)).toInstant().toEpochMilli()
                }
            }
        }.getOrNull() ?: return null
        // 2000-01-01: an unset MP4 date reads 1904 or 1970; a date past now (+1 day of clock skew) is not a capture.
        return ms.takeIf { it >= 946_684_800_000L && it <= nowMs + 86_400_000L }
    }
}
