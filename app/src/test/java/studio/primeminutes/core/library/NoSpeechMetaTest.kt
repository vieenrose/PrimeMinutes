package studio.primeminutes.core.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.reader.ReaderGuard
import java.io.File

/** The no-speech status and the reader guard sidecar read back from an entry's files. */
@RunWith(RobolectricTestRunner::class)
class NoSpeechMetaTest {
    private lateinit var ctx: Context

    @Before fun setUp() { ctx = ApplicationProvider.getApplicationContext(); SessionLibrary.root(ctx).deleteRecursively() }
    @After fun tearDown() { SessionLibrary.root(ctx).deleteRecursively() }

    private fun meta(id: String, json: String): File =
        File(SessionLibrary.root(ctx), id).apply { mkdirs(); File(this, "meta.json").writeText(json) }

    @Test fun flagRoundTrips() {
        meta("a", """{"id":"a","createdAt":1,"durationSec":5,"status":"DONE","noSpeech":true,"title":"x"}""")
        assertTrue(SessionLibrary.byId(ctx, "a")!!.noSpeech)
        SessionLibrary.rename(ctx, File(SessionLibrary.root(ctx), "a"), "renamed")
        val back = SessionLibrary.byId(ctx, "a")!!
        assertEquals("renamed", back.title)
        assertTrue("a rename keeps the status", back.noSpeech)
    }

    @Test fun legacySilentMeetingsReadAsNoSpeech() {
        meta("b", """{"id":"b","createdAt":1,"durationSec":5,"status":"DONE","title":"未偵測到語音"}""")
        meta("c", """{"id":"c","createdAt":1,"durationSec":5,"status":"DONE","title":"Weekly sync"}""")
        meta("d", """{"id":"d","createdAt":1,"durationSec":5,"status":"RECORDED","title":"No speech detected"}""")
        assertTrue(SessionLibrary.byId(ctx, "b")!!.noSpeech)
        assertFalse(SessionLibrary.byId(ctx, "c")!!.noSpeech)
        assertFalse("not processed yet", SessionLibrary.byId(ctx, "d")!!.noSpeech)
    }

    @Test fun readerGuardSidecar() {
        val dir = File(SessionLibrary.root(ctx), "e").apply { mkdirs() }
        val g = ReaderGuard(lines = 20, notes = 15, nonNote = 3, unknownTag = 1, malformed = 1)
        SessionLibrary.saveReaderGuard(dir, g, "E2B")
        assertEquals(g, SessionLibrary.loadReaderGuard(dir))
    }
}
