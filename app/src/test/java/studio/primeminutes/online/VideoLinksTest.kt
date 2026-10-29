package studio.primeminutes.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLinksTest {
    @Test fun youtubeLinksAreVideoLinks() {
        assertTrue(VideoLinks.isVideoLink("https://www.youtube.com/watch?v=jNQXAC9IVRw"))
        assertTrue(VideoLinks.isVideoLink("Watch this: https://youtu.be/jNQXAC9IVRw"))
        assertTrue(VideoLinks.isVideoLink("m.youtube.com/watch?v=x"))
    }

    @Test fun peertubeLinksAreVideoLinks() {
        assertTrue(VideoLinks.isVideoLink("https://framatube.org/w/9c9de5e8-0a1e-484a-b099-e80766180a6d"))
        assertTrue(VideoLinks.isVideoLink("https://peertube.example/videos/watch/9c9de5e8-0a1e-484a"))
    }

    @Test fun otherTextIsNot() {
        assertFalse(VideoLinks.isVideoLink(null))
        assertFalse(VideoLinks.isVideoLink(""))
        assertFalse(VideoLinks.isVideoLink("funny cat videos"))
        assertFalse(VideoLinks.isVideoLink("https://feeds.example.com/podcast.xml"))
        assertFalse(VideoLinks.isVideoLink("https://example.com/w/x"))
    }
}
