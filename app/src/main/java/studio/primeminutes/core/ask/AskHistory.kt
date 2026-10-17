package studio.primeminutes.core.ask

import org.json.JSONArray
import org.json.JSONObject
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

/**
 * The questions answered by the in-meeting Ask, kept next to the meeting (`ask-history.json`, the last
 * [MAX]): the sheet shows them under "Earlier" when it opens again. Local only — not exported, deleted
 * with the meeting. The answer keeps its expanded citations (`[[entryId@sec]]`); [Item.cites] holds the
 * excerpts' times so the citations and the sources still resolve ([AskEngine.cite]).
 */
object AskHistory {
    const val FILE = "ask-history.json"
    const val MAX = 20

    /** [cites]: each excerpt of the answer as (whole second of its tag, exact start). */
    data class Item(val question: String, val answer: String, val at: Long, val cites: List<Pair<Int, Double>>)

    fun file(dir: File) = File(dir, FILE)

    fun encode(items: List<Item>): String = JSONObject().put("v", 1).put("items", JSONArray().apply {
        items.forEach { i ->
            put(JSONObject().put("q", i.question).put("a", i.answer).put("t", i.at).put("x", JSONArray().apply {
                i.cites.forEach { (sec, at) -> put(JSONArray().put(sec).put(at)) }
            }))
        }
    }).toString()

    /** Tolerant: a broken file or row is an empty history, not a crash. */
    fun decode(text: String): List<Item> = runCatching {
        val arr = JSONObject(text).optJSONArray("items") ?: return emptyList()
        (0 until arr.length()).mapNotNull { n ->
            val o = arr.optJSONObject(n) ?: return@mapNotNull null
            val q = o.optString("q").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val xs = o.optJSONArray("x")
            val cites = if (xs == null) emptyList() else (0 until xs.length()).mapNotNull { k ->
                xs.optJSONArray(k)?.let { it.optInt(0) to it.optDouble(1, it.optInt(0).toDouble()) }
            }
            Item(q, o.optString("a"), o.optLong("t"), cites)
        }
    }.getOrDefault(emptyList())

    fun load(dir: File): List<Item> = file(dir).takeIf { it.exists() }?.let { f -> runCatching { decode(f.readText()) }.getOrNull() } ?: emptyList()

    /** Appends [item], keeping the last [MAX]; atomic (tmp + rename). */
    @Synchronized fun append(dir: File, item: Item) {
        if (!dir.isDirectory) return
        val items = (load(dir) + item).takeLast(MAX)
        val tmp = File(dir, "$FILE.tmp")
        runCatching { tmp.writeText(encode(items)); if (!tmp.renameTo(file(dir))) tmp.delete() }
    }

    @Synchronized fun clear(dir: File) { file(dir).delete() }

    /** The saved questions as finished turns of [e] (excerpts without text: they only resolve citations). */
    fun turns(e: SessionLibrary.Entry, items: List<Item>): List<AskEngine.Turn> = items.map { i ->
        val xs = i.cites.map { (sec, at) -> AskEngine.Excerpt(e, sec, "", 0f, at) }
        AskEngine.Turn(i.question, i.answer, xs, done = true, pool = xs)
    }
}
