package studio.primeminutes.core.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A podcast / YouTube item exists from the moment it is submitted: what a kill, a failure or the end
 * of its download leaves on disk must read back as an item the library can show and act on.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadItemTest {
    private lateinit var ctx: Context
    private val ep = SessionLibrary.PendingDownload(SessionLibrary.PendingDownload.PODCAST, "https://example.com/ep1.mp3")

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext(); SessionLibrary.root(ctx).deleteRecursively() }
    @After fun tearDown() { SessionLibrary.root(ctx).deleteRecursively() }

    @Test fun aSubmittedItemIsListedWithItsDownloadAndTitle() {
        val e = SessionLibrary.createDownloadEntry(ctx, " Episode 1 ", ep)!!
        val back = SessionLibrary.list(ctx).single()
        assertEquals(e.id, back.id)
        assertEquals("Episode 1", back.title)
        assertEquals(SessionLibrary.Status.RECORDED, back.status)
        assertEquals(ep, back.download)
        assertFalse("nothing to process yet", back.sourceOnly)
        assertFalse(back.wavFile.exists())
    }

    @Test fun aFailureIsKeptWithItsReasonAndRetryClearsIt() {
        val e = SessionLibrary.createDownloadEntry(ctx, "Ep", ep)!!
        SessionLibrary.saveDownload(e, ep.copy(error = "Download failed (HTTP 404)"))
        assertEquals("Download failed (HTTP 404)", SessionLibrary.byId(ctx, e.id)!!.download!!.error)
        SessionLibrary.saveDownload(e, ep.copy(error = null))
        assertNull(SessionLibrary.byId(ctx, e.id)!!.download!!.error)
    }

    @Test fun aDownloadedFileBecomesTheItemsSource() {
        val e = SessionLibrary.createDownloadEntry(ctx, "Ep", ep)!!
        val file = File(e.dir, "download.mp3").apply { writeBytes(ByteArray(1000)) }
        File(e.dir, "download.mp3.part").delete()
        val done = SessionLibrary.downloadDone(e, file, 3080)!!
        assertNull(done.download)
        assertTrue(done.sourceOnly)
        assertEquals(3080, done.durationSec)
        assertEquals(1000L, SessionLibrary.importSource(done)!!.length())
        assertFalse(file.exists())
    }

    @Test fun deletingAnItemRemovesItsPartialFile() {
        val e = SessionLibrary.createDownloadEntry(ctx, "Ep", ep)!!
        val part = File(e.dir, "download.mp3.part").apply { writeBytes(ByteArray(10)) }
        SessionLibrary.discard(ctx, e.wavFile)
        assertFalse(part.exists())
        assertTrue(SessionLibrary.list(ctx).isEmpty())
    }

    @Test fun processingMovesTheAudioIntoTheSameItem() {
        val e = SessionLibrary.createDownloadEntry(ctx, "Ep", ep)!!
        val src = File(e.dir, "download.mp3").apply { writeBytes(ByteArray(1000)) }
        val item = SessionLibrary.downloadDone(e, src, 600)!!
        // Stopped mid-decode: the prefix goes in, the source stays (Resume decodes all of it), the row keeps its length.
        val prefix = File(ctx.cacheDir, "d1.wav").apply { writeBytes(ByteArray(44 + 32_000 * 3)) }
        val stopped = SessionLibrary.promoteInto(item, prefix, 600, decodedAll = false)!!
        assertEquals(item.id, stopped.id)
        assertTrue(stopped.wavFile.exists())
        assertNotNull(SessionLibrary.importSource(stopped))
        assertFalse(stopped.sourceOnly)
        assertEquals(600, stopped.durationSec)
        // Decoded to the end: the source is no longer needed.
        val whole = File(ctx.cacheDir, "d2.wav").apply { writeBytes(ByteArray(44 + 32_000 * 10)) }
        val done = SessionLibrary.promoteInto(stopped, whole, 10, decodedAll = true)!!
        assertNull(SessionLibrary.importSource(done))
        assertEquals(10, done.durationSec)
        assertEquals("one item, never a second", 1, SessionLibrary.list(ctx).size)
    }
}
