import SwiftUI
import UIKit

// Library by source, the UI half (rules: LibrarySources.swift): the source chips, the episode tile with its
// channel artwork, the artwork cache, and the channel page (which replaces the subscription show page).

/// Channel artwork, downloaded once and kept in Application Support/artwork (≈10 KB each, 200 px): the library draws
/// it offline. Best effort: a failure leaves the generic podcast tile, and nothing is retried before the next launch.
@MainActor final class ArtworkCache: ObservableObject {
    static let shared = ArtworkCache()
    nonisolated static var dir: URL { JobQueue.support.appendingPathComponent("artwork", isDirectory: true) }
    @Published private(set) var version = 0
    private var mem: [String: UIImage] = [:]
    private var inFlight: Set<String> = []
    private var failed: Set<String> = []

    private func file(_ name: String) -> URL { Self.dir.appendingPathComponent(name) }
    /// The cached image, or nil (then a fetch starts when a URL is known).
    func image(seriesId: String?, url: String?) -> UIImage? {
        guard let url, !url.isEmpty else { return nil }
        let name = LibrarySources.artworkFileName(seriesId: seriesId, url: url)
        if let i = mem[name] { return i }
        if let d = try? Data(contentsOf: file(name)), let i = UIImage(data: d) { mem[name] = i; return i }
        prefetch(seriesId: seriesId, url: url)
        return nil
    }
    func prefetch(seriesId: String?, url: String) {
        let name = LibrarySources.artworkFileName(seriesId: seriesId, url: url)
        guard !inFlight.contains(name), !failed.contains(name), !FileManager.default.fileExists(atPath: file(name).path),
              let u = URL(string: url), ["http", "https"].contains(u.scheme) else { return }
        inFlight.insert(name)
        Task {
            defer { inFlight.remove(name) }
            var req = URLRequest(url: u, timeoutInterval: 20); req.setValue("PrimeMinutes/1.0", forHTTPHeaderField: "User-Agent")
            guard let (d, r) = try? await URLSession.shared.data(for: req), (r as? HTTPURLResponse)?.statusCode == 200, d.count < 5 << 20,
                  let small = await Task.detached(operation: { Self.downsample(d) }).value else { failed.insert(name); return }
            try? FileManager.default.createDirectory(at: Self.dir, withIntermediateDirectories: true)
            try? small.write(to: file(name), options: .atomic)
            if let i = UIImage(data: small) { mem[name] = i }
            version += 1
        }
    }
    /// Square 200 px JPEG (center crop), whatever the feed served.
    nonisolated static func downsample(_ d: Data) -> Data? {
        guard let src = UIImage(data: d), src.size.width > 0, src.size.height > 0 else { return nil }
        let side: CGFloat = 200, s = max(side / src.size.width, side / src.size.height)
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        let img = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: f).image { _ in
            let w = src.size.width * s, h = src.size.height * s
            src.draw(in: CGRect(x: (side - w) / 2, y: (side - h) / 2, width: w, height: h))
        }
        return img.jpegData(compressionQuality: 0.8)
    }
    /// Drops cached artwork no episode or subscription refers to any more (run at launch).
    nonisolated static func prune(keep: Set<String>) {
        for f in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] where !keep.contains(f.lastPathComponent) {
            try? FileManager.default.removeItem(at: f)
        }
    }
}

/// Once per launch: the channel snapshot of older episodes from the subscriptions, artwork fetched for every channel
/// in use, and artwork no one uses removed.
@MainActor enum LibrarySourcesSetup {
    static var ran = false
    static func run() {
        guard !ran else { return }; ran = true
        let subs = SubscriptionStore.shared.subs
        EpisodeSources.backfill(subs)
        var keep = Set<String>()
        for s in EpisodeSources.all() where !(s.artworkUrl ?? "").isEmpty {
            keep.insert(LibrarySources.artworkFileName(seriesId: s.seriesId, url: s.artworkUrl!)); ArtworkCache.shared.prefetch(seriesId: s.seriesId, url: s.artworkUrl!)
        }
        for s in subs where !s.artworkUrl.isEmpty {
            keep.insert(LibrarySources.artworkFileName(seriesId: s.id, url: s.artworkUrl)); ArtworkCache.shared.prefetch(seriesId: s.id, url: s.artworkUrl)
        }
        ArtworkCache.prune(keep: keep)
    }
}

/// A channel's artwork at `size`, with a hairline border (a dark cover never bleeds into a dark surface); the generic
/// podcast tile while there is none. Decorative: the row's text names the channel.
struct ChannelArtwork: View {
    let seriesId: String?
    let url: String?
    var size: CGFloat = 40
    @ObservedObject private var cache = ArtworkCache.shared
    var body: some View {
        let r = size * 0.2
        Group {
            if let i = cache.image(seriesId: seriesId, url: url) { Image(uiImage: i).resizable().scaledToFill() }
            else { Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: size * 0.42, weight: .semibold)).voxFore(Vox.info).frame(maxWidth: .infinity, maxHeight: .infinity).voxTint(Vox.info, r) }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: r, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: r, style: .continuous).strokeBorder(Color.primary.opacity(0.12), lineWidth: 1))
        .accessibilityHidden(true)
    }
}

/// An episode's leading tile: the artwork, the status glyph as a corner badge (its shape still tells the state).
struct EpisodeTile: View {
    let source: EpisodeSource?
    let symbol: String
    let tint: Color
    let status: String
    var body: some View {
        ChannelArtwork(seriesId: source?.seriesId, url: source?.artworkUrl, size: 40)
            .overlay(alignment: .bottomTrailing) {
                Image(systemName: symbol).font(.system(size: 9, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 18, height: 18).background(tint, in: Circle())
                    .overlay(Circle().strokeBorder(Color(.systemBackground), lineWidth: 1.5))
                    .offset(x: 4, y: 4)
            }
            .padding(.trailing, 4).padding(.bottom, 4)
            .accessibilityElement().accessibilityLabel(status)
    }
}

/// The "Received" marker on a row's second line: a symbol and the word, never a colour alone.
func receivedText(_ meta: String) -> Text {
    Text("\(meta) · \(Image(systemName: "arrow.down.doc")) \(L("source_received_label"))")
}

/// The library's source chips (All / Meetings / Podcasts / Received with their counts), only the non-empty ones.
struct SourceChips: View {
    let options: [(SourceFilter, Int)]
    @Binding var selection: SourceFilter
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { o in
                    let on = selection == o.0
                    Button { withAnimation(.snappy) { selection = o.0 } } label: {
                        HStack(spacing: 4) { Text(L("source_" + o.0.rawValue)); Text("\(o.1)").foregroundStyle(on ? Color.white.opacity(0.85) : .secondary) }
                            .font(.subheadline.weight(on ? .semibold : .regular))
                            .padding(.horizontal, 12).padding(.vertical, 7)
                            .foregroundStyle(on ? Color.white : Color.primary)
                            .background(on ? Color.accentColor : Color(.secondarySystemGroupedBackground), in: Capsule())
                            .overlay(Capsule().strokeBorder(Color.primary.opacity(on ? 0 : 0.12), lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(L("source_" + o.0.rawValue)), \(o.1)")
                    .accessibilityAddTraits(on ? .isSelected : [])
                    .accessibilityIdentifier("source-" + o.0.rawValue)
                }
            }.padding(.horizontal, Vox.gutter)
        }
        .accessibilityElement(children: .contain).accessibilityLabel(L("cd_source_filter"))
    }
}

/// A channel's section header under the Podcasts filter: artwork, name, count; opens the channel page.
struct ChannelHeader: View {
    let group: ChannelGroup
    let open: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        Button(action: open) {
            HStack(spacing: 10) {
                if group.key != LibrarySources.otherKey { ChannelArtwork(seriesId: group.key, url: group.artworkUrl, size: typeSize.isAccessibilitySize ? 32 : 24) }
                Text(ScreenScript.gen(group.title)).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).multilineTextAlignment(.leading)
                Text("\(group.sessionIds.count)").font(.subheadline).foregroundStyle(.secondary)
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary).accessibilityHidden(true)
            }.contentShape(Rectangle())
        }
        .buttonStyle(.plain).textCase(nil)
        .accessibilityLabel(L("cd_channel_header", group.title, group.sessionIds.count))
        .accessibilityHint(L("cd_channel_open"))
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("channel-header")
    }
}

/// A channel: its artwork and subscription controls, the feed's episodes not in the library yet ("New in feed",
/// the subscription episode states), then the episodes in the library. Replaces the subscription show page.
struct ChannelPage: View {
    let key: String
    let onOpen: (UUID) -> Void
    private var m: Model { Model.shared }
    @ObservedObject private var store = SubscriptionStore.shared
    @ObservedObject private var library: SessionLibrary
    @ObservedObject private var runner: JobRunner
    @ObservedObject private var imports: Imports
    @State private var index: [String: UUID] = [:]
    @State private var sources: [EpisodeSource] = []
    @State private var notice: String?
    @State private var deleting = false
    @State private var busy = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    init(key: String, onOpen: @escaping (UUID) -> Void) {
        self.key = key; self.onOpen = onOpen
        _library = ObservedObject(wrappedValue: Model.shared.library); _runner = ObservedObject(wrappedValue: Model.shared.runner); _imports = ObservedObject(wrappedValue: Model.shared.imports)
    }
    private var sub: Subscription? { store.byId(key) }
    /// This channel's provenance records (latest per job), newest first.
    private var mine: [EpisodeSource] { sources.filter { LibrarySources.groupKey($0) == key } }
    private var snapshot: EpisodeSource? { mine.last { !($0.channelTitle ?? "").isEmpty } ?? mine.last }
    private var title: String { sub?.title ?? snapshot?.channelTitle ?? L("source_other_podcasts") }
    private var artist: String { sub?.artist ?? snapshot?.channelArtist ?? "" }
    private var artwork: String? { (sub?.artworkUrl).flatMap { $0.isEmpty ? nil : $0 } ?? snapshot?.artworkUrl }
    private var inLibrary: [(Session, EpisodeSource)] {
        let ix = LibrarySources.index(mine)
        return library.sessions.compactMap { s in ix[s.id.uuidString].map { (s, $0) } }
    }
    private var feedUrl: String? { sub?.feedUrl ?? mine.compactMap(\.feedUrl).last }

    private var statusLine: String {
        var parts = [L("channel_in_library", inLibrary.count)]
        if let s = sub { parts.append(s.paused ? L("sub_paused") : lastCheckedText(s, time: true)) }
        var line = parts.joined(separator: " · ")
        // M4: when the background check is on, its last run (the per-show time above covers manual checks too).
        if sub.map({ !$0.paused }) == true, BackgroundCheck.enabled {
            line += "\n" + (BackgroundCheck.lastRun.map { L("sub_bg_last_run", $0.formatted(date: .abbreviated, time: .shortened)) } ?? L("sub_bg_never_run"))
        }
        return line
    }
    private var header: some View {
        (typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10)) : AnyLayout(HStackLayout(alignment: .top, spacing: 14))) {
            if key != LibrarySources.otherKey { ChannelArtwork(seriesId: key, url: artwork, size: 72) }
            VStack(alignment: .leading, spacing: 4) {
                Text(ScreenScript.gen(title)).font(.title3.bold())
                if !artist.isEmpty { Text(ScreenScript.gen(artist)).font(.subheadline).foregroundStyle(.secondary) }
                Text(statusLine).font(.caption).foregroundStyle(.secondary)
                if let e = sub?.error { Text(L("sub_error", L(e.key))).font(.caption).foregroundStyle(Vox.danger.color) }
                if let notice { Text(notice).font(.footnote).foregroundStyle(.secondary) }
            }
        }
        .accessibilityElement(children: .combine)
    }
    var body: some View {
        List {
            Section {
                header
                if let s = sub {
                    Label(L("sub_subscribed"), systemImage: "checkmark.circle.fill").foregroundStyle(Color.accentColor).accessibilityIdentifier("channel-subscribed")
                    Button(store.checking.contains(s.id) ? L("sub_checking") : L("sub_check")) { Task { await store.check(s.id); await refresh() } }
                        .disabled(store.checking.contains(s.id)).accessibilityIdentifier("sub-check")
                } else if let f = feedUrl {
                    Button { subscribe(f) } label: { Label(L("sub_subscribe"), systemImage: "plus.circle") }.disabled(busy).accessibilityIdentifier("channel-subscribe")
                }
            }
            if let s = sub {
                let fresh = s.episodes.filter { ep in SubscriptionStore.entry(index, ep).map { id in !library.sessions.contains { $0.id == id } } ?? true }
                if !fresh.isEmpty { Section(L("channel_new_in_feed")) { ForEach(fresh) { ep in episodeRow(s, ep) } } }
            }
            if !inLibrary.isEmpty {
                Section(L("channel_in_library_header")) {
                    ForEach(inLibrary, id: \.0.id) { s, src in
                        Button { onOpen(s.id) } label: {
                            HStack(spacing: 12) {
                                EpisodeTile(source: src, symbol: s.noSpeech ? "mic.slash" : "checkmark", tint: s.noSpeech ? .gray : Vox.success.color, status: L(s.noSpeech ? "status_no_speech" : "cd_status_done"))
                                VStack(alignment: .leading, spacing: 2) {
                                    Vox.head(s.title.isEmpty ? L("recent_untitled") : ScreenScript.gen(s.title)).lineLimit(typeSize.isAccessibilitySize ? 4 : 2)
                                    Vox.meta(episodeMeta(src, s, channel: false))
                                }
                                Spacer(minLength: 0)
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityIdentifier("channel-episode")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(ScreenScript.gen(title)).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let s = sub {
                Menu {
                    Button(L(s.paused ? "sub_resume" : "sub_pause"), systemImage: s.paused ? "play" : "pause") { store.setPaused(s.id, !s.paused) }
                    ShareLink(item: "\(s.title) — \(s.feedUrl)") { Label(L("sub_share"), systemImage: "square.and.arrow.up") }
                    Button(L("sub_delete"), systemImage: "trash", role: .destructive) { deleting = true }
                } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(L("sub_more")) }
            }
        }
        .alert(L("sub_delete"), isPresented: $deleting) {
            Button(L("sub_delete"), role: .destructive) { store.remove(key) }
            Button(L("cancel"), role: .cancel) {}
        } message: { Text(L("sub_delete_note")) }
        .task { sources = EpisodeSources.all(); if sub != nil { await store.checkIfStale(key) }; await refresh() }
        .onReceive(runner.$waiting) { _ in Task { sources = EpisodeSources.all(); await refresh() } }
    }
    private func refresh() async { index = await store.libraryIndex(m) }
    private func subscribe(_ f: String) {
        busy = true; notice = nil
        Task { defer { busy = false }; if case .failure(let e) = await store.subscribe(feedUrl: f, title: title, artist: artist) { notice = L(e.error.key) } }
    }

    @ViewBuilder private func episodeRow(_ s: Subscription, _ ep: FeedEpisode) -> some View {
        let entry = SubscriptionStore.entry(index, ep)
        let isNew = !s.seenGuids.contains(ep.guid)
        VStack(alignment: .leading, spacing: 4) {
            Text(ScreenScript.gen(ep.title))
            let meta = [ep.published > 0 ? Date(timeIntervalSince1970: TimeInterval(ep.published)).formatted(.dateTime.month().day()) : "",
                        ep.durationSec > 0 ? Podcast.formatDuration(String(ep.durationSec)) : ""].filter { !$0.isEmpty }
            if !meta.isEmpty { Text(meta.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
            HStack {
                if let entry {
                    let job = runner.waiting.first { $0.id == entry }
                    Text(job.map { m.jobStatus($0) } ?? L("sub_imported")).font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    if job == nil { Button(L("sub_open")) { onOpen(entry) }.font(.subheadline.weight(.semibold)).buttonStyle(.borderless) }
                } else {
                    if isNew { HStack(spacing: 4) { Circle().fill(Vox.info.color).frame(width: 7, height: 7).accessibilityHidden(true); Text(L("sub_new")) }.font(.caption.weight(.semibold)).foregroundStyle(Vox.info.color) }
                    Spacer()
                    Button(L("sub_download")) { Task { await queue(s, ep) } }.font(.subheadline.weight(.semibold)).buttonStyle(.borderless)
                }
            }
        }.accessibilityIdentifier("sub-episode")
    }
    private func queue(_ s: Subscription, _ ep: FeedEpisode) async {
        switch await store.queue(s.id, ep, model: m) {
        case .started: notice = nil
        case .already(let e): notice = L("sub_already"); onOpen(e)
        case .noSpace: notice = L("sub_no_space")
        }
        sources = EpisodeSources.all(); await refresh()
    }
}

/// An episode row's second line, "Channel · Oct 27 · 58:12" (the channel left out on its own channel page).
func episodeMeta(_ src: EpisodeSource, _ s: Session, channel: Bool = true) -> String {
    let p = LibrarySources.episodeLine(channel: channel ? src.channelTitle : nil, published: src.published, fallbackDate: s.date, seconds: s.seconds)
    return ([p.channel.map(ScreenScript.gen), p.date.formatted(.dateTime.month().day()), p.seconds.map { Export.mmss($0) }] as [String?]).compactMap { $0 }.joined(separator: " · ")
}
