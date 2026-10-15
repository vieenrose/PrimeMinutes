package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import studio.primeminutes.core.reader.Note

class ModelCardTest {
    private val card = ModelCard.DEFAULT

    @Test fun everyShippedModelHasACard() {
        LlmRegistry.ALL.forEach { assertEquals("gemma4-meeting-agent-zh", it.card.name) }
    }

    /** The values the reader, title, prose and Ask used before they moved to the card. */
    @Test fun shippedCardKeepsTheTrainedValues() {
        assertEquals(setOf(1, 50, 106), card.template.stopIds)
        assertEquals(SamplerProfile(40, 0.95f, 0.2f), card.sampler)
        assertEquals(1500, card.budget.windowTokens)
        assertEquals(4096, card.budget.ctxBudget)
        assertEquals(1200, card.budget.restartBudget)
        assertEquals(3900, card.reader.notesChars)
        assertEquals("## 逐字稿片段 3\n", card.reader.windowHeader(3))
        assertEquals("\n（另有 2 則較早的筆記未列出）", card.reader.omitted(2))
        assertEquals("以下是一場會議的筆記：\n\nN\n\n為這場會議寫一個標題，不超過 20 個字。只輸出標題。", card.title.render("N"))
        assertEquals(48, card.title.maxTokens)
        assertEquals(600, card.prose.maxTokens)
        assertEquals(320, card.ask.answerTokens)
        assertEquals(setOf("zh-Hans", "zh-Hant", "en"), card.ask.system.keys)
        assertNotNull(card.title.label)
        assertEquals("尾牙", "標題：尾牙".replace(card.title.label!!, ""))
    }

    @Test fun proposalGuardAndMinutes() {
        val r = card.reader
        assertEquals("PROPOSAL", r.reclassify(Note(1, 1, "0:10", "DECISION", "建議改到週五")).tag)
        assertEquals("DECISION", r.reclassify(Note(1, 1, "0:10", "DECISION", "建議改到週五，大家同意")).tag)
        assertEquals("NUMBER", r.reclassify(Note(1, 1, "0:10", "NUMBER", "建議預算三萬")).tag)
        val m = r.minutes(listOf(Note(1, 1, "0:10", "ACTION", "小王訂餐廳。")))
        assertEquals("【決議事項】\n- 無\n【待辦與負責人】\n- 小王訂餐廳 [0:10]\n【保留與未決】\n- 無\n【討論要點】\n- 無\n【重要數字】\n- 無", m)
    }
}
