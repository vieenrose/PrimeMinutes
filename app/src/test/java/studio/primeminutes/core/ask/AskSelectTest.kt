package studio.primeminutes.core.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.embed.LibrarySearch
import studio.primeminutes.core.embed.SessionIndex
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

/** AskEngine.select and routeMeetings: seeds, overlap merging, widening by neighbour chunks within the budget, meeting routing. */
class AskSelectTest {
    private fun entry(id: String) = SessionLibrary.Entry(id, File("/tmp/$id"), id, 0L, 600, SessionLibrary.Status.DONE)
    private val a = entry("20261014-175615"); private val b = entry("20261014-175951")

    /** Utterance i of every meeting: "m<i>" said at 10·i s; chunks of 3 utterances overlapping by one (0..2, 2..4, ...). */
    private val src = object : AskEngine.Transcripts {
        override fun chunks(e: SessionLibrary.Entry) = (0 until 10).map { it * 2..it * 2 + 2 }
        override fun span(e: SessionLibrary.Entry, a: Int, b: Int) =
            AskEngine.Excerpt(e, a * 10, (a..b).joinToString(" ") { "m$it" }, 0f, a * 10.0, a = a, b = b)
    }
    private fun chunk(e: SessionLibrary.Entry, i: Int, score: Float) = src.span(e, i * 2, i * 2 + 2).copy(score = score)
    private fun note(e: SessionLibrary.Entry, sec: Int, text: String, score: Float) = AskEngine.Excerpt(e, sec, text, score, sec.toDouble())
    /** One token per word: "m4 m5 m6" costs 3 (+ the header). */
    private val words = { s: String -> s.split(' ').size }
    private val header = 24

    @Test fun defaultsKeepTheRankingWithinTheBudget() {
        val ranked = listOf(chunk(a, 3, 0.9f), chunk(b, 1, 0.8f), note(a, 50, "n1", 0.7f), note(b, 50, "n1", 0.6f))
        val p = AskEngine.Retrieval(maxExcerpts = 8, budgetTokens = 2 * (3 + header) + 1 + header)
        val xs = AskEngine.select(ranked, p, words, src)
        // Same text twice (a note in two imports) is kept once; the budget holds three.
        assertEquals(listOf(a.id to 60, b.id to 20, a.id to 50), xs.map { it.entry.id to it.sec })
    }

    @Test fun overlappingChunksOfAMeetingMerge() {
        val ranked = listOf(chunk(a, 3, 0.9f), chunk(a, 4, 0.8f), chunk(a, 3, 0.1f))
        val xs = AskEngine.select(ranked, AskEngine.Retrieval(), words, src)
        assertEquals(1, xs.size)
        assertEquals(6 to 10, xs[0].a to xs[0].b)
        assertEquals(60, xs[0].sec)   // the citation anchor is the first utterance of the merged passage
        assertEquals("m6 m7 m8 m9 m10", xs[0].text)
    }

    @Test fun widenAddsTheNextChunkThenThePreviousWhileTheBudgetLasts() {
        val ranked = listOf(chunk(a, 3, 0.9f), chunk(b, 6, 0.8f))
        // Room for both seeds widened forward (5 words each), then one backward widening (7 words).
        val p = AskEngine.Retrieval(seeds = 2, widen = 1, budgetTokens = 2 * header + 5 + 7)
        val xs = AskEngine.select(ranked, p, words, src)
        assertEquals(listOf(4 to 10, 12 to 16), xs.map { it.a to it.b })
        assertEquals(listOf(40, 120), xs.map { it.sec })
        assertTrue(xs.sumOf { words(it.text) + header } <= p.budgetTokens)
    }

    @Test fun widenMergesExcerptsThatMeet() {
        val ranked = listOf(chunk(a, 1, 0.9f), chunk(a, 3, 0.8f))   // 2..4 and 6..8, 5 between them
        val xs = AskEngine.select(ranked, AskEngine.Retrieval(seeds = 2, widen = 1), words, src)
        // Forward: 2..4 meets 4..6, which touches 6..8 → one 2..8 excerpt; backward: 0..2 → 0..8.
        assertEquals(1, xs.size)
        assertEquals(0 to 8, xs[0].a to xs[0].b)
    }

    @Test fun noWideningWithoutTranscripts() {
        val ranked = listOf(chunk(a, 3, 0.9f))
        assertEquals(listOf(6 to 8), AskEngine.select(ranked, AskEngine.Retrieval(widen = 2), words, null).map { it.a to it.b })
    }

    private fun hit(e: SessionLibrary.Entry, vararg scores: Float) = LibrarySearch.Hit(e, scores.max(),
        scores.mapIndexed { i, sc -> LibrarySearch.Passage(SessionIndex.Kind.CHUNK, i, i, i * 10.0, null, "p$i", false, sc) })

    @Test fun meetingsRankedByTheirBestPassagesInBothLists() {
        val c = entry("20261014-180000")
        // a: one strong passage; b: three good ones (the better meeting on dense); c: best on keywords.
        val dense = listOf(hit(a, 0.9f, 0.5f, 0.5f), hit(b, 0.8f, 0.8f, 0.8f), hit(c, 0.6f))
        val kw = listOf(hit(c, 9f, 8f), hit(b, 6f, 5f, 4f), hit(a, 3f))
        assertEquals(setOf(b.id), AskEngine.routeMeetings(dense, kw, 1))   // b: 1st on dense, 2nd on keywords
        assertEquals(setOf(b.id, c.id), AskEngine.routeMeetings(dense, kw, 2))
        assertEquals(emptySet<String>(), AskEngine.routeMeetings(dense, kw, 0))
        // A meeting whose only dense passage is its summary is ranked by keywords alone.
        val summaryOnly = listOf(LibrarySearch.Hit(a, 0.9f, listOf(LibrarySearch.Passage(SessionIndex.Kind.MEETING, -1, -1, 0.0, null, "t", true, 0.9f))))
        assertEquals(setOf(c.id), AskEngine.routeMeetings(summaryOnly, kw, 1))
    }

    @Test fun repeatedCitationsCollapse() {
        val x = "[[20261014-175615@879]]"; val y = "[[20261014-175951@12]]"
        assertEquals("罰款兩百元$x。", AskEngine.dedupeTags("罰款兩百元$x, $x。"))
        assertEquals("A $x, $y.", AskEngine.dedupeTags("A $x, $y, $x."))
        assertEquals("A $x. B $x.", AskEngine.dedupeTags("A $x. B $x."))   // two claims, one source each: kept
        val pool = listOf(AskEngine.Excerpt(a, 879, "t", 0f, k = 3))
        assertEquals("A $x", AskEngine.expandTags("A [[3]][[3]]", pool))
    }
}
