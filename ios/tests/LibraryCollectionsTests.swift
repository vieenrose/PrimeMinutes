// macOS host test: library collections (spec scope-redesign) — grouping/sorting of the mixed list, scope filtering,
// title normalization for suggestions, membership rules and persistence (old/missing file, round trip).
import Foundation

@main struct LibraryCollectionsTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }
        func day(_ n: Double) -> Date { Date(timeIntervalSince1970: 1_800_000_000 + n * 86_400) }
        let ids = (0..<10).map { _ in UUID() }

        // Title normalization: years, dates and numbers go; the rest must match exactly.
        check(Collections.normalizeTitle("產品週會 10/27"), "產品週會", "md date")
        check(Collections.normalizeTitle("2026年10月30日 產品週會"), "產品週會", "zh date")
        check(Collections.normalizeTitle("產品週會 #12"), "產品週會", "number")
        check(Collections.normalizeTitle("Weekly Sync 2026-10-30"), "weekly sync", "iso date, case folded")
        check(Collections.normalizeTitle("Weekly sync"), Collections.normalizeTitle("WEEKLY SYNC 3"), "case")
        check(Collections.normalizeTitle("2026/10/30"), "", "only a date → empty")
        check(Collections.baseTitle("Q4 預算檢討（2026）"), "Q 預算檢討", "base keeps words")
        check(Collections.normalizeTitle("產品週會") == Collections.normalizeTitle("產品會議"), false, "no semantic matching")

        // Suggestions: ≥ 3, outside collections, not skipped, newest title names it.
        let titles: [(id: UUID, title: String)] = [(ids[0], "產品週會 10/30"), (ids[1], "產品週會 10/23"), (ids[2], "客戶訪談"), (ids[3], "產品週會 10/16"),
                                                    (ids[4], "Standup 1"), (ids[5], "standup 2"), (ids[6], "未命名"), (ids[7], "")]
        let sg = Collections.suggestions(titles, excluded: [], skipped: [])
        check(sg.map(\.name), ["產品週會"], "one group of 3")
        check(sg.first?.ids, [ids[0], ids[1], ids[3]], "group members in order")
        check(Collections.suggestions(titles, excluded: [ids[1]], skipped: []).isEmpty, true, "a member in a collection drops below 3")
        check(Collections.suggestions(titles, excluded: [], skipped: ["產品週會"]).isEmpty, true, "skipped is permanent")
        check(Collections.suggestions(titles, excluded: [], skipped: [], min: 2).map(\.name), ["產品週會", "Standup"], "bigger group first")

        // Membership: one collection per meeting (adding moves it), delete keeps meetings, rename, prune.
        var doc = CollectionsDoc()
        doc = Collections.create(doc, id: "A", name: " 週會 ", ids: [ids[0].uuidString, ids[1].uuidString], now: day(0))
        doc = Collections.create(doc, id: "B", name: "客戶", ids: [ids[1].uuidString], now: day(1))
        check(doc.collections.map(\.name), ["週會", "客戶"], "names trimmed")
        check(doc.collections[0].members, [ids[0].uuidString], "moved out of A")
        check(Collections.collection(of: ids[1].uuidString, in: doc)?.id, "B", "now in B")
        doc = Collections.rename(doc, id: "B", name: "客戶訪談"); check(doc.collections[1].name, "客戶訪談", "rename")
        check(Collections.rename(doc, id: "B", name: "  ").collections[1].name, "客戶訪談", "blank rename ignored")
        let (pr, ch) = Collections.prune(doc, alive: [ids[1].uuidString]); check(ch, true, "prune changed"); check(pr.collections[0].members, [], "deleted member pruned")
        check(Collections.delete(doc, id: "A").collections.map(\.id), ["B"], "delete collection")

        // Grouping/sorting: All mixes loose meetings and collection rows by latest item date; episodes stay in their channel.
        let src = { (j: UUID, s: String) in EpisodeSource(jobId: j.uuidString, sourceUrl: "", seriesId: s, kind: "podcast", channelTitle: "Show " + s, artworkUrl: "https://a/" + s) }
        let items: [Collections.Item] = [
            .init(id: ids[0], date: day(10), title: "週會 3", kind: .meeting, source: nil),      // in A
            .init(id: ids[1], date: day(2), title: "訪談", kind: .meeting, source: nil),         // in B
            .init(id: ids[2], date: day(8), title: "loose", kind: .meeting, source: nil),
            .init(id: ids[3], date: day(9), title: "ep2", kind: .podcast, source: src(ids[3], "s1")),
            .init(id: ids[4], date: day(5), title: "ep1", kind: .podcast, source: src(ids[4], "s1")),
            .init(id: ids[5], date: day(7), title: "single", kind: .podcast, source: src(ids[5], "s2")),
            .init(id: ids[6], date: day(6), title: "recv", kind: .received, source: nil),
        ]
        var d2 = CollectionsDoc()
        d2 = Collections.create(d2, id: "A", name: "週會", ids: [ids[0].uuidString], now: day(0))
        d2 = Collections.create(d2, id: "B", name: "客戶", ids: [ids[1].uuidString, ids[3].uuidString], now: day(0))   // an episode cannot leave its channel
        d2 = Collections.create(d2, id: "E", name: "空", ids: [], now: day(1))
        func label(_ e: LibraryEntry) -> String { switch e { case .session(let u): return "s" + String(ids.firstIndex(of: u)!); case .collection(let r): return r.id } }
        let all = Collections.entries(items, doc: d2, source: .all, otherTitle: "Other").map { label($0.entry) }
        check(all, ["user:A", "channel:s1", "s2", "channel:s2", "s6", "user:B", "user:E"], "All mixed, latest first")
        check(Collections.entries(items, doc: d2, source: .podcasts, otherTitle: "Other").map { label($0.entry) }, ["channel:s1", "channel:s2"], "Podcasts: channels only, single-episode channel kept")
        check(Collections.entries(items, doc: d2, source: .meetings, otherTitle: "Other").map { label($0.entry) }, ["user:A", "s2", "user:B", "user:E"], "Meetings: meetings + user collections")
        check(Collections.entries(items, doc: d2, source: .received, otherTitle: "Other").map { label($0.entry) }, ["s6"], "Received: rows")
        let rows = Collections.rows(items, doc: d2, otherTitle: "Other")
        let s1 = rows.first { $0.id == "channel:s1" }!
        check(s1.sessionIds, [ids[3], ids[4]], "channel newest first"); check(s1.latestTitle, "ep2", "latest title"); check(s1.name, "Show s1", "channel name"); check(s1.artworkUrl, "https://a/s1", "artwork")
        check(rows.first { $0.id == "user:B" }!.sessionIds, [ids[1]], "episode not in a user collection")

        // New badge: seeded on first launch, then counts unseen library episodes; opening the page clears.
        var d3 = Collections.seed(d2, channels: ["s1": [ids[3].uuidString, ids[4].uuidString]])
        check(d3.seeded, true, "seeded")
        check(Collections.seed(d3, channels: ["s1": []]), d3, "seed once")
        let items2 = items + [.init(id: ids[7], date: day(11), title: "ep3", kind: .podcast, source: src(ids[7], "s1"))]
        check(Collections.rows(items2, doc: d3, otherTitle: "").first { $0.id == "channel:s1" }!.newCount, 1, "one new")
        check(Collections.rows(items2, doc: d3, otherTitle: "").first { $0.id == "channel:s2" }!.newCount, 1, "unseeded channel: all new")
        d3 = Collections.markSeen(d3, channel: "s1", ids: [ids[7].uuidString])
        check(Collections.rows(items2, doc: d3, otherTitle: "").first { $0.id == "channel:s1" }!.newCount, 0, "cleared on open")

        // Scope filtering.
        let now = day(30)
        let si = ScopeItem(id: ids[0], date: day(28), kind: .meeting, collection: "user:A", people: ["p1"])
        check(Collections.inScope(si, LibraryScope(), now: now), true, "all")
        check(LibraryScope().isAll, true, "default is all")
        check(Collections.inScope(si, LibraryScope(source: .podcasts), now: now), false, "source")
        check(Collections.inScope(si, LibraryScope(collection: "user:A"), now: now), true, "collection")
        check(Collections.inScope(si, LibraryScope(collection: "user:B"), now: now), false, "other collection")
        check(Collections.inScope(si, LibraryScope(collection: "user:A", person: "p2"), now: now), false, "person inside collection")
        check(Collections.inScope(si, LibraryScope(person: "p1", date: .week), now: now), true, "person + week")
        check(Collections.inScope(si, LibraryScope(date: .week), now: day(40)), false, "older than a week")
        check(LibraryScope(date: .month).isAll, false, "date makes a scope")

        // Persistence: no file / an old partial file load; atomic round trip.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("coll-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = dir.appendingPathComponent("collections.json")
        check(Collections.read(f), CollectionsDoc(), "missing file = empty")
        try? Data(#"{"collections":[{"id":"A","name":"週會","created":1,"members":["x"]}]}"#.utf8).write(to: f)
        let partial = Collections.read(f)
        check(partial.collections.map(\.members), [["x"]], "partial file"); check(partial.seeded, false, "default seeded"); check(partial.skipped, [], "default skipped")
        try? Data("not json".utf8).write(to: f); check(Collections.read(f), CollectionsDoc(), "corrupt = empty")
        var full = d3; full.skipped = ["k"]
        Collections.write(full, to: f); check(Collections.read(f), full, "round trip")

        try? FileManager.default.removeItem(at: dir)
        print(failures == 0 ? "library collections: all passed" : "library collections: \(failures) FAILED")
        exit(failures == 0 ? 0 : 1)
    }
}
