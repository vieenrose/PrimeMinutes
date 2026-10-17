package studio.primeminutes.core.library

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import studio.primeminutes.core.audio.Mp4Tags
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.data.SpeakerName
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * An edit of a library meeting is durable as soon as its journal is written, long before the
 * session.m4a is rebuilt: a kill in between must reopen the edit, the fold must put it into the m4a,
 * and no interleaving of edits, folds and pipeline writes may let an older state win.
 */
@RunWith(RobolectricTestRunner::class)
class EditJournalTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SessionLibrary.root(ctx).deleteRecursively()
    }
    @After fun tearDown() { SessionLibrary.root(ctx).deleteRecursively() }

    private fun utt(i: Int, text: String, speaker: Int = 0) = TranscriptEvent.Utterance(i, text, i * 2.0, i * 2.0 + 1.5, speaker)

    private fun content(vararg lines: String, summary: String = "sum") = SessionLibrary.Content(
        lines.mapIndexed { i, t -> utt(i, t, i % 2) }, mapOf(0 to SpeakerName("Ann", "user", "User edited")),
        summary, null, "Weekly", null, "asr", "nemo", "llm",
    )

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, body: ByteArray) = be32(8 + body.size) + type.toByteArray(Charsets.US_ASCII) + body

    /** A DONE-to-be entry whose session.m4a (a minimal mp4) embeds [c] at revision [rev]. */
    private fun entry(c: SessionLibrary.Content, rev: Long = 0L): SessionLibrary.Entry {
        val wav = File(ctx.cacheDir, "cap.wav").apply { parentFile?.mkdirs(); writeBytes(ByteArray(44)) }
        val e = SessionLibrary.promoteRecording(ctx, wav, 10)!!
        e.wavFile.delete()   // an older entry: its capture was pruned, the m4a is its only audio
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + be32(0) + "isom".toByteArray(Charsets.US_ASCII))
        val plain = File(ctx.cacheDir, "plain.m4a").apply { writeBytes(ftyp + box("moov", box("mvhd", ByteArray(96))) + box("mdat", ByteArray(256) { it.toByte() })) }
        val json = c.manifest(rev).toString().toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(json) } }.toByteArray()
        assertTrue(Mp4Tags.write(plain, e.sessionFile, Base64.encodeToString(gz, Base64.NO_WRAP), "Weekly", "sum", null, null))
        return e
    }

    private fun texts(l: PrimeMinutesSession.Loaded?) = l?.utterances?.map { it.text }
    private fun embeddedTexts(e: SessionLibrary.Entry) =
        PrimeMinutesSession.embeddedManifest(e.sessionFile)!!.getJSONArray("utterances").let { a -> List(a.length()) { a.getJSONObject(it).getString("text") } }

    @Test fun editSurvivesAKillBeforeTheFold() = runBlocking {
        val e = entry(content("two,", "right? yes"))
        assertTrue(SessionLibrary.saveEdits(ctx, e.id, content("two, right?", "yes")))
        // The process dies here: nothing but the files is left. Every reader sees the edit.
        assertEquals(listOf("two, right?", "yes"), texts(PrimeMinutesSession.peek(e.sessionFile)))
        assertEquals(listOf("two, right?", "yes"), texts(PrimeMinutesSession.open(ctx, Uri.fromFile(e.sessionFile))))
        assertTrue(SessionLibrary.pendingEdits(ctx).contains(e.id))
        // An app without the journal reads the m4a: the meeting before the edit, still a valid session.
        assertEquals(listOf("two,", "right? yes"), embeddedTexts(e))
    }

    @Test fun foldPutsTheEditIntoTheM4aAndDropsTheJournal() = runBlocking {
        val e = entry(content("a", "b"))
        SessionLibrary.saveEdits(ctx, e.id, content("a", "B edited", summary = "new sum"))
        val rev = EditJournal.read(e.dir)!!.optLong(EditJournal.REV)
        val updated = SessionLibrary.foldEdits(ctx, e.id)
        assertNotNull(updated)
        assertEquals(SessionLibrary.Status.DONE, updated!!.status)
        assertFalse("folded edits are dropped", EditJournal.exists(e.dir))
        assertEquals(listOf("a", "B edited"), embeddedTexts(e))
        val m = PrimeMinutesSession.embeddedManifest(e.sessionFile)!!
        assertEquals("new sum", m.getString("summary"))
        assertEquals(rev, m.getLong(EditJournal.REV))
        assertEquals(listOf("a", "B edited"), texts(PrimeMinutesSession.peek(e.sessionFile)))
        assertNull("nothing left to fold", SessionLibrary.foldEdits(ctx, e.id))
        assertTrue(SessionLibrary.pendingEdits(ctx).isEmpty())
    }

    @Test fun aKillBetweenTheFoldAndItsCleanupIsHarmless() = runBlocking {
        val e = entry(content("a"))
        SessionLibrary.saveEdits(ctx, e.id, content("a edited"))
        val journal = EditJournal.file(e.dir).readText()
        SessionLibrary.foldEdits(ctx, e.id)
        // The m4a was renamed into place, then the process died before the journal was removed.
        EditJournal.file(e.dir).writeText(journal)
        val before = e.sessionFile.lastModified()
        assertEquals(listOf("a edited"), texts(PrimeMinutesSession.peek(e.sessionFile)))
        assertNull("already in the m4a: not rebuilt", SessionLibrary.foldEdits(ctx, e.id))
        assertFalse(EditJournal.exists(e.dir))
        assertEquals(before, e.sessionFile.lastModified())
        assertEquals(listOf("a edited"), texts(PrimeMinutesSession.peek(e.sessionFile)))
    }

    @Test fun aKillDuringTheFoldKeepsTheOldFileAndTheEdit() = runBlocking {
        val e = entry(content("a"))
        SessionLibrary.saveEdits(ctx, e.id, content("a edited"))
        // A half-written build is a dot-prefixed temp, never the session file itself.
        File(e.dir, ".tagged_tmp.m4a").writeBytes(ByteArray(100))
        assertEquals(listOf("a"), embeddedTexts(e))
        assertEquals(listOf("a edited"), texts(PrimeMinutesSession.peek(e.sessionFile)))
        // Next launch: folded from scratch.
        assertNotNull(SessionLibrary.foldEdits(ctx, e.id))
        assertEquals(listOf("a edited"), embeddedTexts(e))
    }

    @Test fun anEditMadeDuringTheFoldStaysPending() = runBlocking {
        val e = entry(content("a"))
        SessionLibrary.saveEdits(ctx, e.id, content("first"))
        val first = EditJournal.read(e.dir)!!.optLong(EditJournal.REV)
        SessionLibrary.saveEdits(ctx, e.id, content("second"))   // lands while "first" is being folded
        assertFalse("a newer edit is not dropped by an older fold", EditJournal.deleteUpTo(e.dir, first))
        assertEquals(listOf("second"), texts(PrimeMinutesSession.peek(e.sessionFile)))
        SessionLibrary.foldEdits(ctx, e.id)
        assertEquals(listOf("second"), embeddedTexts(e))
        assertFalse(EditJournal.exists(e.dir))
    }

    @Test fun aPipelineResultSupersedesEarlierEditsButNotLaterOnes() = runBlocking {
        val e = entry(content("a"))
        SessionLibrary.saveEdits(ctx, e.id, content("edit before the rerun"))
        SessionLibrary.attachResults(ctx, e, listOf(utt(0, "rerun")), emptyMap(), "s", null, "T", null, "asr", "nemo", "llm",
            audio = Uri.fromFile(e.sessionFile))
        assertFalse(EditJournal.exists(e.dir))
        assertEquals(listOf("rerun"), texts(PrimeMinutesSession.peek(e.sessionFile)))
        SessionLibrary.saveEdits(ctx, e.id, content("edit after the rerun"))
        assertEquals(listOf("edit after the rerun"), texts(PrimeMinutesSession.peek(e.sessionFile)))
    }

    @Test fun concurrentEditsAndFoldsEndOnTheLastEdit() = runBlocking {
        val e = entry(content("start"))
        val revs = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        // Edits from several threads, folds racing them: the last written edit must win everywhere.
        (0 until 40).map { i ->
            async(Dispatchers.IO) {
                if (i % 10 == 9) SessionLibrary.foldEdits(ctx, e.id)
                else {
                    SessionLibrary.saveEdits(ctx, e.id, content("edit $i"))
                    EditJournal.read(e.dir)?.optLong(EditJournal.REV)?.let(revs::add)
                }
            }
        }.awaitAll()
        val last = (PrimeMinutesSession.peek(e.sessionFile))!!
        SessionLibrary.foldEdits(ctx, e.id)
        val folded = PrimeMinutesSession.peek(e.sessionFile)!!
        assertEquals(texts(last), texts(folded))
        assertEquals(texts(folded), embeddedTexts(e))
        assertFalse(EditJournal.exists(e.dir))
        assertEquals("the m4a holds the highest revision ever written", revs.max(), folded.editRev)
    }

    @Test fun revisionsOnlyGrow() {
        val dir = File(SessionLibrary.root(ctx), "x").apply { mkdirs() }
        val a = EditJournal.nextRev(dir); val b = EditJournal.nextRev(dir)
        assertTrue(b > a)
        // An older writer that lost the race never replaces a newer journal.
        EditJournal.write(dir, JSONObject().put(EditJournal.REV, b).put("utterances", org.json.JSONArray()))
        EditJournal.write(dir, JSONObject().put(EditJournal.REV, a).put("title", "stale"))
        assertEquals(b, EditJournal.read(dir)!!.getLong(EditJournal.REV))
        assertFalse(EditJournal.read(dir)!!.has("title"))
    }

    @Test fun aCorruptJournalIsIgnored() = runBlocking {
        val e = entry(content("a"))
        EditJournal.file(e.dir).writeText("{not json")
        assertEquals(listOf("a"), texts(PrimeMinutesSession.peek(e.sessionFile)))
        assertNull(SessionLibrary.foldEdits(ctx, e.id))
        assertFalse("dropped", EditJournal.exists(e.dir))
    }

    @Test fun aJournalOutsideTheLibraryIsNotRead() = runBlocking {
        val e = entry(content("a"))
        val elsewhere = File(ctx.cacheDir, "Download").apply { mkdirs() }
        val f = e.sessionFile.copyTo(File(elsewhere, "meeting.m4a"), overwrite = true)
        EditJournal.write(elsewhere, content("planted").manifest(EditJournal.nextRev(elsewhere)))
        assertEquals(listOf("a"), texts(PrimeMinutesSession.peek(f)))
    }

    @Test fun aSharedCopyCarriesPendingEdits() = runBlocking {
        val e = entry(content("a"))
        SessionLibrary.saveEdits(ctx, e.id, content("a edited"))
        val out = File(ctx.cacheDir, "share/Weekly.m4a").apply { parentFile?.mkdirs() }
        val copy = SessionLibrary.shareableCopy(ctx, SessionLibrary.byId(ctx, e.id)!!, out)!!
        assertEquals(listOf("a edited"), PrimeMinutesSession.embeddedManifest(copy)!!.getJSONArray("utterances").let { a -> List(a.length()) { a.getJSONObject(it).getString("text") } })
        assertTrue("the entry's own file is folded later", EditJournal.exists(e.dir))
    }

    private fun req(id: String, c: SessionLibrary.Content) = studio.primeminutes.service.TranscriptionService.PersistRequest(
        id, null, c.utterances, c.speakerNames, c.summary, c.actionItems, c.title, c.notes, c.asrModelId, c.asrBackend, c.llmModelId,
    )

    @Test fun theScreensSavesAreOnDiskBeforeTheFoldAndInOrder() = runBlocking {
        val e = entry(content("a"))
        val saver = studio.primeminutes.service.EditSaver
        // A burst of edits from the screen (main thread order): the last one is what is on disk.
        for (k in 1..20) saver.submit(ctx, req(e.id, content("edit $k")))
        saver.awaitWritten(e.id)
        assertEquals(listOf("edit 20"), texts(PrimeMinutesSession.peek(e.sessionFile)))
        assertEquals("the m4a waits for the edits to settle", listOf("a"), embeddedTexts(e))
        assertTrue(EditJournal.exists(e.dir))
        // The service's own write (a finished re-summarize) folds at once.
        saver.saveAndFold(ctx, req(e.id, content("edit 21")))
        assertEquals(listOf("edit 21"), embeddedTexts(e))
        assertFalse(EditJournal.exists(e.dir))
    }
}
