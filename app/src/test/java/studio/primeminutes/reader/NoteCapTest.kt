package studio.primeminutes.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.primeminutes.core.reader.AgentEvent
import studio.primeminutes.core.reader.DropReason
import studio.primeminutes.core.reader.Line
import studio.primeminutes.core.reader.MeetingReader
import studio.primeminutes.core.reader.ReaderLlm

/** The per-window note cap keeps decisions and actions over the rest (a closing to-do round-up
 *  used to lose every ACTION because it is read last). */
class NoteCapTest {

    private class OneReply(private val reply: String) : ReaderLlm {
        private var n = 0
        override fun tokenize(text: String, special: Boolean) = text.codePoints().toArray()
        override fun append(tokens: IntArray): Int { n += tokens.size; return n }
        override fun generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Unit) = reply + stop
        override fun seqLength() = n
        override fun reset() { n = 0 }
    }

    @Test
    fun capKeepsDecisionsAndActions() {
        val reply = listOf(
            "NOTE [0:00] (OPEN-ISSUE) 尾牙定在十二月十九號",
            "NOTE [0:20] (PROPOSAL) 建議減少致詞時間",
            "NOTE [1:00] (DECISION) 表演請外面的樂團",
            "NOTE [1:40] (NUMBER) 抽獎預算十五萬",
            "NOTE [2:00] (DECISION) 三萬作為預備金",
            "NOTE [3:00] (ACTION) 週五前定案菜單",
            "NOTE [3:00] (ACTION) 下週一發問卷",
            "NOTE [3:00] (ACTION) 十二月一號前追蹤預算",
            "NOTE [3:00] (ACTION) 下週三前確認遊覽車",
        ).joinToString("\n")
        val capped = ArrayList<String>()
        // The cap mechanism at upstream's 6 (the shipped card keeps 12).
        val reader = MeetingReader(OneReply(reply), "sys", events = {
            if (it is AgentEvent.NoteDropped && it.reason == DropReason.CAP) capped += it.line
        }, pc = studio.primeminutes.core.models.ModelCard.DEFAULT.reader.copy(maxNotes = 6))
        reader.start()
        listOf(0, 20, 60, 100, 120, 180).forEach { reader.offer(Line(it, "S1", "話$it")) }
        reader.finish()

        assertEquals(
            listOf("DECISION", "DECISION", "ACTION", "ACTION", "ACTION", "ACTION"),
            reader.journal.map { it.tag },
        )
        assertEquals(listOf("1:00", "2:00", "3:00", "3:00", "3:00", "3:00"), reader.journal.map { it.ts })
        assertEquals(3, capped.size)
    }

    @Test
    fun theOtherTypeDashIsATagNotText() {
        // Seen on the emulator (E4B v2, English meeting): every note read "(-) Next meeting on Thursday".
        // "-" is the prompt's "other" type; the note pattern took only word tags, so "(-)" leaked into the text.
        for (card in listOf(studio.primeminutes.core.models.ModelCard.DEFAULT,
            studio.primeminutes.core.models.ModelCard.load("gemma4-meeting-agent-zh-v2"))) {
            val reader = MeetingReader(OneReply("NOTE [0:40] (-) Next meeting on Thursday."), "sys", events = {}, pc = card.reader)
            reader.start(); reader.offer(Line(40, "S1", "see you Thursday")); reader.finish()
            assertEquals(listOf("-"), reader.journal.map { it.tag })
            assertEquals(listOf("Next meeting on Thursday."), reader.journal.map { it.text })
        }
    }
}
