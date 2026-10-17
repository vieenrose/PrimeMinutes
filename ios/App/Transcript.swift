import Foundation

/// One transcript line (speaker, times, text). Codable into the library's session JSON.
struct Utterance: Identifiable, Hashable, Codable {
    var id = UUID()
    private enum CodingKeys: String, CodingKey { case speaker, start, end, text, tokenTimes }
    var speaker: Int, start: Double, end: Double, text: String
    /// Start of each token of `text` (TokenTimes.tokenize), s after `start`: the engine's own times, used by the
    /// speaker-boundary editor. Optional: older sessions and edited lines whose tokens changed have none.
    var tokenTimes: [Double]? = nil
}

/// The engine's segment encoding (nemo_c.h; same as Android's nemo_jni.cpp):
/// records separated by RS (0x1e), fields by US (0x1f): speaker, start, end, text [, ms,ms,... per codepoint].
enum SegmentCodec {
    static func decode(_ s: String) -> [Utterance] {
        s.split(separator: "\u{1e}").compactMap { rec in
            let f = rec.split(separator: "\u{1f}", maxSplits: 4, omittingEmptySubsequences: false)
            guard f.count >= 4, let sp = Int(f[0]), let a = Double(f[1]), let b = Double(f[2]) else { return nil }
            var u = Utterance(speaker: sp, start: a, end: b, text: String(f[3]))
            // Optional 5th field (final pass): each codepoint's start, ms after start -> per-token times.
            if f.count == 5, !f[4].isEmpty {
                let ms = f[4].split(separator: ",").compactMap { Double($0) }
                if let t = TokenTimes.fromCodepoints(raw: u.text, starts: ms.map { a + $0 / 1000 }, text: u.text) { u.tokenTimes = TokenTimes.relative(t, a) }
            }
            return u
        }
    }
}
