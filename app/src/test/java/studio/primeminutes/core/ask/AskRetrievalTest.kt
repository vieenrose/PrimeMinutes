package studio.primeminutes.core.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.embed.Keywords
import studio.primeminutes.core.embed.LibrarySearch
import studio.primeminutes.core.embed.SessionIndex
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

class AskRetrievalTest {
    private fun entry(id: String) = SessionLibrary.Entry(id, File("/tmp/$id"), id, 0L, 600, SessionLibrary.Status.DONE)
    private val a = entry("20261014-175615"); private val b = entry("20261014-175951")
    private fun p(kind: SessionIndex.Kind, i: Int, t: Double, score: Float, text: String = "p$i") =
        LibrarySearch.Passage(kind, i, i, t, null, text, false, score)

    @Test fun cjkBigramsAndWords() {
        assertEquals(listOf("頭獎", "獎是", "iphone", "16"), Keywords.terms("頭獎是 iPhone 16".lowercase()))
        assertEquals(listOf("車"), Keywords.terms("車"))
    }

    @Test fun docCountsTermsByCode() {
        val d = Keywords.doc("禮券禮券 iphone 16")
        assertEquals(5, d.length)   // 禮券 券禮 禮券 iphone 16
        assertEquals(2, d.tf(Keywords.code("禮券")))
        assertEquals(1, d.tf(Keywords.code("券禮")))
        assertEquals(1, d.tf(Keywords.code("16")))
        assertEquals(0, d.tf(Keywords.code("手機")))
    }

    @Test fun foldLowersAndMapsCharacters() {
        val table = CharArray(65536) { it.toChar() }.also { it['禮'.code] = '礼' }
        assertEquals("礼券 iphone", Keywords.fold("禮券 iPhone", table))
    }

    @Test fun bm25RanksTheRareTermFirst() {
        val docs = listOf("會議討論預算", "會議討論禮券五百元", "會議討論場地")
        val s = Keywords.bm25("禮券多少", docs)
        assertTrue(s[1] > 0f); assertEquals(0f, s[0]); assertEquals(0f, s[2])
    }

    @Test fun fusionAddsKeywordOnlyPassagesAndRewardsAgreement() {
        val dense = listOf(LibrarySearch.Hit(a, 0.8f, listOf(p(SessionIndex.Kind.CHUNK, 3, 30.0, 0.8f), p(SessionIndex.Kind.CHUNK, 9, 90.0, 0.7f))))
        val kw = listOf(LibrarySearch.Hit(b, 5f, listOf(p(SessionIndex.Kind.CHUNK, 1, 10.0, 5f))),
            LibrarySearch.Hit(a, 4f, listOf(p(SessionIndex.Kind.CHUNK, 9, 90.0, 4f))))
        val xs = AskEngine.fuse(dense, kw)
        assertEquals(listOf(a.id to 90, a.id to 30, b.id to 10), xs.map { it.entry.id to it.sec })
    }

    @Test fun denseOnlyKeepsCosineOrderWithNoteBonus() {
        val dense = listOf(LibrarySearch.Hit(a, 0.8f, listOf(p(SessionIndex.Kind.CHUNK, 3, 30.0, 0.80f), p(SessionIndex.Kind.NOTE, 0, 31.0, 0.78f),
            p(SessionIndex.Kind.MEETING, -1, 0.0, 0.9f))))
        assertEquals(listOf(31, 30), AskEngine.fuse(dense, emptyList()).map { it.sec })
    }

    @Test fun aNamedPersonsLinesGetTheNoteBonus() {
        // "What did Mark say": chunk 30 s is speaker 1 (Mark) of meeting a, slightly below chunk 20 s.
        val dense = listOf(LibrarySearch.Hit(a, 0.8f, listOf(
            LibrarySearch.Passage(SessionIndex.Kind.CHUNK, 2, 2, 20.0, null, "x", false, 0.80f, speakerId = 0),
            LibrarySearch.Passage(SessionIndex.Kind.CHUNK, 3, 3, 30.0, "Mark", "y", false, 0.78f, speakerId = 1))))
        assertEquals(listOf(20, 30), AskEngine.fuse(dense, emptyList()).map { it.sec })
        assertEquals(listOf(30, 20), AskEngine.fuse(dense, emptyList()) { id, sid -> id == a.id && sid == 1 }.map { it.sec })
    }

    @Test fun shortTagsExpandAcrossTheConversation() {
        val pool = listOf(AskEngine.Excerpt(a, 120, "x", 0f, k = 1), AskEngine.Excerpt(b, 45, "y", 0f, k = 9))
        assertEquals("頭獎是手機[[${a.id}@120]]，禮券[[${b.id}@45]] [[7]]", AskEngine.expandTags("頭獎是手機[[1]]，禮券[9] [[7]]", pool))
    }

    @Test fun followUpNeedsRoomForAWholeAnswer() {
        assertTrue(AskEngine.followUpFits(2000, 1500, 320, 4096))
        assertFalse(AskEngine.followUpFits(2400, 1500, 320, 4096))
    }
}
