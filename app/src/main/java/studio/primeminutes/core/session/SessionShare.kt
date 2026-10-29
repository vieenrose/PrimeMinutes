package studio.primeminutes.core.session

import org.json.JSONArray
import org.json.JSONObject
import studio.primeminutes.core.audio.Mp4Tags
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A meeting shared between devices (AirDrop, Quick Share, any file transfer): the session manifest
 * as it leaves the app, and the checks a received one goes through. Pure JVM (org.json + java.util),
 * so the JVM tests read the same golden files as the iOS host tests (`ios/tests/golden_share`).
 *
 * Manifest v2 adds to v1:
 *  - `primeminutes_version` 2: the schema MAJOR. A reader refuses a newer major ([gate]) and imports
 *    the file as plain audio; unknown keys of the same major are ignored.
 *  - `session_id`: the meeting's identity across devices (a UUID string) — a re-import of the same
 *    meeting is detected by it.
 *  - `created_at`: when the meeting took place, ISO-8601 UTC (`2026-10-29T08:15:00Z`).
 *  - `user_edited`: `{summary, lines}` — what the user hand-edited, so a received meeting keeps its
 *    re-run protection (re-summarize / re-transcribe ask first).
 *  - `script`: the sender's Han script setting (`zh-Hant` / `zh-Hans`); the receiver converts the
 *    text to its own setting anyway (span-gated OpenCC), so this is informative.
 *
 * What leaves the app is an ALLOWLIST ([forShare]): a key not listed here never travels, so a
 * future private field (people links, voiceprints, Ask history) cannot leak by default. Speaker
 * names travel as plain labels, without the people directory id (`person`).
 */
object SessionShare {
    /** The newest manifest major this app reads. */
    const val SCHEMA_MAJOR = 2

    private const val MAX_JSON_BYTES = 16 * 1024 * 1024
    private const val MAX_BLOB_CHARS = 32 * 1024 * 1024

    enum class Gate { OK, TOO_NEW }

    /** The identity a shared copy carries (see the class doc); null fields are left out. */
    data class Meta(
        val sessionId: String?, val createdAtMs: Long?, val script: String?,
        /** What the user hand-edited ([studio.primeminutes.core.library.UserEdits]): summary, lines. */
        val userEdited: Pair<Boolean, Boolean>? = null,
    )

    /** Whether this app can restore [m]: a newer major (written by a newer app) is not guessed at. */
    fun gate(m: JSONObject): Gate = if (m.optInt("primeminutes_version", 1) > SCHEMA_MAJOR) Gate.TOO_NEW else Gate.OK

    /** Top-level keys a shared manifest may carry. */
    private val TOP = listOf(
        "primeminutes_version", "session_id", "created_at", "script",
        "title", "title_user_set", "summary", "action_items", "notes",
        "asr_model", "asr_backend", "llm_model",
    )
    private val SPEAKER = listOf("name", "confidence", "reason")
    private val UTTERANCE = listOf("index", "start", "end", "text", "speaker", "tokens", "token_times")

    /**
     * The manifest [m] as it may leave the device: allowlisted keys only, speaker names without their
     * person link, plus the v2 identity ([sessionId], [createdAtMs], [script]).
     */
    fun forShare(m: JSONObject, sessionId: String?, createdAtMs: Long?, script: String?, userEdited: Pair<Boolean, Boolean>? = null): JSONObject {
        val out = JSONObject()
        for (k in TOP) if (m.has(k) && !m.isNull(k)) out.put(k, m.get(k))
        (userEdited ?: userEdited(m))?.let { (sum, lines) -> out.put("user_edited", JSONObject().put("summary", sum).put("lines", lines)) }
        out.put("primeminutes_version", SCHEMA_MAJOR)
        sessionId?.let { out.put("session_id", it) }
        createdAtMs?.takeIf { it > 0 }?.let { out.put("created_at", iso(it)) }
        script?.let { out.put("script", it) }
        val names = JSONObject()
        m.optJSONObject("speaker_names")?.let { sn ->
            for (k in sn.keys()) {
                val o = sn.optJSONObject(k) ?: continue
                val c = JSONObject()
                for (f in SPEAKER) if (o.has(f) && !o.isNull(f)) c.put(f, o.get(f))
                if (c.optString("name").isNotEmpty()) names.put(k, c)
            }
        }
        out.put("speaker_names", names)
        val arr = JSONArray()
        m.optJSONArray("utterances")?.let { us ->
            for (i in 0 until us.length()) {
                val u = us.optJSONObject(i) ?: continue
                val c = JSONObject()
                for (f in UTTERANCE) if (u.has(f) && !u.isNull(f)) c.put(f, u.get(f))
                arr.put(c)
            }
        }
        out.put("utterances", arr)
        return out
    }

    /** `user_edited` as (summary, lines), or null when absent. */
    fun userEdited(m: JSONObject): Pair<Boolean, Boolean>? =
        m.optJSONObject("user_edited")?.let { it.optBoolean("summary") to it.optBoolean("lines") }

    /** The sender's meeting id, or null (a v1 file, or one saved outside the library). */
    fun sessionId(m: JSONObject): String? = m.optString("session_id", "").trim().ifEmpty { null }

    /** When the meeting took place (epoch ms), or null. */
    fun createdAtMs(m: JSONObject): Long? = m.optString("created_at", "").ifEmpty { null }?.let { parseIso(it) }

    fun iso(ms: Long): String = isoFormat().format(Date(ms))

    fun parseIso(s: String): Long? = runCatching { isoFormat().parse(s)?.time }.getOrNull()
        ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /**
     * [m] with its Chinese text in the receiver's script: [transcript] for what was said (character
     * level), [generated] for the title, summary, notes, action items and speaker names. Both are the
     * span-gated OpenCC converters, so text already in the target script is left alone. A copy;
     * [m] is not changed.
     */
    fun convertScript(m: JSONObject, generated: (String) -> String, transcript: (String) -> String): JSONObject {
        val out = JSONObject(m.toString())
        for (k in listOf("title", "summary", "action_items", "notes")) {
            if (out.has(k) && !out.isNull(k)) out.put(k, generated(out.getString(k)))
        }
        out.optJSONObject("speaker_names")?.let { sn ->
            for (k in sn.keys()) sn.optJSONObject(k)?.let { o -> if (o.has("name")) o.put("name", generated(o.optString("name"))) }
        }
        out.optJSONArray("utterances")?.let { us ->
            for (i in 0 until us.length()) us.optJSONObject(i)?.let { u ->
                if (u.has("text")) u.put("text", transcript(u.optString("text")))
                // Per-token text follows its line (the diarization split re-joins it).
                u.optJSONArray("tokens")?.let { t -> u.put("tokens", JSONArray(List(t.length()) { j -> transcript(t.optString(j)) })) }
            }
        }
        return out
    }

    /** gzip + base64 (NO_WRAP), the PRIMEMINUTES tag value. */
    fun encodeBlob(m: JSONObject): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(m.toString().toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    /** Inverse of [encodeBlob], bounded (a hostile file cannot inflate past 16 MB); null when unreadable. */
    fun decodeBlob(blob: String): JSONObject? = runCatching {
        if (blob.length > MAX_BLOB_CHARS) return null
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        GZIPInputStream(Base64.getMimeDecoder().decode(blob).inputStream()).use { gz ->
            var total = 0
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_JSON_BYTES) return null
                out.write(buf, 0, n)
            }
        }
        JSONObject(out.toString(Charsets.UTF_8.name()))
    }.getOrNull()

    /** The manifest embedded in an m4a (PRIMEMINUTES, or the legacy VOXSUM tag), or null. */
    fun readManifest(file: File): JSONObject? = Mp4Tags.readSessionBlob(file)?.let(::decodeBlob)
}
