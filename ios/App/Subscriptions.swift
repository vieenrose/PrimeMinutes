import Foundation

// Podcast series subscriptions (docs/design/podcast-series.md, v1 "manual check"; port of Android online/Subscriptions.kt).
// A subscription is a saved feed link: checking it is one user-visible GET, and a new episode goes through the
// existing single-episode import (Imports.addEpisode); the pipeline is untouched. Same JSON field names as Android.

/// One episode of a subscribed feed. `guid` is the identity key (guid → link → enclosure URL → sha1(title+pubDate)).
struct FeedEpisode: Codable, Hashable, Identifiable {
    var id: String { guid }
    var guid: String
    var title: String
    var audioUrl: String
    var published: Int64 = 0        // epoch seconds, 0 = unknown
    var durationSec: Int = 0
    var lengthBytes: Int64 = 0
    var queuedEntryId: String? = nil
}

struct Feed: Equatable { var title: String; var artist: String; var artworkUrl: String; var episodes: [FeedEpisode] }

enum CheckError: String, Codable { case GONE, PAYWALLED, UNPARSABLE, NO_AUDIO, RETRY_LATER
    var key: String {
        switch self {
        case .GONE: return "sub_err_gone"
        case .PAYWALLED: return "sub_err_paywalled"
        case .UNPARSABLE: return "sub_err_unparsable"
        case .NO_AUDIO: return "sub_err_no_audio"
        case .RETRY_LATER: return "sub_err_retry"
        }
    }
}

struct Subscription: Codable, Hashable, Identifiable {
    var feedUrl: String
    var title: String
    var artist: String = ""
    var artworkUrl: String = ""
    var country: String = ""
    var addedAt: Int64 = 0
    var lastChecked: Int64 = 0
    var checkError: String? = nil
    var auto: Bool = false          // v2, never shown in v1
    var paused: Bool = false
    var seenGuids: [String] = []
    var episodes: [FeedEpisode] = []
    var etag: String? = nil
    var lastModified: String? = nil

    var id: String { SubscriptionLogic.idOf(feedUrl) }
    var newEpisodes: [FeedEpisode] { let s = Set(seenGuids); return episodes.filter { !s.contains($0.guid) } }
    var error: CheckError? { checkError.flatMap(CheckError.init(rawValue:)) }

    init(feedUrl: String, title: String) { self.feedUrl = feedUrl; self.title = title }
    enum CodingKeys: String, CodingKey { case id, feedUrl, title, artist, artworkUrl, country, addedAt, lastChecked, checkError, auto, paused, seenGuids, episodes, etag, lastModified }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        feedUrl = try c.decode(String.self, forKey: .feedUrl); title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        artist = try c.decodeIfPresent(String.self, forKey: .artist) ?? ""; artworkUrl = try c.decodeIfPresent(String.self, forKey: .artworkUrl) ?? ""
        country = try c.decodeIfPresent(String.self, forKey: .country) ?? ""; addedAt = try c.decodeIfPresent(Int64.self, forKey: .addedAt) ?? 0
        lastChecked = try c.decodeIfPresent(Int64.self, forKey: .lastChecked) ?? 0; checkError = try c.decodeIfPresent(String.self, forKey: .checkError)
        auto = try c.decodeIfPresent(Bool.self, forKey: .auto) ?? false; paused = try c.decodeIfPresent(Bool.self, forKey: .paused) ?? false
        seenGuids = try c.decodeIfPresent([String].self, forKey: .seenGuids) ?? []; episodes = try c.decodeIfPresent([FeedEpisode].self, forKey: .episodes) ?? []
        etag = try c.decodeIfPresent(String.self, forKey: .etag); lastModified = try c.decodeIfPresent(String.self, forKey: .lastModified)
    }
    func encode(to e: Encoder) throws {
        var c = e.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id); try c.encode(feedUrl, forKey: .feedUrl); try c.encode(title, forKey: .title); try c.encode(artist, forKey: .artist)
        try c.encode(artworkUrl, forKey: .artworkUrl); try c.encode(country, forKey: .country); try c.encode(addedAt, forKey: .addedAt)
        try c.encode(lastChecked, forKey: .lastChecked); try c.encodeIfPresent(checkError, forKey: .checkError); try c.encode(auto, forKey: .auto)
        try c.encode(paused, forKey: .paused); try c.encode(seenGuids, forKey: .seenGuids); try c.encode(episodes, forKey: .episodes)
        try c.encodeIfPresent(etag, forKey: .etag); try c.encodeIfPresent(lastModified, forKey: .lastModified)
    }
}

/// Pure rules (host-tested): identity, baseline, merge, eviction, rekey, HTTP classification.
enum SubscriptionLogic {
    static let maxSeen = 2000

    static func idOf(_ feedUrl: String) -> String { "podcast-" + fnv1a(feedUrl) }

    /// 64-bit FNV-1a in hex, no zero padding (== Podcast.stableHash, == Android).
    static func fnv1a(_ s: String) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return String(h, radix: 16)
    }

    static func identity(guid: String, link: String, audioUrl: String, title: String, pubDate: String) -> String {
        let t = { (s: String) in s.trimmingCharacters(in: .whitespacesAndNewlines) }
        if !t(guid).isEmpty { return "guid:" + t(guid) }
        if !t(link).isEmpty { return "link:" + t(link) }
        if !t(audioUrl).isEmpty { return "url:" + t(audioUrl) }
        return "sha1:" + sha1Hex(title + pubDate)
    }

    static func baseline(_ feedUrl: String, _ feed: Feed, country: String, now: Int64) -> Subscription {
        var s = Subscription(feedUrl: feedUrl, title: feed.title)
        s.artist = feed.artist; s.artworkUrl = feed.artworkUrl; s.country = country; s.addedAt = now; s.lastChecked = now
        var seen: [String] = []; var set = Set<String>()
        for e in feed.episodes where set.insert(e.guid).inserted { seen.append(e.guid) }
        s.seenGuids = Array(seen.prefix(maxSeen)); s.episodes = feed.episodes
        return s
    }

    static func merge(_ sub: Subscription, _ feed: Feed, now: Int64) -> Subscription {
        var queued: [String: String] = [:]
        for e in sub.episodes { if let q = e.queuedEntryId { queued[e.guid] = q } }
        let eps = feed.episodes.map { e -> FeedEpisode in var x = e; if let q = queued[e.guid] { x.queuedEntryId = q }; return x }
        let seenSet = Set(sub.seenGuids)
        let future = eps.filter { $0.published > now + 3600 && !seenSet.contains($0.guid) }.map(\.guid)
        var s = sub
        if !feed.title.isEmpty { s.title = feed.title }
        if !feed.artist.isEmpty { s.artist = feed.artist }
        if !feed.artworkUrl.isEmpty { s.artworkUrl = feed.artworkUrl }
        s.lastChecked = now; s.checkError = nil; s.episodes = eps
        s.seenGuids = evict(sub.seenGuids + future, eps)
        return s
    }

    static func markSeen(_ sub: Subscription, guid: String, entryId: String?) -> Subscription {
        var s = sub
        s.seenGuids = evict(sub.seenGuids.contains(guid) ? sub.seenGuids : sub.seenGuids + [guid], sub.episodes)
        if let entryId { s.episodes = sub.episodes.map { var e = $0; if e.guid == guid { e.queuedEntryId = entryId }; return e } }
        return s
    }

    /// The cap protects the file size, not a sliding window: only the oldest guids no longer listed are dropped.
    static func evict(_ seen: [String], _ episodes: [FeedEpisode], max: Int = maxSeen) -> [String] {
        guard seen.count > max else { return seen }
        let listed = Set(episodes.map(\.guid)); var drop = seen.count - max
        return seen.filter { g in if drop > 0 && !listed.contains(g) { drop -= 1; return false }; return true }
    }

    static func rekey(_ sub: Subscription, to url: String) -> Subscription { var s = sub; s.feedUrl = url; s.etag = nil; s.lastModified = nil; return s }

    static func classify(_ status: Int) -> CheckError? {
        switch status {
        case 200...299, 304: return nil
        case 404, 410: return .GONE
        case 401, 402, 403: return .PAYWALLED
        default: return .RETRY_LATER
        }
    }

    struct Hop: Equatable { var url: String; var status: Int; var movedPermanently: Bool }

    /// Manual redirects: at most `maxHops`; only an all-permanent (301/308) chain rekeys. Nil on a loop, too many
    /// hops or a non-http Location.
    static func followRedirects(_ start: String, maxHops: Int = 5, step: (String) async throws -> (Int, String?)) async rethrows -> Hop? {
        var url = start, permanent = true, visited: Set<String> = [start]
        for _ in 0...maxHops {
            let (status, loc) = try await step(url)
            if !(300...399).contains(status) || status == 304 { return Hop(url: url, status: status, movedPermanently: permanent && url != start) }
            guard let loc, let base = URL(string: url), let next = URL(string: loc, relativeTo: base)?.absoluteString,
                  next.hasPrefix("http://") || next.hasPrefix("https://"), visited.insert(next).inserted else { return nil }
            if status != 301 && status != 308 { permanent = false }
            url = next
        }
        return nil
    }

    /// Disk full (matched on the POSIX code / message, never on a network error).
    static func isNoSpace(_ e: Error) -> Bool {
        var n: NSError? = e as NSError
        while let x = n {
            if x.domain == NSPOSIXErrorDomain && x.code == Int(ENOSPC) { return true }
            if x.domain == NSCocoaErrorDomain && x.code == NSFileWriteOutOfSpaceError { return true }
            if x.localizedDescription.localizedCaseInsensitiveContains("No space left") { return true }
            n = x.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }

    static func spaceNeeded(_ lengthBytes: Int64) -> Int64 { lengthBytes <= 0 ? 0 : lengthBytes * 3 + 200 << 20 }

    // SHA-1 (identity fallback only; no CryptoKit dependency so the host tests build with swiftc alone).
    static func sha1Hex(_ s: String) -> String {
        var m = Array(s.utf8); let ml = UInt64(m.count) * 8
        var h: [UInt32] = [0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0]
        m.append(0x80); while m.count % 64 != 56 { m.append(0) }
        for i in (0..<8).reversed() { m.append(UInt8((ml >> (UInt64(i) * 8)) & 0xff)) }
        func rol(_ x: UInt32, _ n: UInt32) -> UInt32 { (x << n) | (x >> (32 - n)) }
        for c in stride(from: 0, to: m.count, by: 64) {
            var w = [UInt32](repeating: 0, count: 80)
            for i in 0..<16 { w[i] = (0..<4).reduce(UInt32(0)) { ($0 << 8) | UInt32(m[c + i * 4 + $1]) } }
            for i in 16..<80 { w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1) }
            var a = h[0], b = h[1], cc = h[2], d = h[3], e = h[4]
            for i in 0..<80 {
                let (f, k): (UInt32, UInt32) = i < 20 ? ((b & cc) | (~b & d), 0x5A827999) : i < 40 ? (b ^ cc ^ d, 0x6ED9EBA1)
                    : i < 60 ? ((b & cc) | (b & d) | (cc & d), 0x8F1BBCDC) : (b ^ cc ^ d, 0xCA62C1D6)
                let t = rol(a, 5) &+ f &+ e &+ k &+ w[i]; e = d; d = cc; cc = rol(b, 30); b = a; a = t
            }
            h[0] &+= a; h[1] &+= b; h[2] &+= cc; h[3] &+= d; h[4] &+= e
        }
        return h.map { String(format: "%08x", $0) }.joined()
    }
}

/// RSS parser for subscriptions (60 episodes, channel title/author/artwork, guid/link/enclosure length, atom enclosure).
final class FeedParser: NSObject, XMLParserDelegate {
    static let maxEpisodes = 60
    static let maxFeedBytes = 2 << 20
    struct NotAFeed: Error {}

    static func parse(_ data: Data, limit: Int = maxEpisodes) throws -> Feed {
        let p = FeedParser(limit: limit), x = XMLParser(data: data); x.delegate = p
        let ok = x.parse()
        if !p.sawItem && p.chTitle.isEmpty { throw NotAFeed() }
        _ = ok
        var seen = Set<String>()
        let eps = p.out.filter { seen.insert($0.guid).inserted }.enumerated().sorted { a, b in a.element.published != b.element.published ? a.element.published > b.element.published : a.offset < b.offset }.map(\.element)
        return Feed(title: p.chTitle, artist: p.chAuthor, artworkUrl: p.chArt, episodes: eps)
    }

    static func parseDate(_ s: String) -> Int64 {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines); if t.isEmpty { return 0 }
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX")
        for fmt in ["EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssZ"] {
            f.dateFormat = fmt; if let d = f.date(from: t) { return Int64(d.timeIntervalSince1970) }
        }
        return 0
    }

    static func parseDuration(_ s: String) -> Int {
        var acc = 0.0
        for p in s.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false) { guard let v = Double(p.trimmingCharacters(in: .whitespaces)) else { return 0 }; acc = acc * 60 + v }
        return Int(acc)
    }

    let limit: Int
    var out: [FeedEpisode] = []
    var chTitle = "", chAuthor = "", chArt = "", sawItem = false
    private var inItem = false, inImage = false, text = ""
    private var title = "", guid = "", link = "", audio = "", pub = "", dur = "", len: Int64 = 0
    init(limit: Int) { self.limit = limit }

    func parser(_ p: XMLParser, didStartElement n: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String] = [:]) {
        text = ""
        let l = n.lowercased()
        if l == "item" || l == "entry" { inItem = true; sawItem = true; title = ""; guid = ""; link = ""; audio = ""; pub = ""; dur = ""; len = 0; return }
        if inItem {
            if l == "enclosure" || l == "media:content", audio.isEmpty, (a["type"] ?? "").lowercased().hasPrefix("audio/"), let u = a["url"], !u.isEmpty {
                audio = u; len = Int64(a["length"] ?? a["fileSize"] ?? "") ?? 0
            }
            if l == "link" || l == "atom:link", let href = a["href"], !href.isEmpty {
                if (a["rel"] ?? "").lowercased() == "enclosure" {
                    if audio.isEmpty, (a["type"] ?? "").lowercased().hasPrefix("audio/") { audio = href; len = Int64(a["length"] ?? "") ?? 0 }
                } else if link.isEmpty { link = href }
            }
        } else {
            if l == "itunes:image", chArt.isEmpty { chArt = a["href"] ?? "" }
            if l == "image" { inImage = true }
        }
    }
    func parser(_ p: XMLParser, foundCharacters s: String) { text += s }
    func parser(_ p: XMLParser, foundCDATA d: Data) { text += String(decoding: d, as: UTF8.self) }
    func parser(_ p: XMLParser, didEndElement n: String, namespaceURI: String?, qualifiedName: String?) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines), l = n.lowercased()
        if inItem {
            switch l {
            case "title": title = t
            case "guid", "id": guid = t
            case "link": if link.isEmpty && !t.isEmpty { link = t }
            case "pubdate", "published": pub = t
            case "itunes:duration": dur = t
            case "item", "entry":
                inItem = false
                if !audio.isEmpty {
                    out.append(FeedEpisode(guid: SubscriptionLogic.identity(guid: guid, link: link, audioUrl: audio, title: title, pubDate: pub),
                                           title: title.isEmpty ? "Untitled Episode" : title, audioUrl: audio,
                                           published: Self.parseDate(pub), durationSec: Self.parseDuration(dur), lengthBytes: len))
                }
                if out.count >= limit { p.abortParsing() }
            default: break
            }
        } else {
            switch l {
            case "title" where !inImage && chTitle.isEmpty: chTitle = t
            case "itunes:author" where chAuthor.isEmpty: chAuthor = t
            case "url" where inImage && chArt.isEmpty: chArt = t
            case "image": inImage = false
            default: break
            }
        }
        text = ""
    }
}

/// Network side of a check: manual redirects, conditional GET, 2 MB cap.
enum FeedFetcher {
    enum Result { case ok(Feed, finalUrl: String, moved: Bool, etag: String?, lastModified: String?), notModified(finalUrl: String, moved: Bool), failed(CheckError) }

    private final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ s: URLSession, task: URLSessionTask, willPerformHTTPRedirection r: HTTPURLResponse, newRequest: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    }
    private static let session = URLSession(configuration: .ephemeral, delegate: NoRedirect(), delegateQueue: nil)

    static func fetch(_ feedUrl: String, etag: String? = nil, lastModified: String? = nil) async -> Result {
        guard feedUrl.hasPrefix("http://") || feedUrl.hasPrefix("https://") else { return .failed(.UNPARSABLE) }
        var body = Data(), resp: HTTPURLResponse?
        do {
            let hop = try await SubscriptionLogic.followRedirects(feedUrl) { u in
                guard let url = URL(string: u) else { throw URLError(.badURL) }
                var r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
                r.setValue("PrimeMinutes/1.0", forHTTPHeaderField: "User-Agent")
                if u == feedUrl { if let etag { r.setValue(etag, forHTTPHeaderField: "If-None-Match") }; if let lastModified { r.setValue(lastModified, forHTTPHeaderField: "If-Modified-Since") } }
                let (d, rr) = try await session.data(for: r)
                let h = rr as? HTTPURLResponse; resp = h; body = d
                return (h?.statusCode ?? 0, h?.value(forHTTPHeaderField: "Location"))
            }
            guard let hop else { return .failed(.RETRY_LATER) }
            if hop.status == 304 { return .notModified(finalUrl: hop.url, moved: hop.movedPermanently) }
            if let e = SubscriptionLogic.classify(hop.status) { return .failed(e) }
            guard let feed = try? FeedParser.parse(body.prefix(FeedParser.maxFeedBytes)) else { return .failed(.UNPARSABLE) }
            if feed.episodes.isEmpty { return .failed(.NO_AUDIO) }
            return .ok(feed, finalUrl: hop.url, moved: hop.movedPermanently, etag: resp?.value(forHTTPHeaderField: "ETag"), lastModified: resp?.value(forHTTPHeaderField: "Last-Modified"))
        } catch let e as URLError where [.cannotFindHost, .dnsLookupFailed, .secureConnectionFailed, .serverCertificateUntrusted].contains(e.code) {
            return .failed(.GONE)
        } catch { return .failed(.RETRY_LATER) }
    }
}
