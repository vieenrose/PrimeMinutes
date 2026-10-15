package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTemplateTest {
    /** The pieces the reader and Ask were built with before the template moved to the spec (note §4.1). */
    @Test fun gemma4PiecesAreTheTrainedOnes() {
        val t = ChatTemplate.GEMMA4
        assertEquals("<bos><|turn>system\n", t.p0)
        assertEquals("<turn|>\n<|turn>user\n", t.p1)
        assertEquals("<turn|>\n<|turn>model\n", t.p2)
        assertEquals("<turn|>\n<|turn>user\n", t.p3)
        assertEquals("<bos><|turn>user\n", t.userFirst)
        assertEquals("<turn|>", t.end)
        assertEquals(2, t.bosId)
        assertEquals(setOf(1, 50, 106), t.stopIds)
    }
}
