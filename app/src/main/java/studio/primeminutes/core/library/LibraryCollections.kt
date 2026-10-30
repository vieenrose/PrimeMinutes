package studio.primeminutes.core.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Library collections (spec: library collections + scoped search/Ask, 2026-10-30). One model, two kinds:
 * a CHANNEL (automatic, one per podcast series, from [Provenance.groupKey]) and a USER collection (made by
 * the user, a flat set of meetings). A meeting sits in one user collection at most (adding moves it);
 * podcast episodes stay in their channel; there is no nesting; deleting a collection keeps its meetings.
 *
 * Only the user collections are stored, in `filesDir/library_collections.json` next to the library
 * (atomic writes). No file (an older library) reads as no collection: nothing to migrate.
 */
object LibraryCollections {
    const val FILE = "library_collections.json"
    private const val VERSION = 1

    data class UserCollection(val id: String, val name: String, val createdAt: Long)

    /**
     * What is stored: the user collections, meeting id → collection id, the suggestion groups skipped
     * for good, and per channel the time its page was last opened (its "N new" badge counts after it).
     * [since]: when collections first ran here — a channel never opened counts its new items after it,
     * so an upgraded library does not light every channel up at once.
     */
    data class Doc(
        val collections: List<UserCollection> = emptyList(),
        val members: Map<String, String> = emptyMap(),
        val dismissed: Set<String> = emptySet(),
        val seen: Map<String, Long> = emptyMap(),
        val since: Long = 0,
    )

    enum class Kind { CHANNEL, USER }

    /** A collection as the library shows it: its key ("ch:<groupKey>" / "uc:<id>"), its items newest first. */
    data class Collection(val kind: Kind, val key: String, val name: String?, val items: List<SessionLibrary.Entry>) {
        val latest: Long get() = items.maxOfOrNull { it.createdAt } ?: 0
        val latestItem: SessionLibrary.Entry? get() = items.maxByOrNull { it.createdAt }
        val artwork: Provenance? get() = items.firstNotNullOfOrNull { it.source?.takeIf { s -> s.artworkUrl != null } }
        val totalSec: Int get() = items.sumOf { it.durationSec }
        /** Finished items added after the page was last opened (an unfinished episode is not "new" yet). */
        fun newCount(doc: Doc): Int = if (kind != Kind.CHANNEL) 0 else {
            val after = doc.seen[key] ?: doc.since
            items.count { it.status == SessionLibrary.Status.DONE && !it.noSpeech && it.createdAt > after }
        }
    }

    sealed class Row {
        abstract val date: Long
        data class Item(val entry: SessionLibrary.Entry) : Row() { override val date get() = entry.createdAt }
        data class Coll(val collection: Collection) : Row() { override val date get() = collection.latest }
    }

    fun channelKey(e: SessionLibrary.Entry): String? =
        if (e.category == Provenance.Category.PODCAST) "ch:" + Provenance.groupKey(e.source) else null

    /** The collection [e] belongs to: its channel (a podcast episode), its user collection, or null. */
    fun keyOf(e: SessionLibrary.Entry, doc: Doc): String? =
        channelKey(e) ?: doc.members[e.id]?.takeIf { id -> doc.collections.any { it.id == id } }?.let { "uc:$it" }

    /** Every collection with at least one item (the empty user collections too, so a new one shows), newest first. */
    fun build(entries: List<SessionLibrary.Entry>, doc: Doc): List<Collection> {
        val by = entries.groupBy { keyOf(it, doc) }
        val channels = by.filterKeys { it != null && it.startsWith("ch:") }.map { (k, v) ->
            Collection(Kind.CHANNEL, k!!, v.firstNotNullOfOrNull { it.source?.channelTitle }, sortItems(Kind.CHANNEL, v))
        }
        val users = doc.collections.map { c -> Collection(Kind.USER, "uc:${c.id}", c.name, sortItems(Kind.USER, by["uc:${c.id}"].orEmpty())) }
        return (channels + users).sortedWith(compareByDescending<Collection> { it.latest }.thenBy { it.key })
    }

    private fun sortItems(kind: Kind, v: List<SessionLibrary.Entry>) =
        if (kind == Kind.CHANNEL) v.sortedByDescending { it.source?.published?.takeIf { p -> p > 0 }?.times(1000) ?: it.createdAt }
        else v.sortedByDescending { it.createdAt }

    /**
     * The library's rows: [podcastsOnly] (the Podcasts filter) lists channel rows only; otherwise the
     * items outside any collection and the collection rows, mixed by their latest item's date. [keep]
     * narrows the items first (a collection with none left is not listed, an empty user collection is).
     */
    fun rows(entries: List<SessionLibrary.Entry>, doc: Doc, podcastsOnly: Boolean = false,
             keep: (SessionLibrary.Entry) -> Boolean = { true }): List<Row> {
        val kept = entries.filter(keep)
        val colls = build(kept, doc).filter { c ->
            (c.kind == Kind.CHANNEL || !podcastsOnly) && (c.items.isNotEmpty() || (c.kind == Kind.USER && kept.size == entries.size))
        }
        val loose = if (podcastsOnly) emptyList() else kept.filter { keyOf(it, doc) == null }.map { Row.Item(it) }
        return (loose + colls.map { Row.Coll(it) }).sortedByDescending { it.date }
    }

    /** The items of collection [key] among [entries]. */
    fun itemsOf(key: String, entries: List<SessionLibrary.Entry>, doc: Doc): List<SessionLibrary.Entry> =
        entries.filter { keyOf(it, doc) == key }

    fun nameOf(key: String, entries: List<SessionLibrary.Entry>, doc: Doc): String? =
        if (key.startsWith("uc:")) doc.collections.firstOrNull { "uc:${it.id}" == key }?.name
        else entries.firstNotNullOfOrNull { e -> e.source?.channelTitle?.takeIf { channelKey(e) == key } }

    fun kindOf(key: String) = if (key.startsWith("ch:")) Kind.CHANNEL else Kind.USER

    // --- Suggestions: meetings whose titles match once years, dates and numbers are removed ---

    private val DATE_PART = Regex("[0-9０-９]+\\s*[年月日号號]?")
    private val NOISE = Regex("[\\s\\p{Punct}~～—–·、，。：；！？（）【】「」『』《》〈〉＿－／]+")
    private val EXT = Regex("\\.[A-Za-z0-9]{2,4}$")

    /** A title without its years, dates, numbers, punctuation and case: "2024股東會錄音" → "股東會錄音". */
    fun normalizeTitle(title: String): String =
        title.replace(EXT, "").replace(DATE_PART, "").replace(NOISE, "").lowercase()

    data class Suggestion(val key: String, val name: String, val ids: List<String>)

    /**
     * The first group of 3 meetings or more (outside any collection, not podcast episodes) whose titles
     * are the same after [normalizeTitle], unless skipped before. No semantic matching.
     */
    fun suggestion(entries: List<SessionLibrary.Entry>, doc: Doc): Suggestion? =
        entries.asSequence()
            .filter { it.category != Provenance.Category.PODCAST && keyOf(it, doc) == null && !it.title.isNullOrBlank() }
            .groupBy { normalizeTitle(it.title!!) }
            .filter { (k, v) -> k.length >= 2 && v.size >= 3 && k !in doc.dismissed }
            .maxByOrNull { (_, v) -> v.maxOf { it.createdAt } }
            ?.let { (k, v) -> Suggestion(k, displayName(v.mapNotNull { it.title }), v.map { it.id }) }

    /** The group's shared name as written: the newest title without its numbers and date parts. */
    private fun displayName(titles: List<String>): String =
        titles.first().replace(EXT, "").replace(DATE_PART, " ").replace(Regex("^[\\s\\p{Punct}~～—–·、，。]+|[\\s\\p{Punct}~～—–·、，。]+$"), "")
            .replace(Regex("\\s{2,}"), " ").ifBlank { titles.first() }

    // --- Edits (pure: each returns the next document) ---

    fun create(doc: Doc, name: String, ids: List<String>, id: String, now: Long): Doc =
        move(doc.copy(collections = doc.collections + UserCollection(id, name.trim(), now)), ids, id)

    /** Puts [ids] in collection [id] (taking them out of any other: one collection per meeting). */
    fun move(doc: Doc, ids: List<String>, id: String): Doc = doc.copy(members = doc.members + ids.associateWith { id })

    fun removeItems(doc: Doc, ids: Iterable<String>): Doc = doc.copy(members = doc.members - ids.toSet())

    /** Deletes the collection; its meetings go back to the library. */
    fun delete(doc: Doc, id: String): Doc =
        doc.copy(collections = doc.collections.filter { it.id != id }, members = doc.members.filterValues { it != id })

    fun rename(doc: Doc, id: String, name: String): Doc =
        doc.copy(collections = doc.collections.map { if (it.id == id) it.copy(name = name.trim()) else it })

    fun dismiss(doc: Doc, groupKey: String): Doc = doc.copy(dismissed = doc.dismissed + groupKey)

    fun markSeen(doc: Doc, key: String, now: Long): Doc = doc.copy(seen = doc.seen + (key to now))

    // --- Scope: what search and Ask act on ---

    /**
     * Ask's scope levels (3 at most, the first is where it was opened): this collection → its source type → all;
     * a source filter → all; all alone. Each level: a label kind and its entries.
     */
    enum class Level { MEETING, CHANNEL, COLLECTION, PODCASTS, MEETINGS, RECEIVED, ALL }

    data class AskLevel(val level: Level, val entries: List<SessionLibrary.Entry>, val meeting: SessionLibrary.Entry? = null)

    fun categoryLevel(c: Provenance.Category) = when (c) {
        Provenance.Category.PODCAST -> Level.PODCASTS
        Provenance.Category.RECEIVED -> Level.RECEIVED
        Provenance.Category.MEETING -> Level.MEETINGS
    }

    fun askLevels(entries: List<SessionLibrary.Entry>, doc: Doc, collectionKey: String?, category: Provenance.Category?,
                  meeting: SessionLibrary.Entry? = null): List<AskLevel> {
        val out = mutableListOf<AskLevel>()
        if (meeting != null) out += AskLevel(Level.MEETING, listOf(meeting), meeting)
        val key = collectionKey ?: meeting?.let { keyOf(it, doc) }
        if (key != null) {
            val items = itemsOf(key, entries, doc)
            if (items.isNotEmpty() && meeting == null || items.size > 1) out += AskLevel(if (kindOf(key) == Kind.CHANNEL) Level.CHANNEL else Level.COLLECTION, items)
        }
        val cat = category ?: (if (key != null) (if (kindOf(key) == Kind.CHANNEL) Provenance.Category.PODCAST else Provenance.Category.MEETING) else meeting?.category)
        if (cat != null && out.size < 2) {
            val items = entries.filter { it.category == cat }
            if (items.size < entries.size && items.size > (out.lastOrNull()?.entries?.size ?: 0)) out += AskLevel(categoryLevel(cat), items)
        }
        if (out.lastOrNull()?.entries?.size != entries.size) out += AskLevel(Level.ALL, entries)
        return out.take(3)
    }

    // --- Codec ---

    fun encode(doc: Doc): String = JSONObject().put("version", VERSION).put("since", doc.since)
        .put("collections", JSONArray().apply {
            doc.collections.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt)) }
        })
        .put("members", JSONObject().apply { doc.members.forEach { (k, v) -> put(k, v) } })
        .put("dismissed", JSONArray().apply { doc.dismissed.sorted().forEach { put(it) } })
        .put("seen", JSONObject().apply { doc.seen.forEach { (k, v) -> put(k, v) } })
        .toString()

    /** Tolerant: an unreadable file or a missing field reads as empty (never throws). */
    fun decode(text: String?): Doc {
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Doc()
        val colls = o.optJSONArray("collections")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c ->
                val id = c.optString("id"); if (id.isBlank()) null else UserCollection(id, c.optString("name"), c.optLong("createdAt"))
            } }
        }.orEmpty()
        val ids = colls.map { it.id }.toSet()
        fun obj(name: String) = o.optJSONObject(name)?.let { m -> m.keys().asSequence().associateWith { m.get(it) } }.orEmpty()
        return Doc(
            collections = colls,
            members = obj("members").mapValues { it.value.toString() }.filterValues { it in ids },
            dismissed = o.optJSONArray("dismissed")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() }.orEmpty(),
            seen = obj("seen").mapValues { (it.value as? Number)?.toLong() ?: 0L },
            since = o.optLong("since"),
        )
    }

    // --- Store ---

    private val _doc = MutableStateFlow(Doc())
    val doc: StateFlow<Doc> = _doc
    @Volatile private var loadedFrom: File? = null

    fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    /** Reads the file once per process; a library that never had one starts its "new" counts now. */
    fun load(context: Context): Doc = synchronized(this) {
        val f = file(context)
        if (loadedFrom != f) {
            var d = decode(f.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() })
            if (d.since == 0L) d = d.copy(since = System.currentTimeMillis()).also { runCatching { f.writeTextAtomic(encode(it)) } }
            _doc.value = d; loadedFrom = f
        }
        _doc.value
    }

    fun update(context: Context, edit: (Doc) -> Doc): Doc = synchronized(this) {
        val next = edit(load(context))
        runCatching { file(context).writeTextAtomic(encode(next)) }
        _doc.value = next
        next
    }

    /** Tests: read the file again on the next [load]. */
    internal fun forget() = synchronized(this) { loadedFrom = null; _doc.value = Doc() }

    fun newId(now: Long = System.currentTimeMillis()) = "c" + now.toString(36) + (0..999).random().toString(36)

    /** Record / import from a collection page: the next new library item joins [id] (null: none). */
    @Volatile var target: String? = null

    /** A new library item was saved: it joins the armed collection, once. */
    fun claim(context: Context, entryId: String) {
        val t = target ?: return
        target = null
        runCatching { update(context) { d -> if (d.collections.any { it.id == t }) move(d, listOf(entryId), t) else d } }
    }
}
