import Foundation

// Library by source (study: library-redesign, option A + B's channel page). Pure rules, host-tested
// (ios/tests/LibrarySourcesTests.swift): the source kind of a meeting, the podcast provenance kept at import
// (episode_sources.json, extended additively), its backfill from subscriptions.json, the filters and the
// channel groups. The on-disk layout of sessions does not change: grouping is metadata only.

/// Where a library entry came from. Old sessions without a source are meetings (recording or file import).
enum SourceKind: String, Codable, CaseIterable { case meeting, podcast, received }

/// The library's source filter (chips / segmented control): All / Meetings / Podcasts / Received.
enum SourceFilter: String, CaseIterable, Identifiable {
    case all, meetings, podcasts, received
    var id: String { rawValue }
    func matches(_ k: SourceKind) -> Bool {
        switch self {
        case .all: return true
        case .meetings: return k == .meeting
        case .podcasts: return k == .podcast
        case .received: return k == .received
        }
    }
}

/// One episode's provenance (Android source.json): the job id (== the session's id), its audio URL and feed ids,
/// and, since the library redesign, a snapshot of the channel taken at import so unsubscribing never orphans it.
/// Every added field is optional: old files decode unchanged.
struct EpisodeSource: Codable, Equatable {
    var jobId: String
    var sourceUrl: String
    var guid: String? = nil
    var seriesId: String? = nil
    var kind: String? = nil            // "podcast"; nil in files written before the redesign (they are all podcasts)
    var channelTitle: String? = nil
    var channelArtist: String? = nil
    var artworkUrl: String? = nil
    var episodeTitle: String? = nil
    var published: Int64? = nil        // epoch seconds
    var durationSec: Int? = nil
    var feedUrl: String? = nil         // the channel's feed (the channel page can subscribe from it)
}

/// The channel facts a subscription knows (Subscription, reduced so this file stays free of the feed code).
struct ChannelInfo: Equatable {
    var seriesId: String
    var title: String
    var artist: String
    var artworkUrl: String
    /// Episodes the feed lists: guid → (published, durationSec), and the same by audio URL.
    var episodes: [(guid: String, audioUrl: String, published: Int64, durationSec: Int)] = []
    static func == (a: ChannelInfo, b: ChannelInfo) -> Bool { a.seriesId == b.seriesId && a.title == b.title && a.artist == b.artist && a.artworkUrl == b.artworkUrl }
}

/// A channel group under the Podcasts filter: its key (the series id, or "other" for episodes without one).
struct ChannelGroup: Equatable {
    var key: String
    var title: String
    var artworkUrl: String?
    var sessionIds: [UUID]
}

enum LibrarySources {
    static let otherKey = "other"

    /// The kind of one entry: a restored shared meeting is Received; an episode with provenance is a Podcast;
    /// anything else (recordings, file imports, sessions saved before the field) is a Meeting.
    static func kind(received: Bool, source: EpisodeSource?) -> SourceKind {
        if received { return .received }
        if let s = source, s.kind == nil || s.kind == SourceKind.podcast.rawValue { return .podcast }
        return .meeting
    }

    /// Fills the channel snapshot of episodes imported before the redesign, from the subscriptions still on the
    /// device (by seriesId; published/duration by guid, else audio URL). Never overwrites a value already there.
    /// Returns the sources and whether anything changed (the caller then rewrites the file once).
    static func backfill(_ sources: [EpisodeSource], channels: [ChannelInfo]) -> ([EpisodeSource], Bool) {
        let byId = Dictionary(channels.map { ($0.seriesId, $0) }, uniquingKeysWith: { a, _ in a })
        var changed = false
        let out = sources.map { s0 -> EpisodeSource in
            var s = s0
            guard let sid = s.seriesId, let c = byId[sid] else { return s }
            if (s.channelTitle ?? "").isEmpty, !c.title.isEmpty { s.channelTitle = c.title }
            if (s.channelArtist ?? "").isEmpty, !c.artist.isEmpty { s.channelArtist = c.artist }
            if (s.artworkUrl ?? "").isEmpty, !c.artworkUrl.isEmpty { s.artworkUrl = c.artworkUrl }
            if s.published == nil || s.durationSec == nil,
               let e = c.episodes.first(where: { (s.guid != nil && $0.guid == s.guid) || $0.audioUrl == s.sourceUrl }) {
                if s.published == nil, e.published > 0 { s.published = e.published }
                if s.durationSec == nil, e.durationSec > 0 { s.durationSec = e.durationSec }
            }
            if s != s0 { changed = true }
            return s
        }
        return (out, changed)
    }

    /// The latest source per job id (a re-queued episode appends a second record).
    static func index(_ sources: [EpisodeSource]) -> [String: EpisodeSource] {
        var ix: [String: EpisodeSource] = [:]
        for s in sources { ix[s.jobId] = s }
        return ix
    }

    /// The group key of an episode: its series id, else "other" (a single episode imported without a feed id).
    static func groupKey(_ s: EpisodeSource?) -> String { s?.seriesId ?? otherKey }

    /// Episodes grouped by channel, in the order given (newest first in, newest first per group); groups sorted by
    /// title (locale-aware), "Other podcasts" last. `otherTitle` names the group of episodes without a channel.
    static func groups(_ items: [(id: UUID, source: EpisodeSource?)], otherTitle: String) -> [ChannelGroup] {
        var order: [String] = []; var by: [String: ChannelGroup] = [:]
        for it in items {
            let k = groupKey(it.source)
            if by[k] == nil {
                order.append(k)
                let t = k == otherKey ? otherTitle : (it.source?.channelTitle).flatMap { $0.isEmpty ? nil : $0 } ?? otherTitle
                by[k] = ChannelGroup(key: k, title: t, artworkUrl: k == otherKey ? nil : it.source?.artworkUrl, sessionIds: [])
            }
            by[k]!.sessionIds.append(it.id)
            if by[k]!.artworkUrl == nil, k != otherKey, let a = it.source?.artworkUrl, !a.isEmpty { by[k]!.artworkUrl = a }
        }
        return order.compactMap { by[$0] }.sorted { a, b in
            if (a.key == otherKey) != (b.key == otherKey) { return b.key == otherKey }
            return a.title.localizedStandardCompare(b.title) == .orderedAscending
        }
    }

    /// The parts of an episode row's second line, "Channel · date · length" (empties dropped; the caller joins and
    /// formats the date): the channel title, the published date (the import date when the feed gave none), seconds.
    static func episodeLine(channel: String?, published: Int64?, fallbackDate: Date, seconds: Double) -> (channel: String?, date: Date, seconds: Double?) {
        let c = channel.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        let d = published.flatMap { $0 > 0 ? Date(timeIntervalSince1970: TimeInterval($0)) : nil } ?? fallbackDate
        return (c, d, seconds > 0 ? seconds : nil)
    }

    /// The artwork cache's file name for a channel: the series id when known, else a hash of the URL (stable, never
    /// a path piece from the network).
    static func artworkFileName(seriesId: String?, url: String) -> String {
        if let s = seriesId, !s.isEmpty, s.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) { return s + ".img" }
        var h: UInt64 = 0xcbf29ce484222325
        for b in url.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return "url-" + String(h, radix: 16) + ".img"
    }

    // MARK: file (Application Support/episode_sources.json; the path is given so the host test can use a temp dir)
    static func read(_ url: URL) -> [EpisodeSource] { (try? JSONDecoder().decode([EpisodeSource].self, from: Data(contentsOf: url))) ?? [] }
    static func write(_ a: [EpisodeSource], to url: URL) { try? JSONEncoder().encode(a).write(to: url, options: .atomic) }
}
