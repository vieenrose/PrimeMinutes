package studio.primeminutes.core.share

import org.json.JSONArray
import org.json.JSONObject
import studio.primeminutes.core.people.EmailRules

/**
 * "Last shared" of one meeting (docs/design/speaker-contacts.md §4.6): `library/<id>/shared.json` on
 * Android, `Application Support/shared/<uuid>.json` on iOS, the same JSON. The last [KEEP] shares;
 * never exported, deleted with the meeting, and a deleted person is scrubbed from it ([scrub]).
 */
object ShareHistory {
    const val FILE = "shared.json"
    const val VERSION = 1
    const val KEEP = 10

    data class Recipient(val person: String?, val address: String)

    data class Content(val summary: Boolean, val minutes: Boolean, val only: List<String>?, val attach: List<String>)

    data class Share(
        /** Epoch seconds. */
        val at: Long,
        /** "email" | "share-sheet". */
        val channel: String,
        /** "opened" (Android: the mail app was opened, the app cannot know more) | iOS MFMail "sent" | "saved". */
        val status: String,
        val recipients: List<Recipient>,
        val content: Content,
        val lang: String,
    )

    fun encode(shares: List<Share>): String {
        val arr = JSONArray()
        for (s in shares.takeLast(KEEP)) arr.put(JSONObject().apply {
            put("at", s.at); put("channel", s.channel); put("status", s.status)
            put("recipients", JSONArray().apply {
                s.recipients.forEach { r -> put(JSONObject().apply { r.person?.let { put("person", it) }; put("address", r.address) }) }
            })
            put("content", JSONObject().apply {
                put("summary", s.content.summary); put("minutes", s.content.minutes)
                put("only", s.content.only?.let { JSONArray(it) } ?: JSONObject.NULL)
                put("attach", JSONArray(s.content.attach))
            })
            put("lang", s.lang)
        })
        return JSONObject().put("version", VERSION).put("shares", arr).toString()
    }

    fun decode(text: String?): List<Share> {
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val arr = o.optJSONArray("shares") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val rs = s.optJSONArray("recipients") ?: JSONArray()
            val c = s.optJSONObject("content") ?: JSONObject()
            Share(
                at = s.optLong("at"), channel = s.optString("channel", "email"), status = s.optString("status", "opened"),
                recipients = (0 until rs.length()).mapNotNull { j ->
                    rs.optJSONObject(j)?.let { r -> r.optString("address").ifBlank { null }?.let { Recipient(r.optString("person").ifBlank { null }, it) } }
                },
                content = Content(
                    c.optBoolean("summary", true), c.optBoolean("minutes", true),
                    c.optJSONArray("only")?.let { a -> (0 until a.length()).map { a.optString(it) } },
                    c.optJSONArray("attach")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                ),
                lang = s.optString("lang"),
            )
        }
    }

    fun add(shares: List<Share>, s: Share): List<Share> = (shares + s).takeLast(KEEP)

    /** A person was deleted: their id and their addresses go from every share ([addresses] normalised keys). */
    fun scrub(shares: List<Share>, person: String, addresses: Set<String>): List<Share> =
        shares.map { s -> s.copy(recipients = s.recipients.filterNot { it.person == person || EmailRules.key(it.address) in addresses }) }
}
