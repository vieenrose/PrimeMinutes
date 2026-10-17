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
import studio.primeminutes.core.reader.Note
import studio.primeminutes.core.reader.ReaderCard
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

    private fun read(texts: List<String>, byLanguage: (String?) -> ReaderCard = { ModelCard.DEFAULT.forLanguage(it).reader }): Pair<Recorder, MeetingReader> {
        val card = ModelCard.DEFAULT
        val llm = Recorder()
        val r = MeetingReader(llm, "sys", count = { it.length }, budget = card.budget, pc = card.reader, byLanguage = byLanguage)
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

    /** The system prompt waits for the language: an English meeting's FIRST window is read with the
     *  card's agreement rule (quality eval §8 "still open"). */
    @Test fun firstWindowReadsTheLanguagesSystemPrompt() {
        val (llm, _) = read(List(5) { "OK so we'll go with the yellow casing and the rubber push buttons, agreed" })
        val suffix = ModelCard.DEFAULT.forLanguage("en").reader.systemSuffix!!
        val t = ModelCard.DEFAULT.template
        val first = llm.turns.first()
        assertTrue(first.startsWith(t.p0 + "sys" + suffix + t.p1))
        // What the first lines fed while the prefix waited follows it, in order.
        assertTrue(first.indexOf(ModelCard.DEFAULT.reader.windowHeader(1)) > first.indexOf(ModelCard.DEFAULT.reader.primer))
        assertTrue(first.indexOf("[0:00] S1:") > first.indexOf(ModelCard.DEFAULT.reader.windowHeader(1)))
    }

    /** A Chinese meeting's conversation is exactly the card's own, as an upfront prefill fed it. */
    @Test fun chineseConversationUnchangedByTheWait() {
        val texts = List(40) { "那我們就決定尾牙定在十二月十九號，地點在台北，大家同意，第${it}點" }
        val (withLang, _) = read(texts)
        val (plain, _) = read(texts, byLanguage = { ModelCard.DEFAULT.reader })
        assertEquals(plain.turns, withLang.turns)
        val t = ModelCard.DEFAULT.template
        assertTrue(withLang.turns.first().startsWith(t.p0 + "sys" + t.p1))
    }

    /** The minutes' proposal guard reads the meeting's language (card `languages.<code>`). */
    @Test fun proposalGuardFollowsTheLanguage() {
        fun n(text: String, tag: String = "DECISION") = Note(1, 1, "1:00", tag, text)
        val en = ModelCard.DEFAULT.forLanguage("en").reader
        val zh = ModelCard.DEFAULT.forLanguage("zh").reader
        for (t in listOf("Maybe use rubber push buttons.", "We could use a kinetic battery.", "Should we go for a curved case?",
            "Let's think about a titanium case.", "S2 proposed a yellow casing.", "Marketing suggested a voice recognizer.",
            "Not sure about the scroll wheel.", "Use a solar cell?")) {
            assertEquals(t, "PROPOSAL", en.reclassify(n(t)).tag)
            assertEquals(t, "PROPOSAL", en.reclassify(n(t, "ACTION")).tag)
            assertEquals("the Chinese cues do not read English", "DECISION", zh.reclassify(n(t)).tag)
        }
        for (t in listOf("Use rubber push buttons and a yellow casing.", "The team agreed on S2's suggestion of a kinetic battery.",
            "Decided: maybe later, the logo goes inside.", "Ed will email the specs by Friday."))
            assertEquals(t, "DECISION", en.reclassify(n(t)).tag)
        assertEquals("ACTION", en.actions(listOf(n("Ed will email the specs by Friday.", "ACTION"))).single().tag)
        assertTrue(en.actions(listOf(n("Maybe Ed could email the specs?", "ACTION"))).isEmpty())
        // Chinese keeps upstream's guard.
        assertEquals("PROPOSAL", zh.reclassify(n("建議採用黃色外殼")).tag)
        assertEquals("DECISION", zh.reclassify(n("決定採用黃色外殼")).tag)
        assertTrue(en.minutes(listOf(n("We could use a kinetic battery."))).contains("We could use a kinetic battery"))
    }
}
