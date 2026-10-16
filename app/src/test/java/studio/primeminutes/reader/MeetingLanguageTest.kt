package studio.primeminutes.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.models.ModelCard
import studio.primeminutes.core.reader.Line
import studio.primeminutes.core.reader.MeetingLanguage
import studio.primeminutes.core.reader.MeetingReader
import studio.primeminutes.core.reader.ReaderLlm

/** The notes follow the meeting's language (quality eval 2026-10: every AMI meeting was summarized in
 *  Chinese). Mirrored by ios/tests GuardAndPlanTests. */
class MeetingLanguageTest {
    @Test fun detectsByScript() {
        assertEquals("en", MeetingLanguage.detect(listOf("so we'll go with the yellow casing and the rubber push buttons then, and the logo goes on the inside of the case")))
        assertEquals("zh", MeetingLanguage.detect(listOf("那我們就決定尾牙定在十二月十九號，地點在台北")))
        // A Chinese meeting with English terms stays Chinese.
        assertEquals("zh", MeetingLanguage.detect(listOf("這個 project 的 deadline 是下週五，UI 的部分要先給 PM 看過再說")))
        assertNull("too little to tell", MeetingLanguage.detect(listOf("OK", "好")))
        assertEquals("zh", MeetingLanguage.detect(listOf("会议决定采用新的方案并由小王负责跟进，下周再确认")))   // Simplified too
    }

    @Test fun cardVariantForEnglish() {
        val base = ModelCard.DEFAULT
        val en = base.forLanguage("en")
        assertNotEquals(base.prose.prompt, en.prose.prompt)
        assertNotEquals(base.title.prompt, en.title.prompt)
        assertNotNull(en.reader.directive)
        assertNotNull("the agreement rule rides on the system prompt", en.reader.systemSuffix)
        assertNull("Chinese meetings read the upstream prompt", base.forLanguage("zh").reader.systemSuffix ?: base.forLanguage("zh").reader.directive)
        // The language changes the words, never the budgets (prose.max_tokens is tuned separately).
        assertEquals(base.prose.maxTokens, en.prose.maxTokens)
        assertEquals(base.title.maxTokens, en.title.maxTokens)
        assertEquals(base.reader.sections.map { it.second }, en.reader.sections.map { it.second })
        assertTrue(en.prose.render("x").contains("x"))
        // An unlisted or unknown language keeps the card as it is.
        assertEquals(base, base.forLanguage(null))
        assertEquals(base, base.forLanguage("fr"))
        assertEquals(base.prose, base.forLanguage("zh").prose)
    }

    /** Records each reading turn's conversation. */
    private class Recorder : ReaderLlm {
        val seq = StringBuilder(); val turns = ArrayList<String>()
        override fun tokenize(text: String, special: Boolean) = text.codePoints().toArray()
        override fun append(tokens: IntArray): Int { tokens.forEach { seq.appendCodePoint(it) }; return seqLength() }
        override fun generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Unit): String {
            turns += seq.toString(); return "NEXT$stop"
        }
        override fun seqLength() = seq.codePointCount(0, seq.length)
        override fun reset() { seq.setLength(0) }
    }

    private fun read(texts: List<String>): Pair<Recorder, MeetingReader> {
        val card = ModelCard.DEFAULT
        val llm = Recorder()
        val r = MeetingReader(llm, "sys", count = { it.length }, budget = card.budget, pc = card.reader, byLanguage = { card.forLanguage(it).reader })
        r.start()
        texts.forEachIndexed { i, t -> r.offer(Line(i * 10, "S1", t)) }
        r.finish()
        return llm to r
    }

    @Test fun englishMeetingGetsTheEnglishDirective() {
        val (llm, r) = read(List(5) { "OK so we'll go with the yellow casing and the rubber push buttons, agreed" })
        assertEquals("en", r.language)
        val directive = ModelCard.DEFAULT.forLanguage("en").reader.directive!!
        assertTrue(llm.turns.single().endsWith(directive + ModelCard.DEFAULT.template.let { "${it.end}${it.sep}${it.model}" }))
        // The minutes' headers follow the language too.
        assertTrue(r.finish().contains(ModelCard.DEFAULT.forLanguage("en").reader.sections.first().first))
    }

    @Test fun chineseMeetingKeepsTheCardsOwnTurn() {
        val (llm, r) = read(List(5) { "那我們就決定尾牙定在十二月十九號，地點在台北，大家同意" })
        assertEquals("zh", r.language)
        val en = ModelCard.DEFAULT.forLanguage("en").reader.directive!!
        assertTrue(!llm.turns.single().contains(en))
        ModelCard.DEFAULT.forLanguage("zh").reader.directive?.let { assertTrue(llm.turns.single().contains(it)) }
    }
}
