package studio.primeminutes.people

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.people.CleanSpeech
import studio.primeminutes.core.people.SpeakerCard
import studio.primeminutes.core.people.VoiceFiles
import studio.primeminutes.core.people.VoiceFiles.Exemplar
import studio.primeminutes.core.people.VoiceLane
import studio.primeminutes.core.people.VoiceLane.Note as N
import studio.primeminutes.core.people.VoiceLane.Status as S
import studio.primeminutes.core.people.VoiceLane.Wait as W
import studio.primeminutes.core.people.VoiceMath

/** Voice recognition's pure parts (docs/design/speaker-tags.md §2.6, §4); iOS runs the same cases (VoiceTests.swift). */
class VoiceTest {
    private val card = SpeakerCard.load()

    private fun v(vararg x: Float) = VoiceMath.normalize(x)

    @Test fun theShippedCardParses() {
        assertEquals("Apache-2.0", card.licence)
        assertEquals(192, card.dim)
        assertEquals(80, card.mels)
        assertTrue(card.meanNorm)
        assertEquals(0.50f, card.tLow, 1e-6f); assertEquals(0.70f, card.tHigh, 1e-6f); assertEquals(0.05f, card.margin, 1e-6f)
        assertTrue(card.files.containsKey(card.graph))
        assertEquals(28_800_672L, card.totalBytes)
    }

    @Test fun levelsFollowTheCard() {
        assertEquals(3, VoiceMath.level(0.80f, 0.40f, card))
        assertEquals(2, VoiceMath.level(0.80f, 0.78f, card))   // no margin over the next person: only "possible"
        assertEquals(2, VoiceMath.level(0.55f, 0.10f, card))
        assertEquals(0, VoiceMath.level(0.45f, 0.10f, card))
    }

    @Test fun suggestionsAssignEachPersonToOneSpeaker() {
        val people = mapOf("mark" to v(1f, 0f, 0f), "ada" to v(0f, 1f, 0f))
        val speakers = mapOf(0 to v(1f, 0.1f, 0f), 1 to v(0.9f, 0.2f, 0f), 2 to v(0.1f, 1f, 0f))
        val s = VoiceMath.suggest(speakers, people, card)
        assertEquals("mark", s[0]!!.first().person)
        assertEquals(3, s[0]!!.first().level)
        assertTrue(s[1]!!.none { it.person == "mark" })     // Mark went to speaker 0, the better match
        assertEquals("ada", s[2]!!.first().person)
        // Excluded people (already named in this meeting) are never offered.
        val ex = VoiceMath.suggest(speakers, people, card, excluded = mapOf(0 to setOf("mark"), 1 to setOf("mark"), 2 to setOf("mark")))
        assertTrue(ex.values.flatten().none { it.person == "mark" })
        // Nobody close enough: nothing.
        assertTrue(VoiceMath.suggest(mapOf(0 to v(0f, 0f, 1f)), people, card)[0]!!.isEmpty())
    }

    @Test fun centroidIsSecondsWeighted() {
        val c = VoiceMath.centroid(listOf(v(1f, 0f) to 30f, v(0f, 1f) to 10f))!!
        assertTrue(c[0] > c[1])
        assertEquals(1f, c[0] * c[0] + c[1] * c[1], 1e-5f)
        assertNull(VoiceMath.centroid(emptyList()))
    }

    @Test fun sidecarsRoundTripAndRejectCorruption() {
        val m = VoiceFiles.MeetingVoices(card.id, "abc", listOf(VoiceFiles.SpeakerVoice(0, 42f, v(1f, 2f, 3f)), VoiceFiles.SpeakerVoice(3, 12.5f, v(3f, 2f, 1f))))
        val b = VoiceFiles.encodeMeeting(m)
        val d = VoiceFiles.decodeMeeting(b)!!
        assertEquals(m.modelId, d.modelId); assertEquals("abc", d.fingerprint); assertEquals(2, d.speakers.size)
        assertArrayEquals(m.speakers[1].vector, d.of(3)!!.vector, 0f)
        b[b.size / 2] = (b[b.size / 2] + 1).toByte()
        assertNull(VoiceFiles.decodeMeeting(b))
        val es = listOf(Exemplar("p", card.id, "m1", 0, 20f, true, v(1f, 0f)), Exemplar("p", card.id, "m2", 1, 15f, false, v(0f, 1f)))
        val back = VoiceFiles.decodePeople(VoiceFiles.encodePeople(es))!!
        assertEquals(es.map { it.meeting to it.confirmed }, back.map { it.meeting to it.confirmed })
        assertNull(VoiceFiles.decodePeople(VoiceFiles.encodeMeeting(m)))   // wrong magic
    }

    @Test fun exemplarsFollowTheDirectory() {
        val a = Exemplar("mark", card.id, "m1", 0, 30f, true, v(1f, 0f))
        val b = Exemplar("mark", card.id, "m2", 0, 30f, false, v(0f, 1f))
        val c = Exemplar("ada", card.id, "m2", 1, 30f, true, v(0f, 1f))
        var l = listOf(a, b, c)
        // Only confirmed exemplars make a voice.
        assertArrayEquals(v(1f, 0f), VoiceFiles.Exemplars.voices(l, card.id)["mark"]!!, 1e-6f)
        assertNull(VoiceFiles.Exemplars.voices(l, "other-model")["mark"])
        // Deleting a meeting deletes its samples, even a person's only one (the user's decision).
        assertTrue(VoiceFiles.Exemplars.removeMeeting(l, "m2").none { it.person == "ada" })
        l = VoiceFiles.Exemplars.move(l, "ada", "mark")
        assertTrue(l.all { it.person == "mark" })
        l = VoiceFiles.Exemplars.moveMeeting(l, "m1", "mark", "marcus")
        assertEquals("marcus", l.first { it.meeting == "m1" }.person)
        // A person keeps the newest CAP meetings.
        var many = emptyList<Exemplar>()
        for (i in 0 until VoiceFiles.Exemplars.CAP + 5) many = VoiceFiles.Exemplars.put(many, Exemplar("p", card.id, "m$i", 0, 10f, true, v(1f, 0f))) { it.drop(1).toLong() }
        assertEquals(VoiceFiles.Exemplars.CAP, many.size)
        assertTrue(many.none { it.meeting == "m0" })
    }

    @Test fun fingerprintChangesWithTheSpans() {
        val a = mapOf(0 to listOf(CleanSpeech.Span(1.0, 5.0)))
        val b = mapOf(0 to listOf(CleanSpeech.Span(1.0, 5.5)))
        assertEquals(VoiceFiles.fingerprint(a), VoiceFiles.fingerprint(a))
        assertNotEquals(VoiceFiles.fingerprint(a), VoiceFiles.fingerprint(b))
    }

    @Test fun theGateKeepsVoiceAndDropsPauses() {
        val sr = 16_000
        val r = java.util.Random(1)
        // 1 s of room noise (-60 dB), 2 s of "voice" (-12 dB), 1 s of room noise.
        val pcm = FloatArray(4 * sr) { i -> (r.nextGaussian() * (if (i in sr until 3 * sr) 0.25 else 0.001)).toFloat() }
        val runs = studio.primeminutes.core.people.VoiceGate.runs(pcm, sr)
        assertEquals(1, runs.size)
        val (a, b) = runs[0]
        assertTrue(kotlin.math.abs(a - sr) <= 320 && kotlin.math.abs(b - 3 * sr) <= 320)
        // Pure silence: nothing.
        assertTrue(studio.primeminutes.core.people.VoiceGate.runs(FloatArray(2 * sr), sr).isEmpty())
    }

    @Test fun linesAreReadRoundRobinOverTheThirds() {
        val t = listOf(
            studio.primeminutes.core.people.SpeakerCarryOver.Turn(0.0, 5.0, 0),
            studio.primeminutes.core.people.SpeakerCarryOver.Turn(5.0, 30.0, 0),
            studio.primeminutes.core.people.SpeakerCarryOver.Turn(40.0, 44.0, 0),
            studio.primeminutes.core.people.SpeakerCarryOver.Turn(70.0, 73.0, 0),
            studio.primeminutes.core.people.SpeakerCarryOver.Turn(73.0, 90.0, 1),
        )
        val o = CleanSpeech.ordered(t, 0)
        // First third longest first (5-30), then the second third (40-44), then the last (70-73), then the rest.
        assertEquals(listOf(5.1, 40.1, 70.1, 0.1), o.map { Math.round(it.start * 10) / 10.0 })
    }

    @Test fun aSparseLineGivesNoVoice() {
        fun u(a: Double, b: Double, s: Int, text: String) = studio.primeminutes.core.events.TranscriptEvent.Utterance(index = 0, text = text, startSec = a, endSec = b, speaker = s)
        val words = "we want it to be original something people have not thought of"
        val utts = listOf(u(0.0, 5.0, 0, words), u(5.0, 65.0, 0, "Okay."), u(65.0, 70.0, 1, words), u(70.0, 75.0, 0, words))
        val t = CleanSpeech.voiceTurns(utts)
        assertEquals(listOf(0, -9, 1, 0), t.map { it.speaker })
        assertEquals(listOf(0.1, 70.1), CleanSpeech.ordered(t, 0).map { Math.round(it.start * 10) / 10.0 }.sorted())
    }

    @Test fun theLaneNeverSaysComparingWhenNothingRuns() {
        assertEquals("no vectors, not running: waiting, not comparing", N.WAIT_QUEUE, VoiceLane.note(2, true, null))
        assertEquals(N.WAIT_TRANSCRIPTION, VoiceLane.note(2, true, S.Queued(W.TRANSCRIPTION)))
        assertEquals(N.WAIT_BATTERY, VoiceLane.note(2, true, S.Queued(W.BATTERY)))
        assertEquals(N.COMPUTING, VoiceLane.note(2, true, S.Computing))
        assertEquals(N.FAILED, VoiceLane.note(2, true, S.Failed))
        assertEquals(N.MODEL_MISSING, VoiceLane.note(0, true, S.ModelMissing))
        assertEquals(N.MODEL_MISSING, VoiceLane.note(2, true, S.ModelMissing))
        assertEquals(N.SHORT, VoiceLane.note(1, true, null))
        assertEquals(N.NONE_YET, VoiceLane.note(3, true, null))
        assertEquals(N.NO_MATCH, VoiceLane.note(0, true, null))
        assertNull(VoiceLane.note(0, false, null))
        assertNull("a stale sidecar recomputing keeps its suggestions", VoiceLane.note(0, false, S.Computing))
        assertNull("a failed recompute keeps the old suggestions", VoiceLane.note(0, false, S.Failed))
        assertNull("the meeting on screen goes even while busy", VoiceLane.wait(front = true, busy = true, batteryOk = false))
        assertEquals(W.TRANSCRIPTION, VoiceLane.wait(front = false, busy = true, batteryOk = true))
        assertEquals(W.BATTERY, VoiceLane.wait(front = false, busy = false, batteryOk = false))
        assertNull(VoiceLane.wait(front = false, busy = false, batteryOk = true))
    }
}
