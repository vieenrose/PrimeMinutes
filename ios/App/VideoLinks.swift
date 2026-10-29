import Foundation

/// YouTube and PeerTube links (Android online/VideoLinks). Their import was removed (0.55): a link that reaches the
/// app is recognised only to explain that it is not imported, never fetched.
enum VideoLinks {
    /// True when `text` holds a YouTube link, or a PeerTube video page (`/w/<id>`, `/videos/watch/<id>`, `/videos/embed/<id>`).
    static func isVideoLink(_ text: String?) -> Bool {
        guard let text, !text.isEmpty else { return false }
        return text.split(whereSeparator: { $0.isWhitespace }).contains { classify(String($0)) }
    }

    private static func classify(_ raw: String) -> Bool {
        var t = raw.trimmingCharacters(in: CharacterSet(charactersIn: ".,)\"'"))
        if !t.contains("://") { t = "https://" + t }
        guard let u = URL(string: t), let scheme = u.scheme, ["http", "https"].contains(scheme), let host = u.host?.lowercased() else { return false }
        if host == "youtu.be" || host == "youtube.com" || host.hasSuffix(".youtube.com") || host == "youtube-nocookie.com" || host.hasSuffix(".youtube-nocookie.com") { return true }
        let parts = u.path.split(separator: "/").map(String.init)
        guard let i = parts.firstIndex(where: { $0 == "w" || $0 == "watch" || $0 == "embed" }), i + 1 < parts.count,
              i == 0 || parts[i - 1] == "videos" else { return false }
        return parts[i + 1].range(of: "^[A-Za-z0-9-]{8,40}$", options: .regularExpression) != nil
    }

    /// A queued download from an older version that came from PeerTube: its URL is a PeerTube instance's video file
    /// (`/static/web-videos/`, `/static/webseed/`, `/static/streaming-playlists/`, `/download/videos/`). Podcast
    /// enclosures do not use these paths. Such an item fails with an explanation instead of downloading.
    static func isPeerTubeFile(_ url: String) -> Bool {
        guard let p = URL(string: url)?.path else { return false }
        return ["/static/web-videos/", "/static/webseed/", "/static/streaming-playlists/", "/download/videos/", "/download/web-videos/"].contains { p.hasPrefix($0) }
    }
}
