// macOS host test: M4 background check — scheduling rule, checked set (paused skipped), new-episode diff per
// channel (announced once, rekey-safe), and the DEV baseline rollback.
import Foundation

@main struct BackgroundCheckTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }
        func ep(_ g: String) -> FeedEpisode { FeedEpisode(guid: g, title: "T" + g, audioUrl: "https://x/\(g).mp3", published: 1_700_000_000) }
        func feed(_ gs: [String]) -> Feed { Feed(title: "Show", artist: "", artworkUrl: "", episodes: gs.map(ep)) }
        let now: Int64 = 1_800_000_000
        let a = SubscriptionLogic.baseline("https://a/feed", feed(["2", "1"]), country: "tw", now: now)
        var b = SubscriptionLogic.baseline("https://b/feed", feed(["9"]), country: "tw", now: now); b.paused = true

        // Scheduling: setting on and something not paused.
        check(BackgroundCheckLogic.shouldSchedule(enabled: false, subs: [a]), false, "off: no schedule")
        check(BackgroundCheckLogic.shouldSchedule(enabled: true, subs: []), false, "no subs: no schedule")
        check(BackgroundCheckLogic.shouldSchedule(enabled: true, subs: [b]), false, "only paused: no schedule")
        check(BackgroundCheckLogic.shouldSchedule(enabled: true, subs: [a, b]), true, "active sub: schedule")
        check(BackgroundCheckLogic.interval, 6 * 3600, "about 6 h")
        check(BackgroundCheckLogic.budget < 30, true, "inside the refresh budget")
        check(BackgroundCheckLogic.toCheck([a, b]), [a.id], "paused skipped")

        // Diff: a new episode is found once.
        let a2 = SubscriptionLogic.merge(a, feed(["3", "2", "1"]), now: now)
        check(BackgroundCheckLogic.found(before: [a, b], after: [a.id: a2]), [.init(id: a.id, title: "Show", episodes: ["T3"])], "new episode found")
        check(BackgroundCheckLogic.found(before: [a2], after: [a.id: SubscriptionLogic.merge(a2, feed(["3", "2", "1"]), now: now)]), [], "not announced twice")
        let a3 = SubscriptionLogic.merge(a2, feed(["5", "4", "3", "2", "1"]), now: now)
        check(BackgroundCheckLogic.found(before: [a2], after: [a.id: a3]).first?.episodes, ["T5", "T4"], "only the newer ones, grouped")
        check(BackgroundCheckLogic.found(before: [a], after: [a.id: a]), [], "304 / unchanged: nothing")
        var bNew = SubscriptionLogic.merge(b, feed(["10", "9"]), now: now); bNew.paused = true
        check(BackgroundCheckLogic.found(before: [b], after: [b.id: bNew]), [], "paused: never notified")
        let moved = SubscriptionLogic.rekey(a2, to: "https://c/feed")
        check(BackgroundCheckLogic.found(before: [a], after: [a.id: moved]).first?.id, moved.id, "rekeyed: opens the new id")
        let seen = SubscriptionLogic.markSeen(a2, guid: "3", entryId: nil)
        check(BackgroundCheckLogic.found(before: [a], after: [a.id: seen]), [], "queued meanwhile: nothing")

        // Rollback: the newest episode comes back as new on the next check.
        let r = BackgroundCheckLogic.rollBack(a)
        check(r.episodes.map(\.guid), ["1"], "rollback drops the newest"); check(r.etag == nil && r.lastModified == nil, true, "rollback clears validators")
        check(BackgroundCheckLogic.found(before: [r], after: [a.id: SubscriptionLogic.merge(r, feed(["2", "1"]), now: now)]).first?.episodes, ["T2"], "rolled back episode found again")

        print(failures == 0 ? "background check tests PASS" : "\(failures) FAILED"); exit(failures == 0 ? 0 : 1)
    }
}
