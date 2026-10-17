package studio.primeminutes.people

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.people.SpeakerLink
import studio.primeminutes.data.SpeakerName
import java.io.File

/** The sidecar is the authority for a library meeting's names, named by the directory. */
@RunWith(RobolectricTestRunner::class)
class PeopleStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun sidecarOverlayAndDirectoryEdits() {
        val dir = File(SessionLibrary.root(ctx), "20261017-100000").apply { mkdirs() }
        val session = File(dir, SessionLibrary.SESSION_NAME)
        val mark = PeopleStore.create(ctx, "Mark")
        PeopleStore.saveNames(dir, mapOf(0 to SpeakerName("Mark", person = mark.id), 2 to SpeakerName("Lin")))
        // The sidecar wins over the names embedded in the file.
        val shown = PeopleStore.overlay(session, mapOf(0 to SpeakerName("Old"), 1 to SpeakerName("Gone")))
        assertEquals(setOf(0, 2), shown.keys); assertEquals("Mark", shown[0]!!.name)
        // Rename everywhere: the meeting's copy follows.
        PeopleStore.renameEverywhere(ctx, mark.id, "Marcus")
        assertEquals(SpeakerLink("Marcus", mark.id), PeopleStore.readLinks(dir)!![0])
        assertEquals(mapOf(mark.id to setOf(dir.name)), PeopleStore.meetingsByPerson(PeopleStore.allLinks(ctx)))
        // Split this meeting's Marcus off.
        val other = PeopleStore.split(ctx, dir.name, mark.id, "Marco")!!
        assertEquals(other.id, PeopleStore.readLinks(dir)!![0]!!.person)
        // Delete keeps the name as a plain label.
        PeopleStore.delete(ctx, other.id, clearNames = false)
        assertEquals(SpeakerLink("Marco", null), PeopleStore.readLinks(dir)!![0])
        assertTrue(PeopleStore.doc.value.people.none { it.id == other.id })
        // Outside the library the embedded names are shown as they are.
        val plain = mapOf(0 to SpeakerName("X"))
        assertEquals(plain, PeopleStore.overlay(File(ctx.cacheDir, "a/session.m4a"), plain))
    }
}
