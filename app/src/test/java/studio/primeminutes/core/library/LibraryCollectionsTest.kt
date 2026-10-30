package studio.primeminutes.core.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.library.LibraryCollections as LC
import java.io.File

/** Library collections: grouping and order, scope (search + Ask), suggestions, and the file. */
@RunWith(RobolectricTestRunner::class)
class LibraryCollectionsTest {
    private lateinit var ctx: Context

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext(); LC.file(ctx).delete(); LC.forget(); LC.target = null }
    @After fun tearDown() { LC.file(ctx).delete(); LC.forget(); SessionLibrary.root(ctx).deleteRecursively() }

    private fun meeting(id: String, t: Long, title: String? = null, status: SessionLibrary.Status = SessionLibrary.Status.DONE) =
        SessionLibrary.Entry(id, File("/x/$id"), title, t, 60, status, source = Provenance(Provenance.RECORDING))
    private fun episode(id: String, t: Long, series: String, status: SessionLibrary.Status = SessionLibrary.Status.DONE) =
        SessionLibrary.Entry(id, File("/x/$id"), "ep $id", t, 60, status,
            source = Provenance(Provenance.PODCAST, seriesId = series, channelTitle = "Show $series"))

    private val lib = listOf(
        meeting("m1", 100), meeting("m2", 500), meeting("m3", 300),
        episode("e1", 200, "A"), episode("e2", 400, "A"), episode("e3", 50, "B"),
    )

    @Test fun channelsGroupEpisodesAndSortWithMeetings() {
        val doc = LC.create(LC.Doc(), "Board", listOf("m1"), "u1", 0)
        val rows = LC.rows(lib, doc)
        // m2(500) · channel A(400) · m3(300) · user collection (100) · channel B(50)
        assertEquals(listOf("m2", "ch:A", "m3", "uc:u1", "ch:B"), rows.map {
            when (it) { is LC.Row.Item -> it.entry.id; is LC.Row.Coll -> it.collection.key }
        })
        val a = (rows[1] as LC.Row.Coll).collection
        assertEquals(LC.Kind.CHANNEL, a.kind); assertEquals("Show A", a.name); assertEquals(listOf("e2", "e1"), a.items.map { it.id })
    }

    @Test fun podcastsFilterListsChannelRowsOnly() {
        val doc = LC.create(LC.Doc(), "Board", listOf("m1"), "u1", 0)
        assertEquals(listOf("ch:A", "ch:B"), LC.rows(lib, doc, podcastsOnly = true).map { (it as LC.Row.Coll).collection.key })
    }

    @Test fun singleEpisodeChannelIsStillARow() {
        val rows = LC.rows(listOf(episode("e9", 1, "Z")), LC.Doc())
        assertTrue(rows.single() is LC.Row.Coll)
    }

    @Test fun oneCollectionPerMeetingAndPodcastsStayInTheirChannel() {
        var doc = LC.create(LC.Doc(), "One", listOf("m1", "m2"), "u1", 0)
        doc = LC.create(doc, "Two", listOf("m2", "e1"), "u2", 0)
        assertEquals("uc:u1", LC.keyOf(lib[0], doc)); assertEquals("uc:u2", LC.keyOf(lib[1], doc))
        assertEquals("ch:A", LC.keyOf(lib[3], doc))
        doc = LC.delete(doc, "u2")
        assertNull("deleting a collection keeps its meetings, outside any", LC.keyOf(lib[1], doc))
        assertEquals(lib.size, lib.count { it.id in lib.map { e -> e.id } })
    }

    @Test fun newBadgeCountsFinishedItemsAfterLastVisit() {
        val eps = listOf(episode("e1", 200, "A"), episode("e2", 400, "A"), episode("e3", 500, "A", SessionLibrary.Status.RECORDED))
        val doc = LC.Doc(since = 100)
        val c = LC.build(eps, doc).single()
        assertEquals("unfinished episodes do not count", 2, c.newCount(doc))
        assertEquals(1, c.newCount(LC.markSeen(doc, "ch:A", 300)))
    }

    @Test fun scopeFiltersSearchAndAskEntries() {
        val doc = LC.create(LC.Doc(), "Board", listOf("m1", "m3"), "u1", 0)
        assertEquals(listOf("e1", "e2"), LC.itemsOf("ch:A", lib, doc).map { it.id })
        val ch = LC.askLevels(lib, doc, "ch:A", null)
        assertEquals(listOf(LC.Level.CHANNEL, LC.Level.PODCASTS, LC.Level.ALL), ch.map { it.level })
        assertEquals(setOf("e1", "e2"), ch[0].entries.map { it.id }.toSet())
        assertEquals(setOf("e1", "e2", "e3"), ch[1].entries.map { it.id }.toSet())
        assertEquals(lib.size, ch[2].entries.size)
        val uc = LC.askLevels(lib, doc, "uc:u1", null)
        assertEquals(listOf(LC.Level.COLLECTION, LC.Level.MEETINGS, LC.Level.ALL), uc.map { it.level })
        assertEquals(setOf("m1", "m3"), uc[0].entries.map { it.id }.toSet())
        // In a meeting: this meeting → its collection → all.
        val inMeeting = LC.askLevels(lib, doc, null, null, meeting = lib[0])
        assertEquals(listOf(LC.Level.MEETING, LC.Level.COLLECTION, LC.Level.ALL), inMeeting.map { it.level })
        assertEquals(listOf("m1"), inMeeting[0].entries.map { it.id })
        assertEquals(listOf(LC.Level.ALL), LC.askLevels(lib, doc, null, null).map { it.level })
    }

    @Test fun titleNormalizationStripsYearsDatesNumbers() {
        assertEquals("股東會錄音", LC.normalizeTitle("2024股東會錄音"))
        assertEquals("股東會錄音", LC.normalizeTitle("股東會錄音 2021/06/18"))
        assertEquals("股東會錄音", LC.normalizeTitle("2022年6月18日 股東會錄音"))
        assertEquals("weeklysync", LC.normalizeTitle("Weekly sync #12 (2024-03-05)"))
    }

    @Test fun suggestionNeedsThreeMatchingTitlesAndSkipIsPermanent() {
        val ms = listOf(meeting("a", 1, "2021股東會錄音"), meeting("b", 2, "2022股東會錄音"), meeting("c", 3, "股東會錄音 2023"),
            meeting("d", 4, "產品週會"), meeting("e", 5, "產品週會 2"))
        val s = LC.suggestion(ms, LC.Doc())!!
        assertEquals(setOf("a", "b", "c"), s.ids.toSet()); assertEquals("股東會錄音", s.name)
        assertNull("two are not enough", LC.suggestion(ms.drop(1), LC.Doc()))
        assertNull(LC.suggestion(ms, LC.dismiss(LC.Doc(), s.key)))
        assertNull("already in a collection", LC.suggestion(ms, LC.create(LC.Doc(), s.name, s.ids, "u", 0)))
    }

    @Test fun persistenceRoundTripsAndOldLibrariesLoadEmpty() {
        assertEquals(LC.Doc(), LC.decode(null)); assertEquals(LC.Doc(), LC.decode("not json")); assertEquals(LC.Doc(), LC.decode("{}"))
        val doc = LC.markSeen(LC.dismiss(LC.create(LC.Doc(since = 7), "Board", listOf("m1"), "u1", 9), "k"), "ch:A", 11)
        assertEquals(doc, LC.decode(LC.encode(doc)))
        // A member of a collection that is gone is dropped on read.
        assertEquals(emptyMap<String, String>(), LC.decode("""{"members":{"m1":"gone"}}""").members)
        // No file (a library from before collections): loads empty, starts its "new" counts now, writes the file.
        val d = LC.load(ctx)
        assertTrue(d.collections.isEmpty() && d.since > 0); assertTrue(LC.file(ctx).isFile)
        LC.update(ctx) { LC.create(it, "Board", listOf("m1"), "u1", 1) }
        LC.forget()
        assertEquals("Board", LC.load(ctx).collections.single().name)
        assertEquals(d.since, LC.load(ctx).since)
    }

    @Test fun recordingFromACollectionPageJoinsIt() {
        LC.update(ctx) { LC.create(it, "Board", emptyList(), "u1", 1) }
        LC.target = "u1"
        val wav = File(ctx.cacheDir, "cap.wav").apply { writeBytes(ByteArray(44)) }
        val e = SessionLibrary.promoteRecording(ctx, wav, 1)!!
        assertEquals("u1", LC.load(ctx).members[e.id]); assertNull(LC.target)
        val e2 = SessionLibrary.promoteRecording(ctx, File(ctx.cacheDir, "cap2.wav").apply { writeBytes(ByteArray(44)) }, 1)!!
        assertNull("armed once", LC.load(ctx).members[e2.id])
    }
}
