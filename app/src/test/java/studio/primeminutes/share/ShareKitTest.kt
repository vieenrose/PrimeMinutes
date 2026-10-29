package studio.primeminutes.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.export.Cites
import studio.primeminutes.core.share.ShareKit
import studio.primeminutes.core.share.ShareKit.What

class ShareKitTest {
    @Test fun defaultsFollowTheSource() {
        assertEquals(What.SESSION, ShareKit.defaultWhat(online = false, hasSession = true))
        assertEquals(What.PDF, ShareKit.defaultWhat(online = true, hasSession = true))
        assertEquals(What.AUDIO, ShareKit.defaultWhat(online = false, hasSession = false))
    }

    @Test fun audioNextToThePdfIsOffWhenOnlineOrTooBig() {
        assertTrue(ShareKit.attachAudioByDefault(false, 5_000_000))
        assertFalse(ShareKit.attachAudioByDefault(true, 5_000_000))
        assertFalse(ShareKit.attachAudioByDefault(false, 120_000_000))
    }

    @Test fun itemsAndSizes() {
        assertEquals(listOf("pmin"), ShareKit.items(What.SESSION, true, "m4a").map { it.ext })
        assertEquals(listOf("pdf", "m4a"), ShareKit.items(What.PDF, true, "m4a").map { it.ext })
        assertEquals(listOf("pdf"), ShareKit.items(What.PDF, false, "m4a").map { it.ext })
        assertEquals("audio/wav", ShareKit.items(What.AUDIO, false, "wav").single().mime)
        assertEquals(1_200L, ShareKit.size(What.PDF, true, 0, 1_000, 200))
        assertEquals(200L, ShareKit.size(What.PDF, false, 0, 1_000, 200))
        assertNull(ShareKit.size(What.PDF, false, 0, 1_000, null))
        assertEquals("*/*", ShareKit.mimeOf(ShareKit.items(What.PDF, true, "m4a")))
        assertEquals("application/pdf", ShareKit.mimeOf(ShareKit.items(What.PDF, false, "m4a")))
    }

    @Test fun citedSentenceStartsAfterThePreviousOne() {
        val t = "第一句。⁠[0:01] 第二句話很長。⁠[0:05]"
        val c2 = t.lastIndexOf('[')
        assertEquals(t.indexOf("第二"), Cites.sentenceStart(t, c2, t.indexOf(']') + 1))
        assertEquals(0, Cites.sentenceStart(t, t.indexOf('['), 0))
        val e = "We move on March 7. [0:12] The budget is 3.5 percent over. [0:20]"
        assertEquals(e.indexOf("The"), Cites.sentenceStart(e, e.lastIndexOf('['), e.indexOf(']') + 1))
    }
}
