package studio.primeminutes.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.models.ChatTemplate
import studio.primeminutes.core.models.ModelCard
import studio.primeminutes.core.reader.Note
import studio.primeminutes.core.reader.ReaderLane
import studio.primeminutes.core.reader.ReaderLlm

/** Robolectric: the lane's thread sets an android.os.Process priority. */
@RunWith(RobolectricTestRunner::class)
class SegmentedProseTest {
    @Test fun partsAreConsecutiveAndBalanced() {
        assertEquals(emptyList<IntRange>(), ReaderLane.proseParts(emptyList(), 100, 4))
        assertEquals(listOf(0..3), ReaderLane.proseParts(listOf(10, 10, 10, 10), 0, 4))      // off
        assertEquals(listOf(0..3), ReaderLane.proseParts(listOf(10, 10, 10, 10), 40, 4))     // fits
        assertEquals(listOf(0..1, 2..3), ReaderLane.proseParts(listOf(10, 10, 10, 10), 39, 4))
        assertEquals(listOf(0..2, 3..5, 6..8), ReaderLane.proseParts(List(9) { 10 }, 30, 4))
        // At most segment_max parts, however long the journal.
        assertEquals(4, ReaderLane.proseParts(List(100) { 10 }, 30, 4).size)
        // Never more parts than notes, every note in exactly one part.
        val p = ReaderLane.proseParts(listOf(500, 1, 1), 100, 4)
        assertEquals(3, p.size)
        assertEquals((0..2).toList(), p.flatMap { it.toList() })
        val q = ReaderLane.proseParts(listOf(5, 5, 80, 5, 5), 50, 2)
        assertEquals(listOf(0..2, 3..4), q)
    }

    /** Replies in order; records each prompt. */
    private class Llm(val replies: ArrayDeque<String>) : ReaderLlm {
        val prompts = ArrayList<String>()
        private val seq = StringBuilder()
        override val template: ChatTemplate = ModelCard.DEFAULT.template
        override fun tokenize(text: String, special: Boolean): IntArray { seq.append(text); return IntArray(text.length) { 7 } }
        override fun append(tokens: IntArray): Int = tokens.size
        override fun generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Unit): String {
            prompts += seq.toString(); seq.clear(); return replies.removeFirst()
        }
        override fun seqLength(): Int = 0
        override fun reset() { seq.clear() }
    }

    private fun card(segmentTokens: Int) = ModelCard.DEFAULT.let { c ->
        c.copy(prose = c.prose.copy(segmentTokens = segmentTokens, segmentMax = 4, segmentPrompt = "PART {from}-{to}\n{notes}"))
    }

    private val journal = (1..6).map { Note(it, 1, "${it}:00", "DECISION", "決定第${it}件事") }

    @Test fun longJournalIsWrittenInPartsJoinedAsParagraphs() = runBlocking {
        val llm = Llm(ArrayDeque(listOf("第一段的摘要內容寫得已經夠長了 [1:00]。<turn|>", "第二段的摘要內容也寫得已經夠長 [4:00]。<turn|>")))
        // The fake counts a token per character: 6 notes of 28 → 168, two parts of 84.
        val lane = ReaderLane(llm, "sys", card(segmentTokens = 84)) {}
        val out = lane.prose(journal, "zh")
        lane.close()
        assertEquals("第一段的摘要內容寫得已經夠長了 [1:00]。\n\n第二段的摘要內容也寫得已經夠長 [4:00]。", out)
        assertEquals(2, llm.prompts.size)
        assertTrue(llm.prompts[0].contains("以下是一場會議的筆記"))       // the first part: the card's own prompt
        assertTrue(llm.prompts[0].contains("決定第1件事") && !llm.prompts[0].contains("決定第4件事"))
        assertTrue(llm.prompts[1].contains("PART 4:00-6:00"))            // the next: the segment prompt
        assertTrue(llm.prompts[1].contains("決定第6件事") && !llm.prompts[1].contains("決定第3件事"))
    }

    @Test fun shortJournalIsOneReply() = runBlocking {
        val llm = Llm(ArrayDeque(listOf("整場會議的摘要內容寫得已經夠長了 [1:00]。<turn|>")))
        val lane = ReaderLane(llm, "sys", card(segmentTokens = 10_000)) {}
        assertEquals("整場會議的摘要內容寫得已經夠長了 [1:00]。", lane.prose(journal, "zh"))
        lane.close()
        assertEquals(1, llm.prompts.size)
    }

    @Test fun cardParsesSegmentingAndLanguagesInheritTheNumbersNotThePrompt() {
        val c = ModelCard.parse(ModelCard::class.java.getResourceAsStream("/model_cards/gemma4-meeting-agent-zh.json")!!.readBytes().toString(Charsets.UTF_8)
            .replace("\"max_tokens\": 600", "\"max_tokens\": 600, \"segment_tokens\": 700, \"segment_max\": 3, \"segment_prompt\": \"S {notes}\""))
        assertEquals(700, c.prose.segmentTokens); assertEquals(3, c.prose.segmentMax)
        assertEquals("S n", c.prose.renderSegment("n", "1:00", "2:00"))
        val en = c.forLanguage("en").prose
        assertEquals(700, en.segmentTokens); assertEquals(3, en.segmentMax)
        assertEquals(en.render("n"), en.renderSegment("n", "1:00", "2:00"))   // no English segment prompt: its own prompt
    }
}
