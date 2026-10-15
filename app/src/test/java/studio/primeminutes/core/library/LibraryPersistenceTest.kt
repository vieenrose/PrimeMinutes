package studio.primeminutes.core.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.events.TranscriptEvent
import java.io.File

/** The library entry and the processing queue as files: what a kill, a stale setting or a corrupt
 *  file leaves behind must read back as something the pipeline can act on. */
@RunWith(RobolectricTestRunner::class)
class LibraryPersistenceTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SessionLibrary.root(ctx).deleteRecursively(); ProcessingQueue.clear(ctx)
    }
    @After fun tearDown() { SessionLibrary.root(ctx).deleteRecursively(); ProcessingQueue.clear(ctx) }

    private fun wav(name: String = "cap.wav") = File(ctx.cacheDir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(44)) }
    private fun utt(i: Int, text: String) = TranscriptEvent.Utterance(i, text, i * 2.0, i * 2.0 + 1.5, 0)

    @Test fun recordingBecomesARecordedEntry() {
        val src = wav()
        val e = SessionLibrary.promoteRecording(ctx, src, 42)!!
        assertFalse("the capture moved, not copied", src.exists())
        assertTrue(e.wavFile.exists())
        val back = SessionLibrary.byId(ctx, e.id)!!
        assertEquals(SessionLibrary.Status.RECORDED, back.status)
        assertEquals(42, back.durationSec)
        assertNull(back.title)
    }

    @Test fun twoRecordingsInOneSecondGetTwoEntries() {
        val a = SessionLibrary.promoteRecording(ctx, wav("a.wav"), 1)!!
        val b = SessionLibrary.promoteRecording(ctx, wav("b.wav"), 1)!!
        assertNotEquals(a.id, b.id)
        assertEquals(2, SessionLibrary.list(ctx).size)
    }

    @Test fun renameSticks() {
        val e = SessionLibrary.promoteRecording(ctx, wav(), 5)!!
        SessionLibrary.rename(ctx, e.dir, "  Budget review  ")
        assertEquals("Budget review", SessionLibrary.byId(ctx, e.id)!!.title)
        SessionLibrary.rename(ctx, e.dir, "   ")   // blank: ignored
        assertEquals("Budget review", SessionLibrary.byId(ctx, e.id)!!.title)
    }

    @Test fun pendingTranscriptOnlyUnderTheSameSettings() {
        val e = SessionLibrary.promoteRecording(ctx, wav(), 5)!!
        SessionLibrary.savePendingTranscript(e, listOf(utt(0, "你好"), utt(1, "bonjour")), "fp-A")
        assertEquals(listOf("你好", "bonjour"), SessionLibrary.loadPendingTranscript(e, "fp-A")!!.map { it.text })
        assertNull("other settings: re-transcribe", SessionLibrary.loadPendingTranscript(e, "fp-B"))
        assertNull("and the stale sidecar is gone", SessionLibrary.peekPendingTranscript(e))
    }

    @Test fun progressRoundTripsAndClears() {
        val e = SessionLibrary.promoteRecording(ctx, wav(), 5)!!
        val p = SessionLibrary.Progress("fp", listOf(utt(0, "a"), utt(1, "b")), 3.5, false, "- [01:00] decision")
        SessionLibrary.saveProgress(e, p)
        val back = SessionLibrary.loadProgress(e)!!
        assertEquals(p.fingerprint, back.fingerprint)
        assertEquals(p.utterances.map { it.text }, back.utterances.map { it.text })
        assertEquals(3.5, back.asrDoneSec, 0.0)
        assertEquals(p.notes, back.notes)
        SessionLibrary.clearProgress(e)
        assertFalse(SessionLibrary.hasProgress(e))
    }

    @Test fun corruptFilesReadAsAbsent() {
        val e = SessionLibrary.promoteRecording(ctx, wav(), 5)!!
        File(e.dir, "pending_progress.json").writeText("{trunc")
        assertNull(SessionLibrary.loadProgress(e))
        File(e.dir, "pending_transcript.json").writeText("not json")
        assertNull(SessionLibrary.loadPendingTranscript(e, "fp"))
        File(e.dir, "meta.json").writeText("{")
        assertNull(SessionLibrary.byId(ctx, e.id))
        assertTrue("a broken entry is skipped, not a crash", SessionLibrary.list(ctx).isEmpty())
    }

    @Test fun discardRemovesTheWholeEntry() {
        val e = SessionLibrary.promoteRecording(ctx, wav(), 5)!!
        SessionLibrary.discard(ctx, e.wavFile)
        assertFalse(e.dir.exists())
        assertNull(SessionLibrary.byId(ctx, e.id))
    }

    @Test fun queueIsAPersistentFifoWithoutDuplicates() {
        ProcessingQueue.enqueue(ctx, listOf("a", "b"))
        ProcessingQueue.enqueue(ctx, listOf("b", "c", "a"))
        assertEquals(listOf("a", "b", "c"), ProcessingQueue.ids(ctx))
        assertEquals("a", ProcessingQueue.peek(ctx))
        ProcessingQueue.remove(ctx, "a")
        assertEquals(listOf("b", "c"), ProcessingQueue.ids(ctx))
        assertEquals(2, ProcessingQueue.size(ctx))
    }

    @Test fun corruptQueueIsDiscarded() {
        ProcessingQueue.enqueue(ctx, listOf("a"))
        File(ctx.filesDir, "process_queue.json").writeText("[[[")
        assertTrue(ProcessingQueue.ids(ctx).isEmpty())
        ProcessingQueue.enqueue(ctx, listOf("b"))
        assertEquals(listOf("b"), ProcessingQueue.ids(ctx))
        assertNotNull(SessionLibrary.root(ctx))
    }
}
