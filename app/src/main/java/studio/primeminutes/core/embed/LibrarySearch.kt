package studio.primeminutes.core.embed

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File
import kotlin.math.ln
import kotlin.math.max

/**
 * Hybrid search over the library: lexical (title, summary, notes of every meeting; the utterances
 * of the open one) fused with the dense index of each meeting ([SessionIndex]) by reciprocal rank
 * fusion per passage. A meeting's score is its best passage plus a small bonus for the number of
 * hits; at most [MAX_PASSAGES] passages per meeting. Passages found only by meaning carry
 * [Passage.semanticOnly], shown as "≈" in the list.
 *
 * The caches are keyed by file modification time: the doc cache holds the searchable text of each
 * meeting (built lazily, in the background), the vector cache its int8 vectors.
 */
object LibrarySearch {
    const val MAX_PASSAGES = 3
    const val MIN_COS = 0.45f
    private const val RRF_K = 60f
    private const val LEX_WEIGHT = 0.3f   // T7: dense + 0.3·lexical

    data class Passage(
        val kind: SessionIndex.Kind,
        /** CHUNK: first utterance index; NOTE: note line; MEETING: -1. */
        val uttIndex: Int,
        val uttEnd: Int,
        val timeSec: Double,
        /** The speaker's custom name, or null (the UI then shows its localized "Speaker N" from [speakerId]). */
        val speaker: String?,
        val snippet: String,
        val semanticOnly: Boolean,
        val score: Float,
        val speakerId: Int? = null,
    )

    data class Hit(val entry: SessionLibrary.Entry, val score: Float, val passages: List<Passage>)

    /** The searchable text of one meeting. */
    private class Doc(
        val entry: SessionLibrary.Entry,
        val modified: Long,
        val title: String,
        val summary: String,
        val noteLines: List<String>,
        val loaded: PrimeMinutesSession.Loaded?,
    )

    private val docs = HashMap<String, Doc>()
    private val vectors = HashMap<String, Pair<Long, SessionIndex.Index?>>()
    /** Keyword units of a meeting (passage + BM25 doc), valid for the session and index mtimes and snippet size. */
    private class KwUnits(val key: Triple<Long, Long, Int>, val units: List<Pair<Passage, Keywords.Doc>>)
    private val keywords = HashMap<String, KwUnits>()
    @Volatile private var cc: OpenCcConverter? = null

    private fun norm(context: Context, s: String): String {
        val c = cc ?: runCatching { OpenCcConverter.get(context, ChineseScript.SIMPLIFIED) }.getOrNull()?.also { cc = it }
        val t = s.lowercase().trim()
        return c?.convert(t) ?: t
    }

    /** Drops cache rows of meetings that no longer exist and loads the new ones' text. */
    suspend fun refresh(context: Context, entries: List<SessionLibrary.Entry>) = withContext(Dispatchers.IO) {
        val ids = entries.map { it.id }.toSet()
        synchronized(docs) { docs.keys.retainAll(ids) }
        synchronized(vectors) { vectors.keys.retainAll(ids) }
        synchronized(keywords) { keywords.keys.retainAll(ids) }
        for (e in entries) doc(context, e)
    }

    private suspend fun doc(context: Context, e: SessionLibrary.Entry): Doc {
        val fileMod = e.sessionFile.takeIf { it.exists() }?.lastModified() ?: 0L
        // The names come from the speaker sidecar and the people directory too: a change there is a new doc.
        val mod = if (fileMod == 0L) 0L else fileMod * 31 + studio.primeminutes.core.people.PeopleStore.version.value
        synchronized(docs) { docs[e.id]?.takeIf { it.modified == mod }?.let { return it } }
        val loaded = if (mod != 0L) PrimeMinutesSession.peek(e.sessionFile) else null
        val d = Doc(
            e, mod,
            title = e.title ?: loaded?.title ?: "",
            summary = loaded?.summary ?: "",
            noteLines = loaded?.notes?.lines()?.map { SessionIndex.noteText(it) } ?: emptyList(),
            loaded = loaded,
        )
        synchronized(docs) { docs[e.id] = d }
        return d
    }

    private fun index(e: SessionLibrary.Entry): SessionIndex.Index? {
        val f = SessionIndex.file(e.dir)
        val mod = if (f.exists()) f.lastModified() else 0L
        synchronized(vectors) { vectors[e.id]?.takeIf { it.first == mod }?.let { return it.second } }
        val idx = if (mod != 0L) SessionIndex.read(e.dir) else null
        synchronized(vectors) { vectors[e.id] = mod to idx }
        return idx
    }

    /** Lexical pass: substring matches on the normalized text. [openId]'s utterances are searched too. */
    suspend fun lexical(context: Context, entries: List<SessionLibrary.Entry>, query: String, openId: String? = null): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = norm(context, query)
            if (q.isBlank()) return@withContext emptyList()
            val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
            val out = ArrayList<Hit>()
            for (e in entries) {
                val d = doc(context, e)
                val ps = ArrayList<Passage>()
                fun hits(text: String): Int = terms.count { norm(context, text).contains(it) }
                val tHits = hits(d.title)
                if (tHits == terms.size && d.title.isNotBlank())
                    ps += Passage(SessionIndex.Kind.MEETING, -1, -1, 0.0, null, d.title, false, 1f)
                d.summary.lines().forEach { line ->
                    if (line.isNotBlank() && hits(line) == terms.size)
                        ps += Passage(SessionIndex.Kind.MEETING, -1, -1, 0.0, null, line.trim(), false, 0.9f)
                }
                d.noteLines.forEachIndexed { i, line ->
                    if (line.isNotBlank() && hits(line) == terms.size)
                        ps += Passage(SessionIndex.Kind.NOTE, i, i, noteTime(d, i), null, line, false, 0.9f)
                }
                if (e.id == openId) d.loaded?.let { s ->
                    s.utterances.forEach { u ->
                        if (hits(u.text) == terms.size)
                            ps += Passage(SessionIndex.Kind.CHUNK, u.index, u.index, u.startSec, u.speaker?.let { s.speakerNames[it]?.name }, u.text, false, 0.8f, u.speaker)
                    }
                }
                if (ps.isNotEmpty()) out += Hit(e, ps.maxOf { it.score }, ps.sortedByDescending { it.score }.take(MAX_PASSAGES))
            }
            out.sortedByDescending { it.score }
        }

    /** Dense pass over every meeting's index with the query vector [qv]. */
    /** [maxPassages] per meeting and [snippetChars] per chunk: the Studio list shows 3 short ones,
     *  Ask ([studio.primeminutes.core.ask.AskEngine]) wants whole chunks. */
    suspend fun dense(
        context: Context, entries: List<SessionLibrary.Entry>, qv: FloatArray,
        maxPassages: Int = MAX_PASSAGES, snippetChars: Int = 200,
    ): List<Hit> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Hit>()
            for (e in entries) {
                val idx = index(e) ?: continue
                val scored = idx.vectors.map { v -> v to SessionIndex.dot(v.q, qv) }.filter { it.second >= MIN_COS }
                if (scored.isEmpty()) continue
                val d = doc(context, e)
                // One MEETING passage at most: a long summary has several parts, which would fill every
                // slot and then fold into one title row, leaving the meeting with no passage to show.
                val best = scored.sortedByDescending { it.second }
                val firstMeeting = best.indexOfFirst { it.first.kind == SessionIndex.Kind.MEETING }
                val ps = best.filterIndexed { i, (v, _) -> v.kind != SessionIndex.Kind.MEETING || i == firstMeeting }
                    .take(maxPassages).map { (v, cos) -> passage(d, v, cos, snippetChars) }
                out += Hit(e, ps.maxOf { it.score }, ps)
            }
            out.sortedByDescending { it.score }
        }

    /**
     * Keyword pass for Ask: BM25 ([Keywords]) over the indexed notes and chunks of every meeting,
     * normalized like [lexical] (lower case, Simplified script). [maxPassages] best per meeting.
     */
    suspend fun keyword(
        context: Context, entries: List<SessionLibrary.Entry>, query: String,
        maxPassages: Int = MAX_PASSAGES, snippetChars: Int = 200,
    ): List<Hit> = withContext(Dispatchers.IO) {
        val table = OpenCcConverter.simplifiedChars(context)
        val units = ArrayList<Pair<SessionLibrary.Entry, Pair<Passage, Keywords.Doc>>>()
        for (e in entries) keywordUnits(context, e, snippetChars, table)?.forEach { units += e to it }
        val scores = Keywords.score(Keywords.fold(query, table), units.map { it.second.second })
        units.indices.filter { scores[it] > 0f }
            .groupBy { units[it].first.id }
            .map { (_, ix) ->
                val ps = ix.sortedByDescending { scores[it] }.take(maxPassages).map { units[it].second.first.copy(semanticOnly = false, score = scores[it]) }
                Hit(units[ix.first()].first, ps.first().score, ps)
            }
            .sortedByDescending { it.score }
    }

    /** A meeting's indexed notes and chunks, folded and reduced to BM25 docs once per version. */
    private suspend fun keywordUnits(context: Context, e: SessionLibrary.Entry, snippetChars: Int, table: CharArray): List<Pair<Passage, Keywords.Doc>>? {
        val idx = index(e) ?: return null
        val d = doc(context, e)
        val f = SessionIndex.file(e.dir)
        val key = Triple(d.modified, if (f.exists()) f.lastModified() else 0L, snippetChars)
        synchronized(keywords) { keywords[e.id]?.takeIf { it.key == key }?.let { return it.units } }
        val units = idx.vectors.filter { it.kind != SessionIndex.Kind.MEETING }
            .map { v -> passage(d, v, 0f, snippetChars).let { p -> p to Keywords.doc(Keywords.fold(p.snippet, table)) } }
        synchronized(keywords) { keywords[e.id] = KwUnits(key, units) }
        return units
    }

    private fun passage(d: Doc, v: SessionIndex.Vector, cos: Float, snippetChars: Int = 200): Passage = when (v.kind) {
        SessionIndex.Kind.MEETING -> Passage(v.kind, -1, -1, 0.0, null, d.title.ifBlank { d.summary.lineSequence().firstOrNull() ?: "" }, true, cos)
        SessionIndex.Kind.NOTE -> Passage(v.kind, v.a, v.a, noteTime(d, v.a), null, d.noteLines.getOrNull(v.a) ?: "", true, cos)
        SessionIndex.Kind.CHUNK -> {
            val s = d.loaded; val u = s?.utterances?.getOrNull(v.a)
            val text = s?.utterances?.subList(v.a, minOf(v.b + 1, s.utterances.size))?.joinToString(" ") { it.text } ?: ""
            Passage(v.kind, v.a, v.b, u?.startSec ?: 0.0, u?.speaker?.let { s?.speakerNames?.get(it)?.name }, text.take(snippetChars), true, cos, u?.speaker)
        }
    }

    /** A meeting's transcript chunks as indexed, in order (utterance ranges): Ask widens a hit with its neighbours. */
    fun chunkRanges(e: SessionLibrary.Entry): List<IntRange> =
        index(e)?.vectors?.filter { it.kind == SessionIndex.Kind.CHUNK }?.map { it.a..it.b }?.sortedBy { it.first } ?: emptyList()

    /** A meeting's loaded session as cached by [refresh] (null before it). */
    private fun cached(e: SessionLibrary.Entry): PrimeMinutesSession.Loaded? = synchronized(docs) { docs[e.id]?.loaded }

    /** The utterances [a]..[b] of a meeting as one passage, joined like a chunk's snippet (Ask's widened
     *  excerpts; the meeting's text as loaded by [refresh]). */
    fun span(e: SessionLibrary.Entry, a: Int, b: Int): Passage? {
        val s = cached(e) ?: return null
        if (a < 0 || a > b || a >= s.utterances.size) return null
        val us = s.utterances.subList(a, minOf(b + 1, s.utterances.size))
        val u = us.first()
        return Passage(SessionIndex.Kind.CHUNK, a, a + us.size - 1, u.startSec, u.speaker?.let { s.speakerNames[it]?.name },
            us.joinToString(" ") { it.text }, false, 0f, u.speaker)
    }

    private fun noteTime(d: Doc, line: Int): Double {
        val raw = d.loaded?.notes?.lines()?.getOrNull(line) ?: return 0.0
        return Regex("\\[(\\d+):(\\d{2})(?::(\\d{2}))?\\]").find(raw)?.let { m ->
            val (a, b, c) = m.destructured
            if (c.isEmpty()) a.toInt() * 60.0 + b.toInt() else a.toInt() * 3600.0 + b.toInt() * 60 + c.toInt()
        } ?: 0.0
    }

    /**
     * Reciprocal-rank fusion of the lexical and dense lists, per passage, then per meeting:
     * score = max(passages) + 0.1·ln(n). A passage present in both lists keeps the lexical snippet
     * and loses its "≈" marker.
     */
    fun fuse(lex: List<Hit>, dense: List<Hit>): List<Hit> {
        if (dense.isEmpty()) return lex
        data class Key(val id: String, val kind: SessionIndex.Kind, val a: Int)
        val score = LinkedHashMap<Key, Float>()
        val best = HashMap<Key, Passage>()
        val entries = HashMap<String, SessionLibrary.Entry>()
        fun add(hits: List<Hit>, w: Float, semantic: Boolean) {
            var rank = 0
            for (h in hits) {
                entries[h.entry.id] = h.entry
                for (p in h.passages) {
                    rank++
                    val k = Key(h.entry.id, p.kind, p.uttIndex)
                    score[k] = (score[k] ?: 0f) + w / (RRF_K + rank)
                    val cur = best[k]
                    if (cur == null || (!semantic && cur.semanticOnly)) best[k] = if (semantic) p else p.copy(semanticOnly = false)
                    else if (semantic && cur.semanticOnly.not()) best[k] = cur.copy(semanticOnly = false)
                }
            }
        }
        add(dense, 1f, true)
        add(lex, LEX_WEIGHT, false)
        val byMeeting = score.entries.groupBy { it.key.id }
        return byMeeting.map { (id, ps) ->
            val sorted = ps.sortedByDescending { it.value }
            val top = sorted.take(MAX_PASSAGES).map { best.getValue(it.key).copy(score = it.value) }
            Hit(entries.getValue(id), sorted.first().value + 0.1f * ln(max(1, ps.size).toFloat()), top)
        }.sortedByDescending { it.score }
    }

    /** The index file of a meeting (for tests and the import path). */
    fun indexFile(dir: File): File = SessionIndex.file(dir)
}
