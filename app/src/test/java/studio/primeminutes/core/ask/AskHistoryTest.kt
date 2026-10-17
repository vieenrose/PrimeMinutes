package studio.primeminutes.core.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.library.SessionLibrary
import java.nio.file.Files

class AskHistoryTest {
    private val item = AskHistory.Item("誰訂卡車？", "Anna 訂[[20261014-175615@192]]", 1760000000000L, listOf(192 to 192.4, 250 to 250.0))

    @Test fun roundTrips() = assertEquals(listOf(item), AskHistory.decode(AskHistory.encode(listOf(item))))

    @Test fun brokenFileIsAnEmptyHistory() {
        assertTrue(AskHistory.decode("{not json").isEmpty())
        assertTrue(AskHistory.decode("""{"v":1,"items":[{"q":""},{"x":1}]}""").isEmpty())
    }

    @Test fun keepsTheLastMaxAndClears() {
        val dir = Files.createTempDirectory("askhist").toFile()
        repeat(AskHistory.MAX + 5) { AskHistory.append(dir, item.copy(question = "q$it")) }
        val h = AskHistory.load(dir)
        assertEquals(AskHistory.MAX, h.size)
        assertEquals("q5", h.first().question); assertEquals("q${AskHistory.MAX + 4}", h.last().question)
        AskHistory.clear(dir)
        assertTrue(AskHistory.load(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test fun savedTurnsStillResolveTheirCitations() {
        val e = SessionLibrary.Entry("20261014-175615", java.io.File("/tmp/x"), "Office move", 0L, 600, SessionLibrary.Status.DONE)
        val t = AskHistory.turns(e, listOf(item)).single()
        assertTrue(t.done)
        assertEquals(192.4, AskEngine.cite(e.id, 192, t.pool)!!.at, 0.0)
        assertEquals(250, AskEngine.cite(e.id, 251, t.pool)!!.sec)   // a mistyped second: the nearest excerpt
    }
}
