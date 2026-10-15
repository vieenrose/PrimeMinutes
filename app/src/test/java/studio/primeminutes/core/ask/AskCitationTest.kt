package studio.primeminutes.core.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

class AskCitationTest {
    private fun entry(id: String) = SessionLibrary.Entry(id, File("/tmp/$id"), id, 0L, 600, SessionLibrary.Status.DONE)
    private val a = entry("20261014-175615"); private val b = entry("20261014-175951")
    private val xs = listOf(
        AskEngine.Excerpt(a, 120, "x", 0.9f, 120.4),
        AskEngine.Excerpt(a, 300, "y", 0.8f, 300.0),
        AskEngine.Excerpt(b, 45, "z", 0.7f, 45.2),
    )

    @Test fun exactTagIsKept() = assertEquals(xs[0], AskEngine.cite(a.id, 120, xs))
    @Test fun wrongSecondSnapsToTheNearestExcerptOfThatMeeting() {
        assertEquals(xs[1], AskEngine.cite(a.id, 290, xs))
        assertEquals(xs[2], AskEngine.cite(b.id, 400, xs))
    }
    @Test fun meetingNotAmongTheExcerptsIsDropped() = assertNull(AskEngine.cite("20260101-000000", 120, xs))
    @Test fun noExcerptsNoCitation() = assertNull(AskEngine.cite(a.id, 120, emptyList()))
}
