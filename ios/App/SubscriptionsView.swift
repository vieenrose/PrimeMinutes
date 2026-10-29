import SwiftUI

/// Where an imported podcast episode came from (Android source.json): job id (== the session's id), audio URL, guid,
/// and the channel snapshot taken at import (LibrarySources). Kept in Application Support/episode_sources.json, so a
/// subscribed episode is known as imported after its job ends, and the library can group it by channel offline.
enum EpisodeSources {
    typealias Source = EpisodeSource
    private static let url = JobQueue.support.appendingPathComponent("episode_sources.json")
    private static let lock = NSLock()
    static func all() -> [Source] { lock.lock(); defer { lock.unlock() }; return LibrarySources.read(url) }
    static func add(_ s: Source) {
        lock.lock(); defer { lock.unlock() }
        var a = LibrarySources.read(url); a.append(s)
        LibrarySources.write(a, to: url)
    }
    /// Once per launch: episodes imported before the channel snapshot get it from the subscriptions still here.
    static func backfill(_ subs: [Subscription]) {
        lock.lock(); defer { lock.unlock() }
        let chans = subs.map { ChannelInfo(seriesId: $0.id, title: $0.title, artist: $0.artist, artworkUrl: $0.artworkUrl,
                                           episodes: $0.episodes.map { (guid: $0.guid, audioUrl: $0.audioUrl, published: $0.published, durationSec: $0.durationSec) }) }
        let (out, changed) = LibrarySources.backfill(LibrarySources.read(url), channels: chans)
        if changed { LibrarySources.write(out, to: url); StatusLog.add("episode sources backfilled") }
    }
}

/// The saved subscriptions (Application Support/subscriptions.json) and the check / subscribe / queue actions.
@MainActor final class SubscriptionStore: ObservableObject {
    static let shared = SubscriptionStore()
    @Published private(set) var subs: [Subscription] = []
    @Published private(set) var checking: Set<String> = []
    /// One-shot notice: the feed moved for good (the new id).
    @Published var moved: String?
    private var justQueued: Set<UUID> = []
    private let url = JobQueue.support.appendingPathComponent("subscriptions.json")
    private struct File: Codable { var version = 1; var subscriptions: [Subscription] }

    private init() { subs = (try? JSONDecoder().decode(File.self, from: Data(contentsOf: url)))?.subscriptions ?? [] }
    private func save() { try? JSONEncoder().encode(File(subscriptions: subs)).write(to: url, options: .atomic) }
    func byId(_ id: String) -> Subscription? { subs.first { $0.id == id } }
    private func put(_ s: Subscription, replacing id: String? = nil) {
        let key = id ?? s.id
        if let i = subs.firstIndex(where: { $0.id == key }) { subs[i] = s } else { subs.append(s) }
        var seen = Set<String>(); subs = subs.filter { seen.insert($0.id).inserted }
        save()
    }

    /// iTunes store searched (and recorded with a subscription): the device region, US when unknown.
    static var country: String { (Locale.current.region?.identifier ?? "US").lowercased() }

    @discardableResult func subscribe(_ feedUrl: String, _ feed: Feed, title: String = "", artist: String = "") -> Subscription {
        if let s = byId(SubscriptionLogic.idOf(feedUrl)) { return s }
        var f = feed; if f.title.isEmpty { f.title = title }; if f.artist.isEmpty { f.artist = artist }
        let s = SubscriptionLogic.baseline(feedUrl, f, country: Self.country, now: Int64(Date().timeIntervalSince1970))
        put(s); if !s.artworkUrl.isEmpty { ArtworkCache.shared.prefetch(seriesId: s.id, url: s.artworkUrl) }; return s
    }

    /// Subscribes from a search hit or a pasted link (fetches the feed first).
    func subscribe(feedUrl: String, title: String = "", artist: String = "") async -> Result<Subscription, CheckFailed> {
        switch await FeedFetcher.fetch(feedUrl) {
        case let .ok(feed, finalUrl, moved, _, _): return .success(subscribe(moved ? finalUrl : feedUrl, feed, title: title, artist: artist))
        case .failed(let e): return .failure(CheckFailed(error: e))
        case .notModified: return .failure(CheckFailed(error: .RETRY_LATER))
        }
    }
    struct CheckFailed: Error { let error: CheckError }

    /// Checks one subscription; returns its id afterwards (new when the feed moved for good).
    @discardableResult func check(_ id: String) async -> String {
        guard let sub = byId(id), !checking.contains(id) else { return id }
        checking.insert(id); defer { checking.remove(id) }
        let r = await FeedFetcher.fetch(sub.feedUrl, etag: sub.etag, lastModified: sub.lastModified)
        guard var cur = byId(id) else { return id }
        let now = Int64(Date().timeIntervalSince1970)
        switch r {
        case let .ok(feed, finalUrl, moved, etag, lm):
            cur = SubscriptionLogic.merge(cur, feed, now: now)
            if moved { cur = SubscriptionLogic.rekey(cur, to: finalUrl) }
            cur.etag = etag; cur.lastModified = lm
        case let .notModified(finalUrl, moved):
            cur.lastChecked = now; cur.checkError = nil
            if moved { cur = SubscriptionLogic.rekey(cur, to: finalUrl) }
        case .failed(let e): cur.checkError = e.rawValue
        }
        put(cur, replacing: id)
        if cur.id != id { moved = cur.id }
        return cur.id
    }
    func checkAll() async { for s in subs where !s.paused { await check(s.id) } }
    func checkIfStale(_ id: String) async {
        guard let s = byId(id), !s.paused, Int64(Date().timeIntervalSince1970) - s.lastChecked > 24 * 3600 else { return }
        await check(id)
    }
    func setPaused(_ id: String, _ p: Bool) { guard var s = byId(id) else { return }; s.paused = p; put(s) }
    func remove(_ id: String) { subs.removeAll { $0.id == id }; save() }

    /// Library items by episode (guid, else audio URL) → job/session id, only those still in the library or queue.
    func libraryIndex(_ m: Model) async -> [String: UUID] {
        let live = Set(m.library.sessions.map(\.id)).union((await m.runner.queue.all).map(\.id)).union(justQueued)
        var ix: [String: UUID] = [:]
        for s in EpisodeSources.all() {
            guard let id = UUID(uuidString: s.jobId), live.contains(id) else { continue }
            ix["url:" + s.sourceUrl] = id; if let g = s.guid { ix["guid:" + g] = id }
        }
        return ix
    }
    static func entry(_ ix: [String: UUID], _ ep: FeedEpisode) -> UUID? { ix["guid:" + ep.guid] ?? ix["url:" + ep.audioUrl] }

    enum Queued: Equatable { case started(UUID), already(UUID), noSpace }
    /// "Download and transcribe": dedupes against the library, checks the space it needs, then imports it like a
    /// single episode and marks it seen.
    func queue(_ subId: String, _ ep: FeedEpisode, model m: Model, forceLowSpace: Bool = false) async -> Queued {
        if let id = Self.entry(await libraryIndex(m), ep) { return .already(id) }
        let need = SubscriptionLogic.spaceNeeded(ep.lengthBytes)
        let free = (try? JobQueue.support.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? Int64.max
        if forceLowSpace || (need > 0 && free < need) { return .noSpace }
        let sub = byId(subId)
        let id = m.imports.addEpisode(Episode(title: ep.title, audioUrl: ep.audioUrl, published: "", duration: ""), seriesId: subId, guid: ep.guid,
                                      channel: sub.map { .init(title: $0.title, artist: $0.artist, artworkUrl: $0.artworkUrl, feedUrl: $0.feedUrl) },
                                      published: ep.published > 0 ? ep.published : nil, durationSec: ep.durationSec > 0 ? ep.durationSec : nil)
        justQueued.insert(id)   // its job is added asynchronously: a second tap must still see it
        if let s = byId(subId) { put(SubscriptionLogic.markSeen(s, guid: ep.guid, entryId: id.uuidString)) }
        return .started(id)
    }
}

func lastCheckedText(_ s: Subscription, time: Bool = false) -> String {
    if s.lastChecked == 0 { return L("sub_never_checked") }
    let d = Date(timeIntervalSince1970: TimeInterval(s.lastChecked))
    return L("sub_last_checked", time ? d.formatted(date: .abbreviated, time: .shortened) : d.formatted(.dateTime.month().day()))
}

private func shareText(_ s: Subscription) -> String { "\(s.title) — \(s.feedUrl)" }

/// Subscribed shows: NavigationStack + List (swipe to delete with an alert, swipe to pause), a show page per row.
struct SubscriptionsView: View {
    var initialId: String? = nil
    var onOpen: (UUID) -> Void = { _ in }
    init(initialId: String? = nil, onOpen: @escaping (UUID) -> Void = { _ in }) {
        self.initialId = initialId; self.onOpen = onOpen
        _path = State(initialValue: initialId.map { [$0] } ?? [])   // a show opened from elsewhere starts on its page
    }
    @ObservedObject private var store = SubscriptionStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var path: [String] = []
    @State private var pasting = false
    @State private var deleting: Subscription?
    @State private var query = ""
    var body: some View {
        NavigationStack(path: $path) {
            List {
                ForEach(store.subs.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) }) { s in
                    NavigationLink(value: s.id) { ShowRow(s: s, checking: store.checking.contains(s.id)) }
                        .swipeActions(edge: .trailing) {
                            Button(L("sub_delete"), systemImage: "trash", role: .destructive) { deleting = s }
                        }
                        .swipeActions(edge: .leading) {
                            Button(L(s.paused ? "sub_resume" : "sub_pause"), systemImage: s.paused ? "play" : "pause") { store.setPaused(s.id, !s.paused) }.tint(.orange)
                        }
                        .contextMenu {
                            ShareLink(item: shareText(s)) { Label(L("sub_share"), systemImage: "square.and.arrow.up") }
                            Button(L(s.paused ? "sub_resume" : "sub_pause"), systemImage: s.paused ? "play" : "pause") { store.setPaused(s.id, !s.paused) }
                            Button(L("sub_delete"), systemImage: "trash", role: .destructive) { deleting = s }
                        }
                        .accessibilityIdentifier("sub-row")
                }
            }
            .overlay { if store.subs.isEmpty { ContentUnavailableView(L("sub_source"), systemImage: "square.stack", description: Text(L("sub_empty"))) } }
            .modifier(SearchIfLong(on: store.subs.count > 8, text: $query))
            .navigationTitle(L("sub_source"))
            .navigationDestination(for: String.self) { id in ChannelPage(key: id, onOpen: onOpen) }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("done")) { dismiss() } }
                ToolbarItem { Button(L("sub_paste"), systemImage: "link") { pasting = true } }
                ToolbarItem { Button(L("sub_check_all"), systemImage: "arrow.clockwise") { Task { await store.checkAll() } }.disabled(!store.checking.isEmpty || !store.subs.contains { !$0.paused }) }
            }
            .alert(L("sub_delete"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { s in
                Button(L("sub_delete"), role: .destructive) { store.remove(s.id) }
                Button(L("cancel"), role: .cancel) {}
            } message: { _ in Text(L("sub_delete_note")) }
            .sheet(isPresented: $pasting) { PasteFeedView { id in pasting = false; path = [id] } }
            .onChange(of: store.moved) { if let id = store.moved { if !path.isEmpty { path = [id] }; store.moved = nil } }
        }
    }
}

private struct SearchIfLong: ViewModifier {
    let on: Bool; @Binding var text: String
    func body(content: Content) -> some View { if on { content.searchable(text: $text) } else { content } }
}

private struct ShowRow: View {
    let s: Subscription; let checking: Bool
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(s.title).font(.headline)
                if !s.artist.isEmpty { Text(s.artist).font(.subheadline).foregroundStyle(.secondary) }
                Group {
                    if checking { Text(L("sub_checking")) }
                    else if s.paused { Text(L("sub_paused")) }
                    else if let e = s.error { Text(L(e.key)).foregroundStyle(.red) }
                    else { Text(L("sub_episodes_n", s.episodes.count) + " · " + lastCheckedText(s)) }
                }.font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            let n = s.newEpisodes.count
            if n > 0 { Text(L("sub_new_count", n)).font(.caption.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 2).background(Color.accentColor, in: Capsule()).foregroundStyle(.white) }
        }
    }
}

/// "Paste RSS link": previews the feed (clipboard prefilled when it holds a link), then subscribes.
struct PasteFeedView: View {
    let done: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = SubscriptionStore.shared
    @State private var link = ""
    @State private var busy = false
    @State private var error: String?
    @State private var preview: (url: String, feed: Feed)?
    var body: some View {
        NavigationStack {
            Form {
                TextField(L("sub_paste_hint"), text: $link).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .onChange(of: link) { preview = nil; error = nil }.accessibilityIdentifier("sub-paste-field")
                if busy { HStack(spacing: 8) { ProgressView(); Text(L("sub_checking")).font(.footnote).foregroundStyle(.secondary) } }
                if let error { Text(error).foregroundStyle(.red) }
                if let p = preview {
                    VStack(alignment: .leading) {
                        Text(p.feed.title).font(.headline)
                        Text([p.feed.artist, L("sub_episodes_n", p.feed.episodes.count)].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(L("sub_paste"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(preview == nil ? L("sub_preview") : L("sub_subscribe")) { go() }.disabled(busy || link.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear { if link.isEmpty, UIPasteboard.general.hasURLs, let u = UIPasteboard.general.url, ["http", "https"].contains(u.scheme) { link = u.absoluteString } }
        }
    }
    private func go() {
        if let p = preview { done(store.subscribe(p.url, p.feed).id); return }
        busy = true; error = nil
        let u = link.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            defer { busy = false }
            switch await FeedFetcher.fetch(u) {
            case let .ok(feed, finalUrl, moved, _, _): preview = (moved ? finalUrl : u, feed)
            case .failed(let e): error = L(e.key)
            case .notModified: error = L("sub_err_retry")
            }
        }
    }
}

/// DEV hooks (device / simulator checks without typing), logged to status.log and Documents/subdev.txt:
/// VOX_SUB=<query> (subscribe to the first hit) · VOX_SUBFEED=<url> (paste path) · VOX_SUBEP=<n> (queue episode n, then
/// again: must be deduped) · VOX_SUBLOWSPACE (with VOX_SUBEP: the not-enough-space path) · VOX_SUBPAUSE (pause the first,
/// check all: it must be skipped) · VOX_SUBCHECK (check all, log) · VOX_SUBOPEN (open the list, or the subscribed show).
enum SubDev {
    @MainActor static var ran = false
    @MainActor static func run(_ m: Model, open: @escaping (String?) -> Void) {
        let e = Dev.env
        guard !ran, ["VOX_SUB", "VOX_SUBFEED", "VOX_SUBCHECK", "VOX_SUBPAUSE", "VOX_SUBOPEN"].contains(where: { e[$0] != nil }) else { return }
        ran = true
        Task { @MainActor in
            let st = SubscriptionStore.shared; var log = ""
            func say(_ s: String) { log += s + "\n"; StatusLog.add("subdev " + s) }
            var sub: Subscription?
            if let q = e["VOX_SUB"] {
                if let hit = try? await Podcast.search(q).first, case .success(let s) = await st.subscribe(feedUrl: hit.feedUrl, title: hit.title, artist: hit.artist) { sub = s } else { say("subscribe failed for \(q)") }
            }
            if let u = e["VOX_SUBFEED"] {
                switch await st.subscribe(feedUrl: u) { case .success(let s): sub = s; case .failure(let f): say("feed failed: \(f.error)") }
            }
            if let s = sub { say("subscribed \(s.id) '\(s.title)' episodes=\(s.episodes.count) seen=\(s.seenGuids.count)") }
            if e["VOX_SUBPAUSE"] != nil, let first = st.subs.first {
                st.setPaused(first.id, true); let before = st.byId(first.id)!.lastChecked
                try? await Task.sleep(nanoseconds: 1_100_000_000); await st.checkAll()
                say("pause \(first.id) skipped=\(st.byId(first.id)!.lastChecked == before)")
            }
            if e["VOX_SUBCHECK"] != nil { await st.checkAll(); for s in st.subs { say("check \(s.id) new=\(s.newEpisodes.count) error=\(s.checkError ?? "nil") paused=\(s.paused)") } }
            if let s = sub, let n = Int(e["VOX_SUBEP"] ?? ""), n < s.episodes.count {
                let ep = s.episodes[n]
                say("ep \(ep.title) -> \(await st.queue(s.id, ep, model: m, forceLowSpace: e["VOX_SUBLOWSPACE"] != nil))")
                say("ep second submit -> \(await st.queue(s.id, ep, model: m))")
            }
            try? log.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/subdev.txt"), atomically: true, encoding: .utf8)
            if e["VOX_SUBOPEN"] != nil || sub != nil { open(sub?.id) }
        }
    }
}
