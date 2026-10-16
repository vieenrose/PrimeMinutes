package studio.primeminutes.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class YouTubeFileNameTest {
    /** A re-resolve (after a lost network, or Cancel then the same video again) gets a new signed URL:
     *  the file name must not change with it, or the `.part` is missed and the download restarts at 0. */
    @Test fun aNewStreamUrlKeepsTheSameFile() {
        val a = YouTube.fileName("ZK3O402wf1c_139", "https://rr1.googlevideo.com/videoplayback?expire=1&sig=AAA", "m4a")
        val b = YouTube.fileName("ZK3O402wf1c_139", "https://rr5.googlevideo.com/videoplayback?expire=2&sig=BBB", "m4a")
        assertEquals("youtube_ZK3O402wf1c_139.m4a", a)
        assertEquals(a, b)
    }

    @Test fun anotherStreamOfTheVideoIsAnotherFile() {
        assertNotEquals(YouTube.fileName("vid_139", "u", "m4a"), YouTube.fileName("vid_140", "u", "m4a"))
    }

    @Test fun noKeyFallsBackToTheUrlAndUnsafeCharsAreReplaced() {
        assertEquals("youtube_${"u".hashCode().toUInt()}.webm", YouTube.fileName(null, "u", "webm"))
        assertEquals("youtube_${"u".hashCode().toUInt()}.webm", YouTube.fileName(" ", "u", "webm"))
        assertEquals("youtube_a_b_1.m4a", YouTube.fileName("a/b_1", "u", "m4a"))
    }
}
