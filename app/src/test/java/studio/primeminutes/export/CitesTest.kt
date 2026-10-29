package studio.primeminutes.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import studio.primeminutes.core.export.Cites

/** Cites at the sentence end, glued (the PDF and the notes sections lay them out this way). */
class CitesTest {
    @Test fun zhProseCitesMoveToSentenceEnd() {
        val p = "新辦公室預計於三月十號正式上班 [0:00]。會議決定採用中間報價 [0:18, 0:35]，前提是打包自行完成 [0:40]。"
        assertEquals(
            "新辦公室預計於三月十號正式上班。⁠[0:00] 會議決定採用中間報價，前提是打包自行完成。⁠[0:18, 0:35]",
            Cites.clauseEndCites(p),
        )
    }

    @Test fun enProseCitesUseNoBreakSpaceAndCollapseRepeats() {
        val p = "We move on March 7 [0:12]. The budget is 3.5 percent over [0:12]. No cite here."
        assertEquals("We move on March 7. [0:12] The budget is 3.5 percent over. No cite here.", Cites.clauseEndCites(p))
    }

    @Test fun longHanSentenceNeverOrphansItsCite() {
        val han = "搬".repeat(60)
        val out = Cites.clauseEndCites("$han [1:12]。")
        assertEquals("$han。⁠[1:12]", out)
        assertFalse(Regex("""[㐀-鿿。] \[""").containsMatchIn(out))
    }

    @Test fun itemCitesFollowTheFullStop() {
        assertEquals("。⁠[1:12]", Cites.itemCite("確保包含損壞賠償條款", "1:12"))
        assertEquals("⁠[1:12]", Cites.itemCite("確保包含損壞賠償條款。", "1:12"))
        assertEquals(" [0:05]", Cites.itemCite("Alice sends the deck", "0:05"))
        assertEquals("", Cites.itemCite("x", null))
    }
}
