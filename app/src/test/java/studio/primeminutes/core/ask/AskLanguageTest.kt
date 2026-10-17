package studio.primeminutes.core.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.models.ModelCard

/** Ask answers in the language of the question, not the interface's (v0.53.4: an English question on a
 *  zh-TW interface got a Chinese answer). Mirrored by ios/tests AskSelectTests. */
class AskLanguageTest {
    private val card = ModelCard.DEFAULT

    @Test fun answerFollowsTheQuestion() {
        assertEquals("en", AskEngine.answerLanguage("What did the team decide about the battery?", "zh-TW"))
        assertEquals("zh-TW", AskEngine.answerLanguage("搬家要準備哪些事？", "zh-TW"))
        assertEquals("zh-CN", AskEngine.answerLanguage("搬家要准备哪些事？", "zh-CN"))   // the interface's script
        assertEquals("zh-TW", AskEngine.answerLanguage("尾牙的頭獎是什麼？", "en"))
        // Too short or too mixed to tell: the interface's language.
        assertEquals("zh-TW", AskEngine.answerLanguage("OK?", "zh-TW"))
        assertEquals("en", AskEngine.answerLanguage("好嗎", "en"))
        assertEquals("zh-TW", AskEngine.answerLanguage("這個 project 的 deadline 是哪天？", "zh-TW"))
    }

    /** The system prompt is the first question's language (the card's); a follow-up in another language
     *  gets the card's word for its own, so the conversation's prefix stays as it is. */
    @Test fun theDirectiveComesFromTheCardOnlyWhenNeeded() {
        val q = "What did the team decide about the battery?"
        // Same language as the conversation's system prompt: the question exactly as typed.
        assertEquals(q, AskEngine.asked(card, "  $q ", "zh-TW", systemLang = AskEngine.answerLanguage(q, "zh-TW")))
        assertEquals("尾牙的頭獎是什麼？", AskEngine.asked(card, "尾牙的頭獎是什麼？", "zh-TW", "zh-TW"))
        assertEquals("en", AskEngine.answerKey(AskEngine.answerLanguage(q, "zh-TW")))   // a first English question: English system prompt
        // A follow-up in another language than the conversation's: the card's word for the answer's language.
        assertEquals(q + card.ask.answerIn.getValue("en"), AskEngine.asked(card, q, "zh-TW", systemLang = "zh-TW"))
        assertEquals(q + card.ask.answerIn.getValue("en"), AskEngine.asked(card, q, "zh-CN", systemLang = "zh-CN"))
        assertEquals("尾牙的頭獎是什麼？" + card.ask.answerIn.getValue("zh-Hant"), AskEngine.asked(card, "尾牙的頭獎是什麼？", "en", systemLang = "en"))
        // A card without directives leaves the question alone (model-agnostic: the card decides).
        val bare = card.copy(ask = card.ask.copy(answerIn = emptyMap()))
        assertEquals(q, AskEngine.asked(bare, q, "zh-TW", systemLang = "zh-TW"))
        assertTrue(card.ask.answerIn.keys.containsAll(card.ask.system.keys))
    }
}
