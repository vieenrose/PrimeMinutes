import CryptoKit
import Foundation

/// The speaker-embedding model's card (`speaker_cards/<name>.json`, the same file on Android and iOS): what to
/// download, its input contract and the calibrated thresholds. Features read the thresholds from here, never from
/// constants, so another model is a new card plus a re-calibration (docs/design/speaker-tags.md §3.6). Android: SpeakerCard.kt.
struct SpeakerCard {
    var id, displayName, licence, source, dirName: String
    /// HF `resolve/<commit>` base URL.
    var revision: String
    /// relative path → (size, sha256).
    var files: [String: (size: Int64, sha256: String)]
    var graph: String
    var dim, mels: Int
    var meanNorm, int16Scale: Bool
    var minCleanSec, capSec: Double
    /// Under `tLow` nothing is suggested; `tLow`..`tHigh` "possible"; ≥ `tHigh` with `margin` over the next "likely".
    var tLow, tHigh, margin: Float
    /// A confirmed exemplar this far from the person's voice asks before it is learned.
    var outlier: Float
    var totalBytes: Int64 { files.values.reduce(0) { $0 + $1.size } }

    static let defaultName = "campplus-zh-en"
    static let shipped: SpeakerCard = load(defaultName)

    /// From the app bundle, else `$VOX_SPEAKER_CARDS/` (the host test runner).
    static func load(_ name: String) -> SpeakerCard {
        let url = Bundle.main.url(forResource: name, withExtension: "json", subdirectory: "speaker_cards")
            ?? ProcessInfo.processInfo.environment["VOX_SPEAKER_CARDS"].map { URL(fileURLWithPath: $0).appendingPathComponent("\(name).json") }
        guard let url, let data = try? Data(contentsOf: url), let c = parse(data) else { fatalError("no speaker card \(name)") }
        return c
    }

    static func parse(_ data: Data) -> SpeakerCard? {
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let f = o["files"] as? [String: [String: Any]], let fe = o["frontend"] as? [String: Any],
              let t = o["thresholds"] as? [String: Any] else { return nil }
        func s(_ k: String) -> String { o[k] as? String ?? "" }
        func d(_ x: Any?) -> Double { (x as? NSNumber)?.doubleValue ?? 0 }
        var files: [String: (size: Int64, sha256: String)] = [:]
        for (k, v) in f { files[k] = ((v["size"] as? NSNumber)?.int64Value ?? 0, v["sha256"] as? String ?? "") }
        return SpeakerCard(id: s("id"), displayName: s("displayName"), licence: s("licence"), source: s("source"), dirName: s("dirName"),
                           revision: s("revision"), files: files, graph: s("graph"), dim: Int(d(o["dim"])), mels: Int(d(fe["mels"])),
                           meanNorm: fe["meanNorm"] as? Bool ?? false, int16Scale: fe["int16Scale"] as? Bool ?? false,
                           minCleanSec: d(o["minCleanSec"]), capSec: d(o["capSec"]),
                           tLow: Float(d(t["low"])), tHigh: Float(d(t["high"])), margin: Float(d(t["margin"])), outlier: Float(d(t["outlier"])))
    }
}

/// Voice resemblance (docs/design/speaker-tags.md §4.4), pure. A person's voice is the seconds-weighted mean of their
/// CONFIRMED exemplars for the current model; a speaker is compared to it by cosine; the meeting's speakers are
/// assigned jointly so one person is not the first suggestion of two speakers. Android: VoiceMath.kt.
enum VoiceMath {
    static func normalize(_ v: [Float]) -> [Float] {
        var n = 0.0
        for x in v { n += Double(x) * Double(x) }
        if n <= 0 { return v }
        let inv = Float(1.0 / n.squareRoot())
        return v.map { $0 * inv }
    }

    static func cos(_ a: [Float], _ b: [Float]) -> Float {
        if a.count != b.count { return 0 }
        var d = 0.0, na = 0.0, nb = 0.0
        for i in a.indices { d += Double(a[i]) * Double(b[i]); na += Double(a[i]) * Double(a[i]); nb += Double(b[i]) * Double(b[i]) }
        return na <= 0 || nb <= 0 ? 0 : Float(d / (na * nb).squareRoot())
    }

    /// Seconds-weighted mean of unit vectors, re-normalized; nil when there is none.
    static func centroid(_ exemplars: [(vector: [Float], seconds: Float)]) -> [Float]? {
        guard let dim = exemplars.first?.vector.count else { return nil }
        var acc = [Double](repeating: 0, count: dim), any = false
        for (v, w) in exemplars where v.count == dim {
            let u = normalize(v)
            for i in 0..<dim { acc[i] += Double(u[i]) * Double(max(w, 1e-3)) }
            any = true
        }
        return any ? normalize(acc.map { Float($0) }) : nil
    }

    /// One suggestion level: 3 likely, 2 possible, 0 nothing (see `SpeakerCard`).
    static func level(_ score: Float, _ next: Float, _ card: SpeakerCard) -> Int {
        if score >= card.tHigh && score - next >= card.margin { return 3 }
        return score >= card.tLow ? 2 : 0
    }

    /// Suggestions for every speaker of a meeting. `speakers`: speaker → vector. `people`: person → voice.
    /// `excluded`: speaker → people never suggested for it (already linked in this meeting, or its own link).
    /// The best (speaker, person) pairs are taken greedily in score order; a person taken by one speaker is not
    /// offered to another; at most `max` per speaker, above `SpeakerCard.tLow` only.
    static func suggest(_ speakers: [Int: [Float]], _ people: [String: [Float]], _ card: SpeakerCard,
                        excluded: [Int: Set<String>] = [:], max: Int = 3) -> [Int: [VoiceSuggestion]] {
        var scores: [Int: [(p: String, s: Float)]] = [:]
        for (sid, v) in speakers {
            let ex = excluded[sid] ?? []
            // Stable order (person id) before the score sort, so equal scores rank the same on every run.
            scores[sid] = people.filter { !ex.contains($0.key) }.sorted { $0.key < $1.key }
                .map { (p: $0.key, s: cos(v, $0.value)) }.enumerated()
                .sorted { $0.element.s != $1.element.s ? $0.element.s > $1.element.s : $0.offset < $1.offset }.map(\.element)
        }
        var owner: [String: Int] = [:], taken = Set<Int>()
        let pairs = scores.sorted { $0.key < $1.key }.flatMap { sid, l in l.map { (sid: sid, p: $0.p, s: $0.s) } }.filter { $0.s >= card.tLow }
        for x in pairs.enumerated().sorted(by: { $0.element.s != $1.element.s ? $0.element.s > $1.element.s : $0.offset < $1.offset }).map(\.element)
        where !taken.contains(x.sid) && owner[x.p] == nil { owner[x.p] = x.sid; taken.insert(x.sid) }
        var out: [Int: [VoiceSuggestion]] = [:]
        for (sid, l) in scores {
            let mine = l.filter { $0.s >= card.tLow && (owner[$0.p] == nil || owner[$0.p] == sid) }
            out[sid] = mine.prefix(max).enumerated().map { i, x in
                // "Likely" needs the margin over the next person (any person, shown or not).
                let next = l.first { $0.p != x.p && $0.s <= x.s }?.s ?? -1
                let lv = level(x.s, next, card)
                return VoiceSuggestion(person: x.p, level: i == 0 ? lv : min(2, lv), score: x.s)
            }.filter { $0.level > 0 }
        }
        return out
    }
}

/// The two voice files (little-endian, a CRC32 at the end; the same layout as Android, not shared across platforms):
///  - a meeting's sidecar (`MeetingVoices`, Application Support/voices/<session uuid>.bin): every speaker's vector and
///    seconds, with the fingerprint of the speech spans it came from — derived data, recomputed when stale, deleted
///    with the meeting;
///  - `people/voices.bin` (`Exemplar` list): one exemplar per (person, meeting, model), the confirmed names only.
///    "Delete all voice data" deletes this file and every sidecar. Android: VoiceFiles.kt.
enum VoiceFiles {
    static let meetingMagic: UInt32 = 0x53564D50   // "PMVS"
    static let peopleMagic: UInt32 = 0x50564D50    // "PMVP"
    static let version: Int32 = 1

    struct SpeakerVoice: Equatable { var speaker: Int; var seconds: Float; var vector: [Float] }
    struct MeetingVoices: Equatable {
        var modelId: String; var fingerprint: String; var speakers: [SpeakerVoice]
        func of(_ speaker: Int) -> SpeakerVoice? { speakers.first { $0.speaker == speaker } }
    }
    struct Exemplar: Equatable {
        var person: String; var modelId: String; var meeting: String; var speaker: Int
        var seconds: Float; var confirmed: Bool; var vector: [Float]
    }

    private struct W {
        var b = Data()
        mutating func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { b.append(contentsOf: $0) } }
        mutating func i32(_ v: Int32) { u32(UInt32(bitPattern: v)) }
        mutating func u16(_ v: Int) { withUnsafeBytes(of: UInt16(truncatingIfNeeded: v).littleEndian) { b.append(contentsOf: $0) } }
        mutating func f32(_ v: Float) { u32(v.bitPattern) }
        mutating func str(_ s: String) { let x = Data(s.utf8); u16(x.count); b.append(x) }
        mutating func vec(_ v: [Float]) { u16(v.count); v.forEach { f32($0) } }
        func done() -> Data { var o = b; withUnsafeBytes(of: crc32(b).littleEndian) { o.append(contentsOf: $0) }; return o }
    }

    private struct R {
        let d: [UInt8]; var i = 0
        struct End: Error {}
        mutating func u32() throws -> UInt32 {
            guard i + 4 <= d.count else { throw End() }
            defer { i += 4 }
            return UInt32(d[i]) | UInt32(d[i + 1]) << 8 | UInt32(d[i + 2]) << 16 | UInt32(d[i + 3]) << 24
        }
        mutating func i32() throws -> Int32 { Int32(bitPattern: try u32()) }
        mutating func u16() throws -> Int {
            guard i + 2 <= d.count else { throw End() }
            defer { i += 2 }
            return Int(d[i]) | Int(d[i + 1]) << 8
        }
        mutating func f32() throws -> Float { Float(bitPattern: try u32()) }
        mutating func str() throws -> String {
            let n = try u16()
            guard i + n <= d.count else { throw End() }
            defer { i += n }
            return String(decoding: d[i..<i + n], as: UTF8.self)
        }
        mutating func vec() throws -> [Float] { let n = try u16(); return try (0..<n).map { _ in try f32() } }
    }

    private static let crcTable: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }
    static func crc32(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for x in d { c = crcTable[Int((c ^ UInt32(x)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFFFFFF
    }

    private static func body(_ bytes: Data, _ magic: UInt32) -> R? {
        if bytes.count < 12 { return nil }
        let a = [UInt8](bytes), body = Data(a[0..<a.count - 4])
        var t = R(d: Array(a[a.count - 4..<a.count]))
        guard let crc = try? t.u32(), crc32(body) == crc else { return nil }
        var r = R(d: Array(body))
        guard (try? r.u32()) == magic, (try? r.i32()) == version else { return nil }
        return r
    }

    static func encodeMeeting(_ m: MeetingVoices) -> Data {
        var w = W()
        w.u32(meetingMagic); w.i32(version); w.str(m.modelId); w.str(m.fingerprint); w.i32(Int32(m.speakers.count))
        for s in m.speakers { w.u16(s.speaker); w.f32(s.seconds); w.vec(s.vector) }
        return w.done()
    }

    static func decodeMeeting(_ bytes: Data) -> MeetingVoices? {
        guard var r = body(bytes, meetingMagic) else { return nil }
        return try? {
            let model = try r.str(), fp = try r.str(), n = try r.i32()
            guard n >= 0 else { throw R.End() }
            return MeetingVoices(modelId: model, fingerprint: fp, speakers: try (0..<Int(n)).map { _ in
                SpeakerVoice(speaker: try r.u16(), seconds: try r.f32(), vector: try r.vec()) })
        }()
    }

    static func encodePeople(_ list: [Exemplar]) -> Data {
        var w = W()
        w.u32(peopleMagic); w.i32(version); w.i32(Int32(list.count))
        for e in list {
            w.str(e.person); w.str(e.modelId); w.str(e.meeting); w.u16(e.speaker); w.f32(e.seconds); w.u16(e.confirmed ? 1 : 0); w.vec(e.vector)
        }
        return w.done()
    }

    static func decodePeople(_ bytes: Data) -> [Exemplar]? {
        guard var r = body(bytes, peopleMagic) else { return nil }
        return try? {
            let n = try r.i32()
            guard n >= 0 else { throw R.End() }
            return try (0..<Int(n)).map { _ in
                Exemplar(person: try r.str(), modelId: try r.str(), meeting: try r.str(), speaker: try r.u16(),
                         seconds: try r.f32(), confirmed: try r.u16() != 0, vector: try r.vec())
            }
        }()
    }

    /// The fingerprint of a speaker's clean speech spans (recompute the vector when it changes).
    static func fingerprint(_ spans: [Int: [CleanSpeech.Span]]) -> String {
        var sb = ""
        for (k, v) in spans.sorted(by: { $0.key < $1.key }) {
            sb += "\(k):"
            for s in v { sb += String(format: "%.2f-%.2f,", locale: Locale(identifier: "en_US_POSIX"), s.start, s.end) }
            sb += ";"
        }
        return SHA256.hash(data: Data(sb.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    /// Exemplar edits (pure). Newest `cap` per (person, model).
    enum Exemplars {
        static let cap = 30

        static func put(_ list: [Exemplar], _ e: Exemplar, order: (String) -> Int64 = { _ in 0 }) -> [Exemplar] {
            let all = list.filter { !($0.person == e.person && $0.meeting == e.meeting && $0.modelId == e.modelId) } + [e]
            let mine = all.filter { $0.person == e.person && $0.modelId == e.modelId }.enumerated()
                .sorted { order($0.element.meeting) != order($1.element.meeting) ? order($0.element.meeting) > order($1.element.meeting) : $0.offset < $1.offset }
            let drop = Set(mine.dropFirst(cap).map(\.element.meeting))
            return all.filter { !($0.person == e.person && $0.modelId == e.modelId && drop.contains($0.meeting)) }
        }
        static func removeMeeting(_ list: [Exemplar], _ meeting: String) -> [Exemplar] { list.filter { $0.meeting != meeting } }
        static func removePerson(_ list: [Exemplar], _ person: String) -> [Exemplar] { list.filter { $0.person != person } }
        static func remove(_ list: [Exemplar], _ person: String, _ meeting: String) -> [Exemplar] {
            list.filter { !($0.person == person && $0.meeting == meeting) }
        }
        static func move(_ list: [Exemplar], _ from: String, _ to: String) -> [Exemplar] {
            list.map { var e = $0; if e.person == from { e.person = to }; return e }
        }
        static func moveMeeting(_ list: [Exemplar], _ meeting: String, _ from: String, _ to: String) -> [Exemplar] {
            list.map { var e = $0; if e.person == from && e.meeting == meeting { e.person = to }; return e }
        }
        static func voices(_ list: [Exemplar], _ modelId: String) -> [String: [Float]] {
            var out: [String: [Float]] = [:]
            for (p, es) in Dictionary(grouping: list.filter { $0.confirmed && $0.modelId == modelId }, by: \.person) {
                if let c = VoiceMath.centroid(es.map { ($0.vector, $0.seconds) }) { out[p] = c }
            }
            return out
        }
    }
}

/// Energy gate inside a speaker's lines: a transcript line can span long pauses (a whiteboard, a slide change) whose
/// room noise would dominate the voice vector. Keeps 20 ms frames louder than both the line's loud frames minus `rel`
/// dB and its quiet frames plus `aboveFloor` dB (and an absolute floor), bridges gaps under `gap` s and drops runs
/// under `minRun` s. Android: VoiceGate in CleanSpeech.kt.
enum VoiceGate {
    static let frame = 0.02
    static let rel = 30.0
    static let aboveFloor = 6.0
    static let floorDb = -55.0
    static let gap = 0.2
    static let minRun = 0.3

    /// Voiced sample ranges [start, end) of `pcm` (`sr` Hz).
    static func runs(_ pcm: [Float], sr: Int = 16_000) -> [Range<Int>] {
        let n = Int(Double(sr) * frame), k = pcm.count / n
        if k == 0 { return [] }
        let e: [Double] = (0..<k).map { f in
            var s = 0.0
            for i in f * n..<(f + 1) * n { s += Double(pcm[i]) * Double(pcm[i]) }
            return 10 * log10(s / Double(n) + 1e-10)
        }
        let sorted = e.sorted()
        func pct(_ p: Double) -> Double { sorted[min(max(Int((p / 100) * Double(k - 1)), 0), k - 1)] }
        let thr = max(pct(95) - rel, floorDb, pct(10) + aboveFloor)
        var merged: [(Int, Int)] = []
        var i = 0
        while i < k {
            if e[i] > thr {
                var j = i
                while j < k && e[j] > thr { j += 1 }
                if let last = merged.last, Double(i - last.1) * frame < gap { merged[merged.count - 1].1 = j } else { merged.append((i, j)) }
                i = j
            } else { i += 1 }
        }
        return merged.filter { Double($0.1 - $0.0) * frame >= minRun }.map { $0.0 * n..<$0.1 * n }
    }
}
