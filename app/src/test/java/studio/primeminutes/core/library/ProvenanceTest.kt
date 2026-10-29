package studio.primeminutes.core.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.online.ArtworkCache
import java.io.File

/** Provenance (source.json): every new item says where it came from; old items read as meetings. */
@RunWith(RobolectricTestRunner::class)
class ProvenanceTest {
    private lateinit var ctx: Context

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext(); SessionLibrary.root(ctx).deleteRecursively() }
    @After fun tearDown() { SessionLibrary.root(ctx).deleteRecursively() }

    private fun wav(name: String = "recording_1700000000000.wav"): File =
        File(ctx.cacheDir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(44 + 32000)) }

    @Test fun aRecordingIsAMeetingWithItsKind() {
        val e = SessionLibrary.promoteRecording(ctx, wav(), 1)!!
        val back = SessionLibrary.byId(ctx, e.id)!!
        assertEquals(Provenance.RECORDING, back.source?.kind)
        assertEquals(Provenance.Category.MEETING, back.category)
    }

    @Test fun anImportedFileIsAMeetingOfKindFile() {
        val e = SessionLibrary.promoteRecording(ctx, wav("x.wav"), 1, SessionLibrary.UNKNOWN_START, Provenance.FILE)!!
        assertEquals(Provenance.FILE, SessionLibrary.byId(ctx, e.id)!!.source?.kind)
        assertEquals(Provenance.Category.MEETING, SessionLibrary.byId(ctx, e.id)!!.category)
    }

    @Test fun aReceivedMeetingIsMarkedReceived() {
        val f = File(ctx.cacheDir, "in.pmin").apply { writeBytes(ByteArray(64)) }
        val e = SessionLibrary.importShared(ctx, f, studio.primeminutes.core.session.SessionShareTest.meetingV1(), null, keepId = true)!!
        val back = SessionLibrary.byId(ctx, e.id)!!
        assertEquals(Provenance.RECEIVED, back.source?.kind)
        assertEquals(Provenance.Category.RECEIVED, back.category)
        assertEquals(true, back.source!!.receivedAt > 0)
        assertNull("not an online source", SessionLibrary.onlineSource(back))
    }

    @Test fun anItemWithoutProvenanceCountsAsAMeeting() {
        val e = SessionLibrary.promoteRecording(ctx, wav(), 1)!!
        File(e.dir, SessionLibrary.SOURCE).delete()   // as saved by a version before provenance
        val back = SessionLibrary.byId(ctx, e.id)!!
        assertNull(back.source)
        assertEquals(Provenance.Category.MEETING, back.category)
        assertNull(SessionLibrary.onlineSource(back))
    }

    @Test fun aPodcastEpisodeKeepsItsChannelSnapshot() {
        val ch = Provenance(Provenance.PODCAST, seriesId = "podcast-abc", channelTitle = "Hard Fork", channelArtist = "NYT",
            artworkUrl = "https://example.com/a.jpg", feedUrl = "https://example.com/feed", published = 1_700_000_000, durationSec = 3492)
        val e = SessionLibrary.createDownloadEntry(ctx, "Ep 1",
            SessionLibrary.PendingDownload(SessionLibrary.PendingDownload.PODCAST, "https://example.com/1.mp3", guid = "g1"), 0, ch)!!
        val src = SessionLibrary.byId(ctx, e.id)!!.source!!
        assertEquals(Provenance.PODCAST, src.kind)
        assertEquals("https://example.com/1.mp3", src.sourceUrl)
        assertEquals("podcast-abc", src.seriesId)
        assertEquals("g1", src.guid)
        assertEquals("Hard Fork", src.channelTitle)
        assertEquals("NYT", src.channelArtist)
        assertEquals("https://example.com/a.jpg", src.artworkUrl)
        assertEquals("https://example.com/feed", src.feedUrl)
        assertEquals(1_700_000_000L, src.published)
        assertEquals(3492, src.durationSec)
        assertEquals(Provenance.Category.PODCAST, SessionLibrary.byId(ctx, e.id)!!.category)
        assertEquals(Provenance.PODCAST, SessionLibrary.onlineSource(SessionLibrary.byId(ctx, e.id)!!))
        // The subscription index still finds it (sourceUrl + guid).
        assertEquals(Triple(e.id, "https://example.com/1.mp3", "g1"), SessionLibrary.sources(ctx).single())
    }

    @Test fun anOldSourceJsonStillReads() {
        val o = JSONObject().put("kind", "podcast").put("sourceUrl", "https://x/1.mp3").put("seriesId", "podcast-1").put("guid", "g")
        val p = Provenance.fromJson(o)
        assertEquals(Provenance.PODCAST, p.kind); assertEquals("podcast-1", p.seriesId); assertNull(p.channelTitle); assertEquals(0L, p.published)
        assertEquals(Provenance.Category.PODCAST, Provenance.categoryOf("youtube"))
        assertEquals(Provenance.Category.MEETING, Provenance.categoryOf(null))
        assertEquals(Provenance.Category.RECEIVED, Provenance.categoryOf("received"))
        assertEquals(p, Provenance.fromJson(p.toJson()))
    }

    @Test fun backfillTakesTheChannelFromItsSubscriptionOnce() {
        val p = Provenance(Provenance.PODCAST, sourceUrl = "u", seriesId = "podcast-1", guid = "g")
        val ch = mapOf("podcast-1" to Provenance.Channel("podcast-1", "Show", "Host", "https://a/b.jpg", "https://feed", mapOf("g" to (1_700_000_000L to 600))))
        val next = Provenance.backfill(p, ch)!!
        assertEquals("Show", next.channelTitle); assertEquals("Host", next.channelArtist)
        assertEquals("https://a/b.jpg", next.artworkUrl); assertEquals("https://feed", next.feedUrl)
        assertEquals(1_700_000_000L, next.published); assertEquals(600, next.durationSec)
        assertNull("already filled: nothing to write", Provenance.backfill(next, ch))
        assertNull("unknown series", Provenance.backfill(p.copy(seriesId = "podcast-2"), ch))
        assertNull("not a podcast", Provenance.backfill(Provenance(Provenance.RECORDING), ch))
        // A snapshot taken at import is never overwritten by the subscription.
        assertEquals("Mine", Provenance.backfill(p.copy(channelTitle = "Mine"), ch)!!.channelTitle)
    }

    @Test fun groupKeyIsTheSeriesElseTheTitleElseOther() {
        assertEquals("podcast-1", Provenance.groupKey(Provenance(Provenance.PODCAST, seriesId = "podcast-1", channelTitle = "T")))
        assertEquals("title:T", Provenance.groupKey(Provenance(Provenance.PODCAST, channelTitle = "T")))
        assertEquals("", Provenance.groupKey(Provenance(Provenance.YOUTUBE)))
        assertEquals("", Provenance.groupKey(null))
    }

    @Test fun artworkIsScaledDownToTheCap() {
        assertEquals(256 to 256, ArtworkCache.scaled(3000, 3000, 256))
        assertEquals(256 to 128, ArtworkCache.scaled(1000, 500, 256))
        assertEquals(100 to 100, ArtworkCache.scaled(100, 100, 256))
        assertEquals(8, ArtworkCache.sampleSize(3000, 3000, 256))
        assertEquals(1, ArtworkCache.sampleSize(300, 300, 256))
        assertEquals("podcast-1", ArtworkCache.keyOf("podcast-1", "https://a"))
        assertEquals("u-", ArtworkCache.keyOf(null, "https://a")!!.take(2))
        assertNull(ArtworkCache.keyOf(null, null))
    }

    @Test fun pruneKeepsOnlyArtworkInUse() {
        val d = ArtworkCache.dir(ctx).apply { mkdirs() }
        File(d, "keep.jpg").writeText("x"); File(d, "gone.jpg").writeText("x")
        ArtworkCache.prune(ctx, setOf("keep"))
        assertEquals(listOf("keep.jpg"), d.list()!!.sorted())
        d.deleteRecursively()
    }
}
