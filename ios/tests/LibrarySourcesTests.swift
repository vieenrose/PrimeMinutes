// macOS host test: library by source (kind, backfill from subscriptions, old episode_sources.json compatibility,
// channel groups, the episode line, the artwork cache's file names, the filters).
import Foundation

@main struct LibrarySourcesTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }

        // Kind: received wins; an old record without kind is a podcast; nothing = meeting.
        let old = EpisodeSource(jobId: "A", sourceUrl: "https://x/1.mp3", guid: "guid:g1", seriesId: "podcast-1")
        check(LibrarySources.kind(received: false, source: nil), .meeting, "no source = meeting")
        check(LibrarySources.kind(received: false, source: old), .podcast, "old record = podcast")
        check(LibrarySources.kind(received: true, source: old), .received, "received wins")
        check(LibrarySources.kind(received: false, source: EpisodeSource(jobId: "B", sourceUrl: "", kind: "podcast")), .podcast, "explicit podcast")
        check(LibrarySources.kind(received: false, source: EpisodeSource(jobId: "B", sourceUrl: "", kind: "opened")), .meeting, "unknown kind = meeting")

        // Filters.
        check(SourceFilter.allCases.map(\.rawValue), ["all", "meetings", "podcasts", "received"], "filter order")
        check(SourceKind.allCases.filter { SourceFilter.all.matches($0) }.count, 3, "all matches all")
        check(SourceKind.allCases.filter { SourceFilter.podcasts.matches($0) }, [.podcast], "podcasts")
        check(SourceKind.allCases.filter { SourceFilter.meetings.matches($0) }, [.meeting], "meetings")
        check(SourceKind.allCases.filter { SourceFilter.received.matches($0) }, [.received], "received")

        // Old file (pre-redesign) decodes; a round trip keeps the new fields.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("libsrc-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = dir.appendingPathComponent("episode_sources.json")
        try? Data(#"[{"jobId":"A","sourceUrl":"https://x/1.mp3","guid":"guid:g1","seriesId":"podcast-1"},{"jobId":"C","sourceUrl":"https://y/9.mp3"}]"#.utf8).write(to: f)
        let read = LibrarySources.read(f)
        check(read.count, 2, "old file read"); check(read[0], old, "old record fields")
        check(LibrarySources.read(dir.appendingPathComponent("missing.json")), [], "missing file = empty")

        // Backfill from subscriptions: fills, never overwrites; reports a change once.
        let ch = ChannelInfo(seriesId: "podcast-1", title: "Show", artist: "Host", artworkUrl: "https://x/art.jpg",
                             episodes: [(guid: "guid:g1", audioUrl: "https://x/1.mp3", published: 1_761_000_000, durationSec: 3100)])
        let (b1, c1) = LibrarySources.backfill(read, channels: [ch])
        check(c1, true, "backfill changed")
        check(b1[0].channelTitle, "Show", "title"); check(b1[0].channelArtist, "Host", "artist"); check(b1[0].artworkUrl, "https://x/art.jpg", "artwork")
        check(b1[0].published, 1_761_000_000, "published"); check(b1[0].durationSec, 3100, "duration")
        check(b1[1], read[1], "no series: untouched")
        let (_, c2) = LibrarySources.backfill(b1, channels: [ch]); check(c2, false, "backfill idempotent")
        var mine = old; mine.channelTitle = "Snapshot"
        check(LibrarySources.backfill([mine], channels: [ch]).0[0].channelTitle, "Snapshot", "snapshot kept over subscription")
        var byUrl = old; byUrl.guid = nil
        check(LibrarySources.backfill([byUrl], channels: [ch]).0[0].durationSec, 3100, "episode matched by URL")
        check(LibrarySources.backfill(read, channels: []).1, false, "unsubscribed: nothing to fill")
        LibrarySources.write(b1, to: f); check(LibrarySources.read(f), b1, "round trip")

        // Index: the latest record per job wins.
        var again = old; again.channelTitle = "Later"
        check(LibrarySources.index([old, again])["A"]?.channelTitle, "Later", "latest per job")

        // Groups: by series, title order (locale-aware), "Other podcasts" last; artwork from any member.
        let u = (0..<5).map { _ in UUID() }
        func src(_ s: String?, _ t: String?, _ a: String? = nil) -> EpisodeSource { EpisodeSource(jobId: "", sourceUrl: "", seriesId: s, channelTitle: t, artworkUrl: a) }
        let g = LibrarySources.groups([(u[0], src("p-b", "Beta")), (u[1], src(nil, nil)), (u[2], src("p-a", "alpha", "https://a")), (u[3], src("p-b", "Beta", "https://b")), (u[4], nil)], otherTitle: "Other")
        check(g.map(\.key), ["p-a", "p-b", "other"], "group order")
        check(g.map(\.title), ["alpha", "Beta", "Other"], "group titles")
        check(g[1].sessionIds, [u[0], u[3]], "members keep order")
        check(g[1].artworkUrl, "https://b", "artwork from a later member")
        check(g[2].sessionIds, [u[1], u[4]], "no series → other")
        check(g[2].artworkUrl, nil, "other has no artwork")
        check(LibrarySources.groups([(u[0], src("p-x", ""))], otherTitle: "Other")[0].title, "Other", "untitled channel")

        // Episode line.
        let d = Date(timeIntervalSince1970: 1_000)
        let l1 = LibrarySources.episodeLine(channel: "Show", published: 2_000, fallbackDate: d, seconds: 60)
        check(l1.channel, "Show", "line channel"); check(l1.date, Date(timeIntervalSince1970: 2_000), "line published"); check(l1.seconds, 60, "line seconds")
        let l2 = LibrarySources.episodeLine(channel: "  ", published: 0, fallbackDate: d, seconds: 0)
        check(l2.channel, nil, "blank channel dropped"); check(l2.date, d, "fallback date"); check(l2.seconds, nil, "zero length dropped")

        // Artwork file names: the series id, else a URL hash; never a path piece from the network.
        check(LibrarySources.artworkFileName(seriesId: "podcast-1a2b", url: "https://x"), "podcast-1a2b.img", "by series")
        check(LibrarySources.artworkFileName(seriesId: "../evil", url: "https://x").hasPrefix("url-"), true, "unsafe id → hash")
        check(LibrarySources.artworkFileName(seriesId: nil, url: "https://x"), LibrarySources.artworkFileName(seriesId: "", url: "https://x"), "hash stable")
        check(LibrarySources.artworkFileName(seriesId: nil, url: "https://x") != LibrarySources.artworkFileName(seriesId: nil, url: "https://y"), true, "hash differs")

        try? FileManager.default.removeItem(at: dir)
        print(failures == 0 ? "library sources: all passed" : "library sources: \(failures) FAILED")
        exit(failures == 0 ? 0 : 1)
    }
}
