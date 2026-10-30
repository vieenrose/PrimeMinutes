import Foundation

// Library collections + scoped search/Ask (spec: scope-redesign, approved 2026-10-30). Pure rules, host-tested
// (ios/tests/LibraryCollectionsTests.swift): one model, a Collection, of two kinds — a channel (automatic, one per
// podcast series, from episode_sources.json) and a user collection (Application Support/collections.json). The
// grouping/sorting of the mixed library list, the scope filter, the title normalization of the suggestion card and
// the file. Sessions on disk do not change: membership is metadata only, and a library without the file loads as before.

/// A collection the user made: a name and its meetings (session ids, one collection per meeting).
struct UserCollection: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var created: Double
    var members: [String]
}

/// collections.json. Every field decodes with a default, so older or partial files load.
struct CollectionsDoc: Codable, Equatable {
    var version = 1
    var collections: [UserCollection] = []
    /// Suggestion groups the user skipped (normalized title keys): never offered again.
    var skipped: [String] = []
    /// Channel key → the episodes already seen (the "N new" badge counts the others).
    var seen: [String: [String]] = [:]
    /// False until the first launch with collections marked every existing episode seen (no badge flood on upgrade).
    var seeded = false
    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        version = (try? c.decodeIfPresent(Int.self, forKey: .version)) ?? 1
        collections = (try? c.decodeIfPresent([UserCollection].self, forKey: .collections)) ?? []
        skipped = (try? c.decodeIfPresent([String].self, forKey: .skipped)) ?? []
        seen = (try? c.decodeIfPresent([String: [String]].self, forKey: .seen)) ?? [:]
        seeded = (try? c.decodeIfPresent(Bool.self, forKey: .seeded)) ?? false
    }
}

enum CollectionKind: String, Codable { case channel, user }

/// A collection row of the library: its kind, key (series id / collection id), name, artwork and members newest first.
struct CollectionRow: Equatable, Identifiable {
    var kind: CollectionKind
    var key: String
    var name: String
    var artworkUrl: String?
    var sessionIds: [UUID]
    var latest: Date
    var latestTitle: String
    var newCount: Int = 0
    var id: String { kind.rawValue + ":" + key }
}

/// One library entry under All: a meeting outside any collection, or a collection.
enum LibraryEntry: Equatable {
    case session(UUID)
    case collection(CollectionRow)
}

/// The date part of a scope.
enum DateScope: String, CaseIterable, Identifiable {
    case any, week, month, year
    var id: String { rawValue }
    func contains(_ d: Date, now: Date = Date()) -> Bool {
        switch self {
        case .any: return true
        case .week: return d >= now.addingTimeInterval(-7 * 86_400)
        case .month: return d >= now.addingTimeInterval(-30 * 86_400)
        case .year: return d >= now.addingTimeInterval(-365 * 86_400)
        }
    }
}

/// The scope: every active filter of a screen. All of them off = the whole library.
struct LibraryScope: Equatable {
    var source: SourceFilter = .all
    /// "channel:<key>" / "user:<id>" (a collection page), nil elsewhere.
    var collection: String? = nil
    var person: String? = nil
    var date: DateScope = .any
    var isAll: Bool { source == .all && collection == nil && person == nil && date == .any }
}

/// What the scope filter needs of one library item.
struct ScopeItem {
    var id: UUID
    var date: Date
    var kind: SourceKind
    var collection: String?          // the CollectionRow id it belongs to, if any
    var people: Set<String>
}

/// A suggestion card: meetings whose titles match once years/dates/numbers are removed.
struct CollectionSuggestion: Equatable {
    var key: String
    var name: String
    var ids: [UUID]
}

enum Collections {
    static let minSuggestion = 3

    // MARK: title normalization (suggestions; no semantic matching)
    private static let patterns = [
        #"\d{2,4}\s*年(\s*\d{1,2}\s*月(\s*\d{1,2}\s*[日號号])?)?"#,
        #"\d{1,2}\s*月\s*\d{1,2}\s*[日號号]?"#,
        #"\d{1,4}([/.\-]\d{1,2}){1,2}"#,
        #"\d+"#,
    ]
    /// The title with its years, dates and numbers removed, separators trimmed (the name a suggestion proposes).
    static func baseTitle(_ t: String) -> String {
        var s = t
        for p in patterns { s = s.replacingOccurrences(of: p, with: " ", options: .regularExpression) }
        s = s.replacingOccurrences(of: #"[\s\-_/.:·,，、#()（）\[\]【】|]+"#, with: " ", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespaces)
    }
    /// The comparison key: the base title, case-folded.
    static func normalizeTitle(_ t: String) -> String { baseTitle(t).lowercased() }

    /// Groups of ≥ `min` meetings with the same normalized title, outside any collection and not skipped; biggest
    /// group first. `items` newest first: a group's name comes from its newest title.
    static func suggestions(_ items: [(id: UUID, title: String)], excluded: Set<UUID>, skipped: Set<String>, min: Int = minSuggestion) -> [CollectionSuggestion] {
        var order: [String] = []; var by: [String: CollectionSuggestion] = [:]
        for it in items where !excluded.contains(it.id) {
            let k = normalizeTitle(it.title)
            guard !k.isEmpty, !skipped.contains(k) else { continue }
            if by[k] == nil { order.append(k); by[k] = CollectionSuggestion(key: k, name: baseTitle(it.title), ids: []) }
            by[k]!.ids.append(it.id)
        }
        return order.compactMap { by[$0] }.filter { $0.ids.count >= min }.enumerated()
            .sorted { $0.element.ids.count != $1.element.ids.count ? $0.element.ids.count > $1.element.ids.count : $0.offset < $1.offset }.map(\.element)
    }

    // MARK: membership (one collection per meeting; no nesting)
    static func collection(of id: String, in doc: CollectionsDoc) -> UserCollection? { doc.collections.first { $0.members.contains(id) } }

    /// Moves `ids` into collection `cid` (out of any other one).
    static func assign(_ doc: CollectionsDoc, ids: [String], to cid: String) -> CollectionsDoc {
        var d = doc
        let set = Set(ids)
        for i in d.collections.indices { d.collections[i].members.removeAll { set.contains($0) } }
        if let i = d.collections.firstIndex(where: { $0.id == cid }) {
            for id in ids where !d.collections[i].members.contains(id) { d.collections[i].members.append(id) }
        }
        return d
    }
    static func create(_ doc: CollectionsDoc, id: String, name: String, ids: [String], now: Date = Date()) -> CollectionsDoc {
        var d = doc
        d.collections.append(UserCollection(id: id, name: name.trimmingCharacters(in: .whitespacesAndNewlines), created: now.timeIntervalSince1970, members: []))
        return assign(d, ids: ids, to: id)
    }
    static func rename(_ doc: CollectionsDoc, id: String, name: String) -> CollectionsDoc {
        var d = doc
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !n.isEmpty, let i = d.collections.firstIndex(where: { $0.id == id }) { d.collections[i].name = n }
        return d
    }
    /// Deletes the collection; its meetings stay in the library.
    static func delete(_ doc: CollectionsDoc, id: String) -> CollectionsDoc { var d = doc; d.collections.removeAll { $0.id == id }; return d }
    static func remove(_ doc: CollectionsDoc, ids: [String]) -> CollectionsDoc {
        var d = doc; let set = Set(ids)
        for i in d.collections.indices { d.collections[i].members.removeAll { set.contains($0) } }
        return d
    }
    /// Drops members that are neither in the library nor queued (deleted meetings).
    static func prune(_ doc: CollectionsDoc, alive: Set<String>) -> (CollectionsDoc, Bool) {
        var d = doc
        for i in d.collections.indices { d.collections[i].members.removeAll { !alive.contains($0) } }
        return (d, d != doc)
    }

    // MARK: "N new" (channels)
    /// The first launch: every channel's current episodes count as seen.
    static func seed(_ doc: CollectionsDoc, channels: [String: [String]]) -> CollectionsDoc {
        guard !doc.seeded else { return doc }
        var d = doc
        for (k, ids) in channels { d.seen[k] = Array(Set((d.seen[k] ?? []) + ids)).sorted() }
        d.seeded = true
        return d
    }
    /// Library episodes (processed: unfinished ones are not in the library) not seen yet. A channel never opened since
    /// seeding counts all its episodes.
    static func newCount(_ ids: [String], seen: [String]?) -> Int { let s = Set(seen ?? []); return ids.filter { !s.contains($0) }.count }
    static func markSeen(_ doc: CollectionsDoc, channel: String, ids: [String]) -> CollectionsDoc {
        var d = doc; d.seen[channel] = Array(Set((d.seen[channel] ?? []) + ids)).sorted(); return d
    }

    // MARK: grouping / sorting
    struct Item { var id: UUID; var date: Date; var title: String; var kind: SourceKind; var source: EpisodeSource? }

    /// The collection of each item: "channel:<series>" for podcast episodes (episodes stay in their channel),
    /// "user:<id>" for members of a user collection, nil otherwise.
    static func membership(_ items: [Item], doc: CollectionsDoc) -> [UUID: String] {
        var user: [String: String] = [:]
        for c in doc.collections { for m in c.members { user[m] = "user:" + c.id } }
        var out: [UUID: String] = [:]
        for it in items {
            if it.kind == .podcast { out[it.id] = "channel:" + LibrarySources.groupKey(it.source) }
            else if let u = user[it.id.uuidString] { out[it.id] = u }
        }
        return out
    }

    /// Every collection row (channels from the episodes, user collections from the doc, empty ones included),
    /// newest latest-item first. `items` in any order.
    static func rows(_ items: [Item], doc: CollectionsDoc, otherTitle: String) -> [CollectionRow] {
        let sorted = items.sorted { $0.date > $1.date }
        let mem = membership(sorted, doc: doc)
        var rows: [String: CollectionRow] = [:]
        for it in sorted {
            guard let rid = mem[it.id] else { continue }
            if rows[rid] == nil {
                if it.kind == .podcast {
                    let k = LibrarySources.groupKey(it.source)
                    let t = k == LibrarySources.otherKey ? otherTitle : (it.source?.channelTitle).flatMap { $0.isEmpty ? nil : $0 } ?? otherTitle
                    rows[rid] = CollectionRow(kind: .channel, key: k, name: t, artworkUrl: k == LibrarySources.otherKey ? nil : it.source?.artworkUrl, sessionIds: [], latest: it.date, latestTitle: it.title)
                } else if let c = doc.collections.first(where: { "user:" + $0.id == rid }) {
                    rows[rid] = CollectionRow(kind: .user, key: c.id, name: c.name, artworkUrl: nil, sessionIds: [], latest: it.date, latestTitle: it.title)
                }
            }
            rows[rid]?.sessionIds.append(it.id)
            if rows[rid]?.artworkUrl == nil, it.kind == .podcast, LibrarySources.groupKey(it.source) != LibrarySources.otherKey, let a = it.source?.artworkUrl, !a.isEmpty { rows[rid]?.artworkUrl = a }
        }
        for c in doc.collections where rows["user:" + c.id] == nil {
            rows["user:" + c.id] = CollectionRow(kind: .user, key: c.id, name: c.name, artworkUrl: nil, sessionIds: [], latest: Date(timeIntervalSince1970: c.created), latestTitle: "")
        }
        for (k, r) in rows where r.kind == .channel { rows[k]!.newCount = newCount(r.sessionIds.map(\.uuidString), seen: doc.seen[r.key]) }
        return rows.values.sorted { $0.latest != $1.latest ? $0.latest > $1.latest : $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// The library under a source filter, no query: meetings outside collections and collection rows, mixed and
    /// sorted by latest item date (All); channels only (Podcasts); meetings + user collections (Meetings);
    /// received meetings as rows (Received).
    static func entries(_ items: [Item], doc: CollectionsDoc, source: SourceFilter, otherTitle: String) -> [(date: Date, entry: LibraryEntry)] {
        let mem = membership(items, doc: doc)
        let all = rows(items, doc: doc, otherTitle: otherTitle)
        var out: [(date: Date, entry: LibraryEntry)] = []
        for r in all {
            switch source {
            case .all: out.append((r.latest, .collection(r)))
            case .podcasts: if r.kind == .channel { out.append((r.latest, .collection(r))) }
            case .meetings: if r.kind == .user { out.append((r.latest, .collection(r))) }
            case .received: break
            }
        }
        for it in items where mem[it.id] == nil && source.matches(it.kind) && (source == .received || it.kind != .podcast) {
            out.append((it.date, .session(it.id)))
        }
        if source == .received {   // received meetings filed in a collection still list here (the filter is the kind)
            for it in items where mem[it.id] != nil && it.kind == .received { out.append((it.date, .session(it.id))) }
        }
        return out.enumerated().sorted { $0.element.date != $1.element.date ? $0.element.date > $1.element.date : $0.offset < $1.offset }.map(\.element)
    }

    // MARK: scope
    static func inScope(_ it: ScopeItem, _ s: LibraryScope, now: Date = Date()) -> Bool {
        guard s.source.matches(it.kind) else { return false }
        if let c = s.collection, it.collection != c { return false }
        if let p = s.person, !it.people.contains(p) { return false }
        return s.date.contains(it.date, now: now)
    }

    // MARK: file (Application Support/collections.json; the path is given so the host test can use a temp dir)
    static func read(_ url: URL) -> CollectionsDoc { (try? JSONDecoder().decode(CollectionsDoc.self, from: Data(contentsOf: url))) ?? CollectionsDoc() }
    static func write(_ d: CollectionsDoc, to url: URL) {
        let e = JSONEncoder(); e.outputFormatting = [.sortedKeys]
        try? e.encode(d).write(to: url, options: .atomic)
    }
}
