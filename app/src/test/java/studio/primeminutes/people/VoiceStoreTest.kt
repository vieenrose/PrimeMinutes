package studio.primeminutes.people

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.people.VoiceFiles
import studio.primeminutes.core.people.VoiceStore
import studio.primeminutes.data.SpeakerName
import java.io.File

/** "Delete all voice data" while the background lane is still computing a meeting. */
@RunWith(RobolectricTestRunner::class)
class VoiceStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun deleteAllWinsOverAComputationInFlight() {
        VoiceStore.init(ctx)
        PeopleStore.mutate(ctx) { it.copy(voice = true) }
        val dir = File(SessionLibrary.root(ctx), "20261017-110000").apply { mkdirs() }
        val p = PeopleStore.create(ctx, "陳經理")
        PeopleStore.saveNames(dir, mapOf(0 to SpeakerName("陳經理", person = p.id)))
        val mv = VoiceFiles.MeetingVoices(VoiceStore.card.id, "fp", listOf(VoiceFiles.SpeakerVoice(0, 30f, FloatArray(8) { it + 1f })))

        // The lane starts on the meeting, then the user deletes all voice data.
        val started = VoiceStore.generation()
        VoiceStore.deleteAll(ctx)
        assertFalse(VoiceStore.storeSidecar(dir, mv, started))
        assertNull(VoiceStore.readMeeting(dir))

        // A sidecar that is there anyway teaches nobody while recognition is off.
        File(dir, VoiceStore.SIDECAR).writeBytes(VoiceFiles.encodeMeeting(mv))
        VoiceStore.syncMeeting(ctx, dir.name)
        assertTrue(VoiceStore.known(ctx).isEmpty())

        // Turned back on, a new computation is stored and the named person learns again.
        PeopleStore.mutate(ctx) { it.copy(voice = true) }
        assertTrue(VoiceStore.storeSidecar(dir, mv, VoiceStore.generation()))
        assertEquals(true, VoiceStore.syncMeeting(ctx, dir.name))
        assertEquals(setOf(p.id), VoiceStore.known(ctx).keys)

        // Turning it off (keeping the data) also drops a computation already under way.
        val again = VoiceStore.generation()
        VoiceStore.disable(ctx, deleteData = false)
        PeopleStore.mutate(ctx) { it.copy(voice = true) }
        assertFalse(VoiceStore.storeSidecar(dir, mv, again))
    }
}
