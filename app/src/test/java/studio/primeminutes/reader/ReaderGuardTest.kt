package studio.primeminutes.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.models.ModelCard
import studio.primeminutes.core.reader.GuardCard
import studio.primeminutes.core.reader.Line
import studio.primeminutes.core.reader.MeetingReader
import studio.primeminutes.core.reader.ReaderGuard
import studio.primeminutes.core.reader.ReaderLlm

/** Model guardrail: off-protocol reply lines are counted per job, the notes themselves are unchanged. */
class ReaderGuardTest {
    private class OneReply(private val reply: String) : ReaderLlm {
        private var n = 0
        override fun tokenize(text: String, special: Boolean) = text.codePoints().toArray()
        override fun append(tokens: IntArray): Int { n += tokens.size; return n }
        override fun generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Unit) = reply + stop
        override fun seqLength() = n
        override fun reset() { n = 0 }
    }

    private fun read(reply: String): MeetingReader {
        val r = MeetingReader(OneReply(reply), "sys", pc = ModelCard.DEFAULT.reader)
        r.start()
        listOf(0, 20, 60).forEach { r.offer(Line(it, "S1", "話$it")) }
        r.finish()
        return r
    }

    @Test fun countsEachKind() {
        val r = read(listOf(
            "NOTE [0:00] (DECISION) 尾牙定在十二月十九號",
            "Here is a summary of the meeting.",      // not a NOTE
            "REVISE #1 something",                     // another act: not a NOTE
            "NOTE garbage without a time",             // malformed
            "NOTE [0:20] (TODO) 寄出邀請函",           // unknown tag (kept)
            "",
            "NEXT",                                    // end of turn: not counted
        ).joinToString("\n"))
        assertEquals(ReaderGuard(lines = 5, notes = 2, nonNote = 2, unknownTag = 1, malformed = 1), r.guard)
        assertEquals(listOf("DECISION", "TODO"), r.journal.map { it.tag })
        assertEquals(0.8, r.guard.rate, 1e-9)
    }

    @Test fun cleanReplyHasNoDrops() {
        val r = read("NOTE [0:00] (DECISION) 尾牙定在十二月十九號\nNOTE [1:00] (ACTION) 週五前定案菜單")
        assertEquals(ReaderGuard(lines = 2, notes = 2), r.guard)
        assertEquals(0.0, r.guard.rate, 0.0)
    }

    @Test fun thresholdNeedsEnoughLines() {
        val card = GuardCard(maxOffProtocolRate = 0.2, minLines = 10)
        assertFalse("too few lines to judge", ReaderGuard(lines = 4, nonNote = 4).exceeds(card))
        assertTrue(ReaderGuard(lines = 10, nonNote = 3).exceeds(card))
        assertFalse(ReaderGuard(lines = 10, nonNote = 2).exceeds(card))
    }

    @Test fun shippedCardCarriesTheGuard() {
        val g = ModelCard.DEFAULT.reader.guard
        assertEquals(setOf("DECISION", "ACTION", "NUMBER", "OPEN-ISSUE", "PROPOSAL", "-"), g.knownTags)
        assertEquals(setOf("NEXT"), g.ignoreActs)
        assertEquals(0.2, g.maxOffProtocolRate, 0.0)
        assertTrue(g.known(null)); assertTrue(g.known("decision")); assertTrue(g.known("-")); assertFalse(g.known("TODO"))
    }

    @Test fun cardWithoutGuardFallsBackToDefaults() {
        val text = ModelCard::class.java.getResourceAsStream("/model_cards/gemma4-meeting-agent-zh.json")!!.readBytes().toString(Charsets.UTF_8)
        val o = org.json.JSONObject(text); o.getJSONObject("reader").remove("guard")
        assertEquals(GuardCard(), ModelCard.parse(o.toString()).reader.guard)
    }
}
