// macOS host test: podcast subscriptions (feed parser, identity chain, baseline/merge, eviction, rekey, redirects,
// HTTP classes, disk-full classifier, JSON compatibility with Android's subscriptions.json).
import Foundation

@main struct SubscriptionsTests {
    static func main() async throws {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }
        let rss = """
        <?xml version="1.0"?>
        <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:atom="http://www.w3.org/2005/Atom"><channel>
        <title>Show</title><itunes:author>Host</itunes:author><itunes:image href="https://x/art.jpg"/>
        <image><url>https://x/other.jpg</url><title>ignored</title></image>
        <item><title>Ep 2</title><guid>g2</guid><pubDate>Tue, 20 Oct 2026 08:00:00 +0000</pubDate><itunes:duration>51:40</itunes:duration>
         <enclosure url="https://x/2.mp3" type="audio/mpeg" length="73400000"/></item>
        <item><title>Ep 1</title><link>https://x/ep1</link><pubDate>Mon, 19 Oct 2026 08:00:00 +0000</pubDate><itunes:duration>3100</itunes:duration>
         <enclosure url="https://x/1.mp3" type="audio/mpeg"/></item>
        <item><title>Video</title><guid>v</guid><enclosure url="https://x/v.mp4" type="video/mp4"/></item>
        <item><title>NoIds</title><atom:link rel="enclosure" type="audio/mpeg" href="https://x/0.mp3" length="5"/></item>
        </channel></rss>
        """
        let f = try FeedParser.parse(Data(rss.utf8))
        check(f.title, "Show", "channel title"); check(f.artist, "Host", "author"); check(f.artworkUrl, "https://x/art.jpg", "artwork")
        check(f.episodes.map(\.guid), ["guid:g2", "link:https://x/ep1", "url:https://x/0.mp3"], "identity chain")
        check(f.episodes[1].durationSec, 3100, "seconds duration"); check(f.episodes[0].durationSec, 51 * 60 + 40, "mm:ss duration")
        check(f.episodes[0].lengthBytes, 73400000, "length"); check(f.episodes[2].lengthBytes, 5, "atom length")
        check(f.episodes[0].published > f.episodes[1].published, true, "dates")
        check(SubscriptionLogic.identity(guid: "", link: "", audioUrl: "", title: "t", pubDate: "d").hasPrefix("sha1:"), true, "sha1 fallback")
        check(SubscriptionLogic.sha1Hex("abc"), "a9993e364706816aba3e25717850c26c9cd0d89d", "sha1 vector")
        let items = (1...80).map { "<item><guid>\($0)</guid><enclosure url=\"https://x/\($0).mp3\" type=\"audio/mpeg\"/></item>" }.joined()
        check(try FeedParser.parse(Data("<rss><channel><title>t</title>\(items)</channel></rss>".utf8)).episodes.count, 60, "60 cap")
        check((try? FeedParser.parse(Data("<html><body>hi</body></html>".utf8))) == nil, true, "html is not a feed")

        let now: Int64 = 1_800_000_000
        let s = SubscriptionLogic.baseline("https://x/feed", f, country: "tw", now: now)
        check(s.newEpisodes.isEmpty, true, "baseline: nothing new")
        var added = f; added.episodes = [FeedEpisode(guid: "guid:g3", title: "Ep 3", audioUrl: "https://x/3.mp3", published: 1_790_000_000)] + f.episodes
        let m = SubscriptionLogic.merge(s, added, now: now)
        check(m.newEpisodes.map(\.guid), ["guid:g3"], "new after merge")
        let seen = SubscriptionLogic.markSeen(m, guid: "guid:g3", entryId: "E")
        let back = SubscriptionLogic.merge(SubscriptionLogic.merge(seen, f, now: now), added, now: now)
        check(back.newEpisodes.isEmpty, true, "seen never new again")
        let fut = SubscriptionLogic.merge(SubscriptionLogic.baseline("u", Feed(title: "t", artist: "", artworkUrl: "", episodes: []), country: "", now: now),
                                          Feed(title: "t", artist: "", artworkUrl: "", episodes: [FeedEpisode(guid: "guid:f", title: "f", audioUrl: "https://x/f.mp3", published: now + 86400)]), now: now)
        check(fut.newEpisodes.isEmpty, true, "future not new")
        let eps = [FeedEpisode(guid: "a", title: "", audioUrl: ""), FeedEpisode(guid: "b", title: "", audioUrl: "")]
        check(SubscriptionLogic.evict(["a", "x1", "b", "x2", "x3"], eps, max: 3), ["a", "b", "x3"], "evict keeps listed")
        let r = SubscriptionLogic.rekey(s, to: "https://new/feed")
        check(r.id, SubscriptionLogic.idOf("https://new/feed"), "rekey id"); check(r.seenGuids, s.seenGuids, "rekey keeps guids"); check(r.episodes, s.episodes, "rekey keeps episodes")

        let perm: [String: (Int, String?)] = ["https://a/f": (301, "https://b/f"), "https://b/f": (308, "/g"), "https://b/g": (200, nil)]
        let h = await SubscriptionLogic.followRedirects("https://a/f") { perm[$0]! }
        check(h, .init(url: "https://b/g", status: 200, movedPermanently: true), "permanent chain")
        let temp: [String: (Int, String?)] = ["https://a/f": (301, "https://b/f"), "https://b/f": (302, "https://c/f"), "https://c/f": (200, nil)]
        check(await SubscriptionLogic.followRedirects("https://a/f") { temp[$0]! }?.movedPermanently, false, "temporary hop: no rekey")
        check(await SubscriptionLogic.followRedirects("https://a/f") { _ in (200, nil) }?.movedPermanently, false, "no redirect")
        check(await SubscriptionLogic.followRedirects("https://a/f") { $0 == "https://a/f" ? (301, "https://b/f") : (301, "https://a/f") } == nil, true, "loop")
        check(await SubscriptionLogic.followRedirects("https://a/f") { (302, $0 + "x") } == nil, true, "too many hops")
        check(await SubscriptionLogic.followRedirects("https://a/f") { _ in (301, "ftp://z/f") } == nil, true, "non-http")
        check(SubscriptionLogic.classify(404), .GONE, "404"); check(SubscriptionLogic.classify(401), .PAYWALLED, "401")
        check(SubscriptionLogic.classify(429), .RETRY_LATER, "429"); check(SubscriptionLogic.classify(304), nil, "304")
        check(SubscriptionLogic.isNoSpace(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))), true, "ENOSPC")
        check(SubscriptionLogic.isNoSpace(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError, userInfo: [NSUnderlyingErrorKey: NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))])), true, "wrapped ENOSPC")
        check(SubscriptionLogic.isNoSpace(URLError(.timedOut)), false, "network error is not disk full")

        var p = s; p.paused = true; p.checkError = "GONE"; p.etag = "e"
        let round = try JSONDecoder().decode(Subscription.self, from: JSONEncoder().encode(p))
        check(round, p, "json round trip")
        let old = try JSONDecoder().decode(Subscription.self, from: Data(#"{"feedUrl":"https://x/f","title":"t"}"#.utf8))
        check(old.paused, false, "old file paused"); check(old.seenGuids, [], "old file guids")
        // Android writes the same fields (id included, ignored on read)
        let android = try JSONDecoder().decode(Subscription.self, from: Data(#"{"id":"podcast-1","feedUrl":"https://x/f","country":"tw","title":"t","artist":"a","artworkUrl":"","addedAt":5,"lastChecked":6,"auto":false,"paused":true,"seenGuids":["g"],"episodes":[{"guid":"g","title":"e","audioUrl":"https://x/1.mp3","published":1,"durationSec":2,"lengthBytes":3,"queuedEntryId":"q"}]}"#.utf8))
        check(android.episodes.first?.queuedEntryId, "q", "android json")
        check(SubscriptionLogic.fnv1a(""), "cbf29ce484222325", "fnv empty"); check(SubscriptionLogic.fnv1a("a"), "af63dc4c8601ec8c", "fnv a")

        print(failures == 0 ? "subscriptions tests PASS" : "\(failures) FAILED"); exit(failures == 0 ? 0 : 1)
    }
}
