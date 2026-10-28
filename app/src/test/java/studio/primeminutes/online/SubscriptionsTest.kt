package studio.primeminutes.online

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubscriptionsTest {
    private val rss = """<?xml version="1.0"?>
<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:atom="http://www.w3.org/2005/Atom"><channel>
<title>Show</title><itunes:author>Host</itunes:author><itunes:image href="https://x/art.jpg"/>
<image><url>https://x/other.jpg</url><title>ignored</title></image>
<item><title>Ep 2</title><guid>g2</guid><pubDate>Tue, 20 Oct 2026 08:00:00 +0000</pubDate><itunes:duration>51:40</itunes:duration>
 <enclosure url="https://x/2.mp3" type="audio/mpeg" length="73400000"/></item>
<item><title>Ep 1</title><link>https://x/ep1</link><pubDate>Mon, 19 Oct 2026 08:00:00 +0000</pubDate><itunes:duration>3100</itunes:duration>
 <enclosure url="https://x/1.mp3" type="audio/mpeg"/></item>
<item><title>Video</title><guid>v</guid><enclosure url="https://x/v.mp4" type="video/mp4"/></item>
<item><title>NoIds</title><atom:link rel="enclosure" type="audio/mpeg" href="https://x/0.mp3" length="5"/></item>
</channel></rss>"""

    private fun feed() = FeedParser.parse(rss.byteInputStream())

    @Test fun parsesChannelAndIdentityChain() {
        val f = feed()
        assertEquals("Show", f.title); assertEquals("Host", f.artist); assertEquals("https://x/art.jpg", f.artworkUrl)
        assertEquals(listOf("guid:g2", "link:https://x/ep1", "url:https://x/0.mp3"), f.episodes.map { it.guid })
        assertEquals(3100, f.episodes[1].durationSec); assertEquals(51 * 60 + 40, f.episodes[0].durationSec)
        assertEquals(73400000L, f.episodes[0].lengthBytes); assertEquals(5L, f.episodes[2].lengthBytes)
        assertTrue(f.episodes[0].published > f.episodes[1].published)
    }

    @Test fun titleHashWhenNoIds() {
        assertTrue(SubscriptionLogic.identity("", "", "", "t", "d").startsWith("sha1:"))
    }

    @Test fun capsEpisodes() {
        val items = (1..80).joinToString("") { "<item><guid>$it</guid><enclosure url=\"https://x/$it.mp3\" type=\"audio/mpeg\"/></item>" }
        assertEquals(60, FeedParser.parse("<rss><channel><title>t</title>$items</channel></rss>".byteInputStream()).episodes.size)
    }

    @Test(expected = Exception::class) fun htmlIsNotAFeed() { FeedParser.parse("<html><body>hi</body></html>".byteInputStream()) }

    @Test fun baselineMarksAllSeenAndNewOnlyAfter() {
        val f = feed(); val now = 1_800_000_000L
        val s = SubscriptionLogic.baseline("https://x/feed", f, "tw", now)
        assertTrue(s.newEpisodes.isEmpty())
        val added = f.copy(episodes = listOf(FeedEpisode("guid:g3", "Ep 3", "https://x/3.mp3", 1_790_000_000)) + f.episodes)
        val m = SubscriptionLogic.merge(s, added, now)
        assertEquals(listOf("guid:g3"), m.newEpisodes.map { it.guid })
        // disappears then reappears: still new until seen; once seen, never new again
        val seen = SubscriptionLogic.markSeen(m, "guid:g3", "20261020-083011")
        val gone = SubscriptionLogic.merge(seen, f, now); val back = SubscriptionLogic.merge(gone, added, now)
        assertTrue(back.newEpisodes.isEmpty())
    }

    @Test fun futureEpisodesAreNotNew() {
        val now = 1_800_000_000L
        val s = SubscriptionLogic.baseline("u", Feed("t", "", "", emptyList()), "", now)
        val m = SubscriptionLogic.merge(s, Feed("t", "", "", listOf(FeedEpisode("guid:f", "f", "https://x/f.mp3", now + 86400))), now)
        assertTrue(m.newEpisodes.isEmpty())
    }

    @Test fun evictionKeepsListedGuids() {
        val eps = listOf(FeedEpisode("a", "", ""), FeedEpisode("b", "", ""))
        val seen = listOf("a", "x1", "b", "x2", "x3")
        assertEquals(listOf("a", "b", "x3"), SubscriptionLogic.evict(seen, eps, max = 3))
        assertEquals(seen, SubscriptionLogic.evict(seen, eps, max = 10))
    }

    @Test fun rekeyKeepsGuidsAndEpisodes() {
        val s = SubscriptionLogic.baseline("https://old/feed", feed(), "", 1)
        val r = SubscriptionLogic.rekey(s, "https://new/feed")
        assertEquals("https://new/feed", r.feedUrl); assertFalse(r.id == s.id)
        assertEquals(s.seenGuids, r.seenGuids); assertEquals(s.episodes, r.episodes)
        assertEquals(SubscriptionLogic.idOf("https://new/feed"), r.id)
    }

    @Test fun redirects() {
        val perm = mapOf("https://a/f" to (301 to "https://b/f"), "https://b/f" to (308 to "/g"), "https://b/g" to (200 to null))
        val h = SubscriptionLogic.followRedirects("https://a/f") { perm.getValue(it) }!!
        assertEquals("https://b/g", h.url); assertTrue(h.movedPermanently); assertEquals(200, h.status)
        val temp = mapOf("https://a/f" to (301 to "https://b/f"), "https://b/f" to (302 to "https://c/f"), "https://c/f" to (200 to null))
        assertFalse(SubscriptionLogic.followRedirects("https://a/f") { temp.getValue(it) }!!.movedPermanently)
        val none = SubscriptionLogic.followRedirects("https://a/f") { 200 to null }!!
        assertFalse(none.movedPermanently)
        assertNull(SubscriptionLogic.followRedirects("https://a/f") { if (it == "https://a/f") 301 to "https://b/f" else 301 to "https://a/f" })
        assertNull(SubscriptionLogic.followRedirects("https://a/f") { 302 to "${it}x" })
        assertNull(SubscriptionLogic.followRedirects("https://a/f") { 301 to "ftp://z/f" })
        assertEquals(304, SubscriptionLogic.followRedirects("https://a/f") { 304 to null }!!.status)
    }

    @Test fun classify() {
        assertNull(SubscriptionLogic.classify(200)); assertNull(SubscriptionLogic.classify(304))
        assertEquals(CheckError.GONE, SubscriptionLogic.classify(404)); assertEquals(CheckError.GONE, SubscriptionLogic.classify(410))
        assertEquals(CheckError.PAYWALLED, SubscriptionLogic.classify(401)); assertEquals(CheckError.RETRY_LATER, SubscriptionLogic.classify(429))
    }

    @Test fun noSpaceByMessageOnly() {
        assertTrue(SubscriptionLogic.isNoSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(SubscriptionLogic.isNoSpace(RuntimeException(java.io.IOException("No space left on device"))))
        assertFalse(SubscriptionLogic.isNoSpace(java.net.SocketTimeoutException("timeout")))
        assertFalse(SubscriptionLogic.isNoSpace(java.io.IOException("Connection reset")))
    }

    @Test fun jsonRoundTripAndOldFileTolerance() {
        val s = SubscriptionLogic.baseline("https://x/f", feed(), "tw", 5).copy(paused = true, checkError = "GONE", etag = "e")
        assertEquals(s, Subscription.fromJson(JSONObject(s.toJson().toString())))
        val old = Subscription.fromJson(JSONObject("""{"feedUrl":"https://x/f","title":"t"}"""))
        assertFalse(old.paused); assertTrue(old.seenGuids.isEmpty()); assertEquals(0L, old.lastChecked)
    }

    @Test fun fnvMatchesKnownVector() {
        assertEquals("cbf29ce484222325", SubscriptionLogic.fnv1a(""))
        assertEquals("af63dc4c8601ec8c", SubscriptionLogic.fnv1a("a"))
    }
}
