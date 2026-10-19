import Foundation

/// PeerTube input: a video link from any instance → its smallest file (the audio-only one when the instance has it),
/// downloaded like a podcast episode. Only the instance's public REST API is used (`GET /api/v1/videos/{id}`).
enum PeerTube {
    struct Video { let title: String; let fileUrl: String; let seconds: Int }
    enum Failure: LocalizedError {
        case notALink, live, noFile
        var errorDescription: String? { L(self == .notALink ? "peertube_bad_link" : self == .live ? "peertube_live" : "peertube_no_file") }
    }

    /// (host, video id) of `https://host/w/<id>`, `/videos/watch/<id>` or `/videos/embed/<id>`; nil for anything else.
    static func parse(_ text: String) -> (host: URL, id: String)? {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.contains("://") { t = "https://" + t }
        guard let u = URL(string: t), let scheme = u.scheme, ["http", "https"].contains(scheme), let host = u.host else { return nil }
        let parts = u.path.split(separator: "/").map(String.init)
        guard let i = parts.firstIndex(where: { $0 == "w" || $0 == "watch" || $0 == "embed" }), i + 1 < parts.count,
              i == 0 || ["videos"].contains(parts[i - 1]) else { return nil }
        let id = parts[i + 1]
        guard id.range(of: "^[A-Za-z0-9-]{8,40}$", options: .regularExpression) != nil else { return nil }
        var base = URLComponents(); base.scheme = scheme; base.host = host; base.port = u.port
        return base.url.map { ($0, id) }
    }

    static func resolve(_ text: String) async throws -> Video {
        guard let (host, id) = parse(text) else { throw Failure.notALink }
        var r = URLRequest(url: host.appendingPathComponent("api/v1/videos/\(id)"), timeoutInterval: 20)
        r.setValue("PrimeMinutes/1.0", forHTTPHeaderField: "User-Agent")
        let (d, resp) = try await URLSession.shared.data(for: r)
        if let h = resp as? HTTPURLResponse, h.statusCode >= 400 { throw URLError(.fileDoesNotExist) }
        guard let o = try JSONSerialization.jsonObject(with: d) as? [String: Any] else { throw Failure.noFile }
        if o["isLive"] as? Bool == true { throw Failure.live }
        let files = (o["files"] as? [[String: Any]] ?? []) + ((o["streamingPlaylists"] as? [[String: Any]] ?? []).flatMap { $0["files"] as? [[String: Any]] ?? [] })
        // Resolution id 0 is an audio-only file; otherwise the lowest resolution (everything is resampled to 16 kHz mono anyway).
        let best = files.compactMap { f -> (Int, String)? in
            guard let url = (f["fileUrl"] as? String).flatMap({ $0.isEmpty ? nil : $0 }) ?? (f["fileDownloadUrl"] as? String).flatMap({ $0.isEmpty ? nil : $0 }),
                  url.hasPrefix("http"), !url.hasSuffix(".m3u8") else { return nil }
            return (((f["resolution"] as? [String: Any])?["id"] as? Int) ?? Int.max, url)
        }.min { $0.0 < $1.0 }
        guard let best else { throw Failure.noFile }
        return Video(title: o["name"] as? String ?? "", fileUrl: best.1, seconds: o["duration"] as? Int ?? 0)
    }
}
