package studio.primeminutes.session

import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.R
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.data.SpeakerName
import studio.primeminutes.service.TranscriptionService

/** The session screen's transitions, without a screen: state in, state + effects out. */
@RunWith(RobolectricTestRunner::class)   // android.net.Uri
class SessionReducerTest {
    /** Strings come back as "<id>" so assertions don't depend on a locale. */
    private val strings = object : SessionStrings {
        override fun str(id: Int, vararg args: Any) = "#$id"
        override fun plural(id: Int, n: Int, vararg args: Any) = "#$id:$n"
    }
    private val root = File("/data/lib")
    private val env = SessionEnv(strings, root)
    private val s0 = SessionReducer.initial(TranscriptionConfig(), strings)

    private fun utt(i: Int, text: String = "line $i", speaker: Int? = 0) =
        TranscriptEvent.Utterance(i, text, i * 2.0, i * 2.0 + 1.5, speaker)
    private fun entryUri(id: String, name: String = "recording.wav") = Uri.fromFile(File(File(root, id), name))

    private inline fun <reified T> List<SessionEffect>.one(): T = filterIsInstance<T>().single()

    @Test fun launchAudioStartsAFreshTaggedRun() {
        val edited = s0.copy(utterances = listOf(utt(0)), title = "old", summary = "s", sessionGen = 4, statusIsError = true)
        val n = SessionReducer.launchAudio(edited, env, Uri.parse("content://x/a.mp3"), sourceTitle = " Episode 1 ")
        with(n.state) {
            assertEquals(5, sessionGen)
            assertTrue(running); assertFalse(statusIsError)
            assertEquals(Screen.Session, screen)
            assertTrue(utterances.isEmpty()); assertNull(summary)
            assertEquals("Episode 1", title); assertTrue(titleEdited)
            assertNull(libraryDir); assertFalse(recordingRun)
        }
        assertEquals(5, n.effects.one<SessionEffect.StartImport>().gen)
        // The config is applied before the service is asked, and the player pauses.
        val kinds = n.effects.map { it::class }
        assertTrue(kinds.indexOf(SessionEffect.ApplyConfig::class) < kinds.indexOf(SessionEffect.StartImport::class))
        assertTrue(SessionEffect.PausePlayer in n.effects)
    }

    @Test fun launchingALibraryCaptureBindsItsEntry() {
        val n = SessionReducer.launchAudio(s0, env, entryUri("20261014-175615"))
        assertEquals(File(root, "20261014-175615"), n.state.libraryDir)
        assertTrue(n.state.recordingRun)
    }

    @Test fun aDownloadedItemRunsAsAnImportOfItsOwnEntry() {
        val n = SessionReducer.launchAudio(s0, env, entryUri("20261017-090000", "import_source"), "Episode 7")
        assertEquals(File(root, "20261017-090000"), n.state.libraryDir)
        assertFalse("an import: no ⏭ Next talk, the queue does not supersede it", n.state.recordingRun)
        assertEquals("Episode 7", n.state.title)
    }

    @Test fun aDownloadItemOpensItsScreenAndProcessingReplacesIt() {
        val e = studio.primeminutes.core.library.SessionLibrary.Entry(
            "20261017-090000", File(root, "20261017-090000"), "Episode 7", 0L, 0,
            studio.primeminutes.core.library.SessionLibrary.Status.RECORDED,
            download = studio.primeminutes.core.library.SessionLibrary.PendingDownload("podcast", "https://x/e.mp3"),
        )
        val running = s0.copy(sessionGen = 2)
        val open = SessionReducer.openDownload(running, env, e).state
        assertEquals(Screen.Session, open.screen)
        assertEquals("20261017-090000", open.downloadEntryId)
        assertEquals("Episode 7", open.title)
        assertFalse(open.running)
        assertNull(open.audioUri)
        assertEquals(3, open.sessionGen)
        // The download lands and processing starts: the live view replaces the download card.
        val live = SessionReducer.launchAudio(open, env, entryUri(e.id, "import_source"), e.title).state
        assertNull(live.downloadEntryId)
        assertTrue(live.running)
        assertNull(SessionReducer.leaveToStudio(open, env).state.downloadEntryId)
    }

    @Test fun eventsOfASupersededRunAreDropped() {
        val s = SessionReducer.launchAudio(s0, env, Uri.parse("content://x/a.mp3")).state
        val stale = SessionReducer.onEvent(s, env, s.sessionGen - 1, TranscriptEvent.Status("old"), null)
        assertEquals(s, stale.state)
        val live = SessionReducer.onEvent(s, env, s.sessionGen, TranscriptEvent.Status("now"), null)
        assertEquals("now", live.state.status)
        val untagged = SessionReducer.onEvent(s, env, TranscriptionService.UNTAGGED, TranscriptEvent.Status("export"), null)
        assertEquals("export", untagged.state.status)
    }

    @Test fun completeKeepsInFlightEditsByStartTime() {
        var s = SessionReducer.launchAudio(s0, env, Uri.parse("content://x/a.mp3")).state
        val g = s.sessionGen
        s = SessionReducer.onEvent(s, env, g, TranscriptEvent.UtteranceSnapshot(listOf(utt(0), utt(1)), stable = 1), null).state
        assertEquals(1, s.liveStable)
        s = SessionReducer.saveLineText(s, 1, "fixed")
        // Diarization re-indexed the lines: the edit follows the start time, not the index.
        val final = listOf(utt(5).copy(startSec = 0.0), utt(6).copy(startSec = 2.0, text = "asr"))
        s = SessionReducer.onEvent(s, env, g, TranscriptEvent.Complete(final, speakerCount = 2), null).state
        assertEquals(listOf("line 0", "fixed"), s.utterances.map { it.text })   // the shown text wins at each start time
        assertEquals(listOf(5, 6), s.utterances.map { it.index })
        assertTrue(s.transcriptReady); assertTrue(s.running)   // the summary phase follows
        s = SessionReducer.onEvent(s, env, g, TranscriptEvent.SummaryComplete("sum"), null).state
        assertEquals("sum", s.summary); assertFalse(s.running)
    }

    @Test fun emptyTranscriptEndsTheRun() {
        val s = SessionReducer.launchAudio(s0, env, Uri.parse("content://x/a.mp3")).state
        val n = SessionReducer.onEvent(s, env, s.sessionGen, TranscriptEvent.Complete(emptyList()), null)
        assertFalse(n.state.running)
        assertEquals("#${R.string.status_no_speech}", n.state.status)
    }

    @Test fun titleFillsButNeverOverwrites() {
        var s = SessionReducer.launchAudio(s0, env, Uri.parse("content://x/a.mp3"), "Podcast").state
        s = SessionReducer.onEvent(s, env, s.sessionGen, TranscriptEvent.Title("LLM guess"), null).state
        assertEquals("Podcast", s.title)
        s = SessionReducer.launchAudio(s, env, Uri.parse("content://x/b.mp3")).state
        s = SessionReducer.onEvent(s, env, s.sessionGen, TranscriptEvent.Title("LLM guess"), null).state
        assertEquals("LLM guess", s.title)
    }

    @Test fun failureOffersRetryOnlyInTheSession() {
        val s = SessionReducer.launchAudio(s0, env, Uri.parse("content://x/a.mp3")).state
        val n = SessionReducer.onEvent(s, env, s.sessionGen, TranscriptEvent.Failed("boom"), null)
        assertTrue(n.state.statusIsError); assertFalse(n.state.running)
        assertEquals(s.audioUri, n.effects.one<SessionEffect.OfferRetry>().source)
        // From Studio: a toast, no retry.
        val away = SessionReducer.onEvent(s.copy(screen = Screen.Studio), env, s.sessionGen, TranscriptEvent.Failed("boom"), null)
        assertTrue(away.effects.none { it is SessionEffect.OfferRetry })
        away.effects.one<SessionEffect.Toast>()
    }

    @Test fun recordingFailureLeavesTheBooth() {
        val s = SessionReducer.beginRecording(s0, env).state
        assertEquals(Screen.Capture, s.screen); assertTrue(s.isRecording)
        val n = SessionReducer.onEvent(s, env, s.sessionGen, TranscriptEvent.Failed("mic"), null)
        assertEquals(Screen.Studio, n.state.screen); assertFalse(n.state.isRecording)
    }

    @Test fun beginRecordingIsIdempotentWhileLive() {
        val s = SessionReducer.beginRecording(s0, env).state
        val again = SessionReducer.beginRecording(s, env)
        assertEquals(s, again.state); assertTrue(again.effects.isEmpty())
    }

    @Test fun stopAndSaveLeavesTheQueueingToTheService() {
        var s = SessionReducer.beginRecording(s0, env).state
        val stop = SessionReducer.stop(s, env)
        assertTrue(SessionEffect.StopRecording in stop.effects)
        s = stop.state
        assertEquals(Screen.Session, s.screen); assertTrue(s.pendingAutoProcess); assertTrue(s.running)
        val saved = SessionReducer.onEvent(s, env, s.sessionGen, TranscriptEvent.RecordingSaved(entryUri("e1").toString()), null)
        assertEquals(File(root, "e1"), saved.state.libraryDir)
        assertFalse(saved.state.pendingAutoProcess)
        assertTrue(saved.effects.none { it is SessionEffect.StartRecording })
    }

    @Test fun nextTalkRollsIntoANewRecordingOnlyFromTheBooth() {
        var s = SessionReducer.beginRecording(s0, env).state
        val n = SessionReducer.nextTalk(s, env)
        assertTrue(SessionEffect.StopRecordingDefer in n.effects)
        s = n.state
        val g = s.sessionGen
        val saved = SessionReducer.onEvent(s, env, g, TranscriptEvent.RecordingSaved(entryUri("e1").toString()), null)
        assertEquals(g + 1, saved.effects.one<SessionEffect.StartRecording>().gen)
        assertTrue(saved.state.isRecording); assertEquals(Screen.Capture, saved.state.screen)
        // Left the booth meanwhile: no new recording starts (the service queued the capture).
        val away = SessionReducer.onEvent(s.copy(screen = Screen.Studio), env, g, TranscriptEvent.RecordingSaved(entryUri("e1").toString()), null)
        assertTrue(away.effects.none { it is SessionEffect.StartRecording })
    }

    @Test fun aLibraryResummaryCarriesTheSessionForTheServiceToSave() {
        val names = mapOf(0 to SpeakerName("Ann", confidence = "user"))
        val s = s0.copy(
            utterances = listOf(utt(0)), speakerNames = names, summary = "old", title = "T", actionItems = "- a",
            transcriptReady = true, libraryDir = File(root, "e7"), audioUri = entryUri("e7", "session.m4a"),
        )
        val save = SessionReducer.reSummarize(s, env, journal = "j").effects.one<SessionEffect.StartSummarize>().save!!
        assertEquals("e7", save.entryId)
        assertEquals(listOf(utt(0)), save.utterances); assertEquals(names, save.speakerNames)
        assertEquals("T", save.title)   // kept, or the fallback when a new one is not written
        assertEquals(entryUri("e7", "session.m4a"), save.audioUri)
        // Not a library session, or a watched queue item: nothing to save into.
        assertNull(SessionReducer.reSummarize(s.copy(libraryDir = null), env, null).effects.one<SessionEffect.StartSummarize>().save)
        assertTrue(SessionReducer.reSummarize(s.copy(watchingQueue = true), env, null).effects.none { it is SessionEffect.StartSummarize && it.save != null })
    }

    @Test fun aRunStoppedOutsideTheScreenSettlesIt() {
        // An import the notification's Stop cancelled: no terminal event, the run just ends.
        val running = SessionReducer.launchAudio(s0, env, Uri.parse("content://x/a.mp3")).state
        val n = SessionReducer.runEnded(running, env)
        assertFalse(n.state.running); assertEquals("#${R.string.status_stopped}", n.state.status)
        assertTrue(n.effects.none { it is SessionEffect.StopRun })   // the service already stopped it
        // A recording cancelled from the notification: the booth is left.
        val rec = SessionReducer.runEnded(SessionReducer.beginRecording(s0, env).state, env).state
        assertFalse(rec.isRecording); assertFalse(rec.running); assertEquals(Screen.Studio, rec.screen)
        // A re-summarize stopped mid-way gets its old summary back.
        val r = SessionReducer.reSummarize(s0.copy(utterances = listOf(utt(0)), summary = "old", transcriptReady = true), env, "j").state
        assertEquals("old", SessionReducer.runEnded(r, env).state.summary)
        // A failed reading keeps the reason on the status line.
        val failed = running.copy(status = "#${R.string.svc_summary_failed} [oom]")
        assertEquals(failed.status, SessionReducer.runEnded(failed, env).state.status)
        // Idle or a watched queue item: untouched.
        assertEquals(s0, SessionReducer.runEnded(s0, env).state)
        val watching = running.copy(watchingQueue = true)
        assertEquals(watching, SessionReducer.runEnded(watching, env).state)
    }

    @Test fun stoppedResummaryPutsTheOldSummaryBack() {
        val s = s0.copy(utterances = listOf(utt(0)), summary = "old", title = "T", titleEdited = true, transcriptReady = true)
        val r = SessionReducer.reSummarize(s, env, journal = "j")
        assertNull(r.state.summary); assertTrue(r.state.running)
        assertEquals("T", r.state.title)   // a hand title stays
        assertFalse(r.effects.one<SessionEffect.StartSummarize>().withTitle)
        val stopped = SessionReducer.stop(r.state, env)
        assertEquals("old", stopped.state.summary); assertFalse(stopped.state.running)
        assertEquals("j", stopped.effects.one<SessionEffect.AgentRestore>().journal)
        assertTrue(SessionEffect.StopRun in stopped.effects)
    }

    @Test fun finishedResummaryOffersUndo() {
        var s = s0.copy(utterances = listOf(utt(0)), summary = "old", screen = Screen.Session)
        s = SessionReducer.reSummarize(s, env, journal = null).state
        val done = SessionReducer.onEvent(s, env, s.sessionGen, TranscriptEvent.SummaryComplete("new"), null)
        val undo = done.effects.one<SessionEffect.OfferResummaryUndo>()
        assertEquals("new", done.state.summary)
        assertEquals("old", SessionReducer.undoResummary(done.state, env, undo.undo, undo.gen).state.summary)
        // Not after the session moved on.
        val moved = done.state.copy(sessionGen = done.state.sessionGen + 1)
        assertEquals("new", SessionReducer.undoResummary(moved, env, undo.undo, undo.gen).state.summary)
    }

    @Test fun handEditsMarkTheSessionAndItsChildren() {
        var s = s0.copy(utterances = listOf(utt(0), utt(1)), summary = "sum")
        s = SessionReducer.beginLineEdit(s, 1)
        assertEquals(1, s.editingIndex)
        s = SessionReducer.saveLineText(s, 1, "edited")
        assertEquals("edited", s.utterances[1].text)
        assertTrue(s.sessionDirty); assertTrue(s.transcriptDirty); assertEquals(-1, s.editingIndex); assertEquals(1, s.editSeq)
        // An empty edit is ignored.
        assertEquals(s, SessionReducer.saveLineText(s, 0, ""))
        s = SessionReducer.commitSpeakerName(s, 0, "Alice")
        assertEquals(SpeakerName("Alice", confidence = "user"), s.speakerNames[0])
        s = SessionReducer.commitSpeakerName(s, 0, " ")
        assertNull(s.speakerNames[0])
    }

    @Test fun speakerMergeIsUndoableUntilTheTranscriptChanges() {
        val s = s0.copy(utterances = listOf(utt(0, speaker = 0), utt(1, speaker = 1)))
        val n = SessionReducer.mergeSpeaker(s, env, 1, 0)
        assertEquals(listOf(0, 0), n.state.utterances.map { it.speaker })
        val u = n.effects.one<SessionEffect.OfferSpeakerUndo>()
        assertEquals(listOf(0, 1), SessionReducer.undoSpeakerEdit(n.state, u.gen, u.before, u.after).utterances.map { it.speaker })
        val edited = SessionReducer.saveLineText(n.state, 0, "x")
        assertEquals(edited, SessionReducer.undoSpeakerEdit(edited, u.gen, u.before, u.after))
    }

    @Test fun persistOnlyWhenDirtyIdleAndLibraryBound() {
        val s = s0.copy(utterances = listOf(utt(0)), libraryDir = File(root, "e1"), sessionDirty = true)
        val n = SessionReducer.persistEdits(s, env)
        assertEquals("e1", n.effects.one<SessionEffect.Persist>().entryId)
        assertFalse(n.state.sessionDirty)
        assertTrue(SessionReducer.persistEdits(s.copy(running = true), env).effects.isEmpty())
        assertTrue(SessionReducer.persistEdits(s.copy(libraryDir = null), env).effects.isEmpty())
        assertTrue(SessionReducer.persistEdits(n.state, env).effects.isEmpty())
    }

    @Test fun leavingAWatchedQueueItemReleasesRunning() {
        val e = studio.primeminutes.core.library.SessionLibrary.Entry(
            id = "q1", dir = File(root, "q1"), createdAt = 0L, title = null,
            status = studio.primeminutes.core.library.SessionLibrary.Status.RECORDED, durationSec = 0,
        )
        var s = s0.copy(queueItemId = "q1", queueUtterances = listOf(utt(0)), queueTitle = "Q", queueLabel = "reading")
        s = SessionReducer.watchQueueItem(s, env, e, null).state
        assertTrue(s.watchingQueue); assertTrue(s.running); assertEquals("Q", s.title); assertEquals(1, s.utterances.size)
        // Queue events stream into the watched view…
        s = SessionReducer.onEvent(s, env, TranscriptionService.QUEUE_GEN, TranscriptEvent.Utterance(1, "b", 2.0, 3.0), "q1").state
        assertEquals(2, s.utterances.size); assertEquals(2, s.queueUtterances.size)
        // …until the drain moves on to the next item: the view keeps what it showed, and is no longer
        // in progress (nothing feeds it; it used to stay running, with Studio's processing banner).
        s = SessionReducer.onEvent(s, env, TranscriptionService.QUEUE_GEN, TranscriptEvent.Status("next"), "q2").state
        assertFalse(s.watchingQueue); assertEquals("q2", s.queueItemId); assertEquals(2, s.utterances.size)
        assertFalse(s.running); assertEquals("reading", s.status)
        val back = SessionReducer.leaveToStudio(s.copy(watchingQueue = true), env).state
        assertFalse(back.running); assertEquals(Screen.Studio, back.screen)
    }

    @Test fun aWatchedQueueItemWhoseQueueEndedSettles() {
        val e = studio.primeminutes.core.library.SessionLibrary.Entry(
            id = "q1", dir = File(root, "q1"), createdAt = 0L, title = null,
            status = studio.primeminutes.core.library.SessionLibrary.Status.RECORDED, durationSec = 0,
        )
        val watching = SessionReducer.watchQueueItem(s0.copy(queueItemId = "q1", queueLabel = "transcribing"), env, e, null).state
        val n = SessionReducer.queueEnded(watching, env).state
        assertFalse(n.running); assertFalse(n.watchingQueue); assertEquals("#${R.string.status_stopped}", n.status)
        // A failed reading keeps its reason; a screen not watching the queue is not touched.
        val failed = watching.copy(status = "#${R.string.svc_summary_failed} [oom]")
        assertEquals(failed.status, SessionReducer.queueEnded(failed, env).state.status)
        val own = SessionReducer.launchAudio(s0, env, Uri.parse("content://x/a.mp3")).state
        assertEquals(own, SessionReducer.queueEnded(own, env).state)
    }

    @Test fun aNoSpeechTitleIsNotASavedTitle() {
        val loaded = studio.primeminutes.core.session.PrimeMinutesSession.Loaded(
            audio = File("/data/lib/e1/session.m4a"), utterances = listOf(utt(0)), speakerNames = emptyMap(),
            summary = null, title = null, notes = null, asrModelId = null, asrBackend = null, llmModelId = null, recovered = true,
        )
        fun open(meta: String?) = SessionReducer.sessionOpened(s0, env, OpenedSession(entryUri("e1", "session.m4a"), loaded, meta, null, null)).state
        assertTrue(open("Budget review").titleEdited)
        // A re-summarize writes a title over it.
        assertFalse(open("No speech detected").titleEdited)
        assertFalse(open("未偵測到語音").titleEdited)
    }

    @Test fun deletingTheOpenSessionStopsItsRun() {
        val s = SessionReducer.launchAudio(s0, env, entryUri("e1")).state
        val e = studio.primeminutes.core.library.SessionLibrary.Entry(
            id = "e1", dir = File(root, "e1"), createdAt = 0L, title = null,
            status = studio.primeminutes.core.library.SessionLibrary.Status.RECORDED, durationSec = 0,
        )
        val n = SessionReducer.deleteEntries(s, env, listOf(e))
        assertTrue(SessionEffect.StopRun in n.effects)
        assertFalse(n.state.running); assertNull(n.state.libraryDir)
        n.effects.one<SessionEffect.DeleteEntries>()
    }

    @Test fun llmChangeMarksTheSummaryStale() {
        val s = s0.copy(summary = "sum", utterances = listOf(utt(0)))
        val n = SessionReducer.configChanged(s, env, s.config.copy(llmModelId = s.config.llmModelId + "-other"))
        assertTrue(n.state.summaryStale)
        n.effects.one<SessionEffect.SaveConfig>()
    }
}
