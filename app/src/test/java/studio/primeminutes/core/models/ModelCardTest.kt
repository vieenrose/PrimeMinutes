package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import studio.primeminutes.core.reader.Note

class ModelCardTest {
    private val card = ModelCard.DEFAULT

    @Test fun everyShippedModelHasACard() {
        LlmRegistry.ALL.forEach { assert(it.card.name.startsWith("gemma4-meeting-agent-zh")) { it.id } }
        assertEquals("gemma4-meeting-agent-zh-v2", LlmRegistry.byId(LlmRegistry.E4B_MOBILE_V2_ID).card.name)
    }

    /** Since 2026-10-19 the mobile-v2 card has mobile-v1's values (prose budget 1600, 12 notes per window,
     *  segmented prose for long journals: docs/model-eval-mobile-v2.md §10, §11); only its name differs. */
    @Test fun mobileV2CardIsTheV1Card() {
        val v2 = LlmRegistry.byId(LlmRegistry.E4B_MOBILE_V2_ID).card
        assertEquals(12, v2.reader.maxNotes)
        assertEquals(12, card.reader.maxNotes)
        assertEquals(1600, v2.prose.maxTokens)
        assertEquals(1600, v2.forLanguage("en").prose.maxTokens)
        assertEquals(card.copy(name = v2.name).toString(), v2.toString())
    }

    /** Every shipped model: one prose reply of 1600 tokens up to a 2000-token journal, then parts of about
     *  600 tokens of notes (at most 4), with a segment prompt in each prompt language (§11). */
    @Test fun shippedCardsSegmentLongJournals() {
        LlmRegistry.ALL.forEach { spec ->
            for (lang in listOf("zh", "en")) {
                val p = spec.card.forLanguage(lang).prose
                assertEquals(spec.id, 1600, p.maxTokens)
                assertEquals(spec.id, 2000, p.segmentAbove)
                assertEquals(spec.id, 600, p.segmentTokens)
                assertEquals(spec.id, 4, p.segmentMax)
                assertNotNull(spec.id, p.segmentPrompt)
                assert(p.segmentPrompt!!.contains("{notes}") && p.segmentPrompt.contains("{from}") && p.segmentPrompt.contains("{to}"))
            }
            assert(spec.card.forLanguage("en").prose.segmentPrompt!!.startsWith("Here are"))
        }
    }

    /** Static acceptance check (docs/model-acceptance-checklist.md): every shipped model's card fits the
     *  context its graph is loaded with, and its turns can end. A window opens only with room for two
     *  windows, a reading turn and slack (MeetingReader.openWindow): a budget that cannot hold that
     *  restarts before every window forever. */
    @Test fun budgetsFitTheContextAndTurnsCanStop() {
        LlmRegistry.ALL.forEach { spec ->
            val c = spec.card
            assert(c.budget.ctxBudget <= spec.maxCtx) { "${spec.id}: ctx budget ${c.budget.ctxBudget} > graph ${spec.maxCtx}" }
            assert(2 * c.budget.windowTokens + c.reader.readMax + 600 <= c.budget.ctxBudget) { "${spec.id}: a window cannot fit" }
            assert(c.budget.restartBudget + c.budget.windowTokens + c.reader.readMax <= c.budget.ctxBudget) { "${spec.id}: restart journal too big" }
            assert(c.prose.maxTokens < spec.maxCtx && c.title.maxTokens < spec.maxCtx && c.ask.answerTokens < spec.maxCtx)
            assert(c.template.stopIds.isNotEmpty()) { "${spec.id}: no stop id, a turn would run to its token cap" }
            assert(c.reader.stop.isNotBlank())
        }
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
        assertEquals(1600, card.prose.maxTokens)
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
