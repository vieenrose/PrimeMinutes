import Foundation
import Combine
import CryptoKit
import UIKit

// Semantic search over the library (port of Android core/embed: Embedder, EmbedHost, SessionIndex,
// LibraryIndexer, LibrarySearch). Same model files, same index.bin format, same ranking.

extension ReaderModel {
    /// EmbeddingGemma-2 270M int4 repacked for LiteRT (Android EmbedRegistry.GEMMA2_270M).
    /// Pinned to the published HF commit (same as Android).
    static let embed: ReaderModel = {
        let base = "https://huggingface.co/Luigi/primeminutes-embed-litert/resolve/c961c0490871bd7dc4ac16c2e13fad1e79e963c8"
        return ReaderModel(id: "embed-gemma2-270m", repo: "", rev: "", files: [
            ModelFile(name: "embedder.tflite", remote: base + "/embedder.tflite", size: 70_257_800, sha256: "25596c901d4cc9bb1156bdbff71829506b9137c48292993036464dab889ac3a3"),
            ModelFile(name: "encoder.tflite", remote: base + "/encoder.tflite", size: 89_540_120, sha256: "912763557c5b892d6db76a95382ac9141fc55b1ee4e0ee786d454b70c12d8ccb"),
            ModelFile(name: "tokenizer.spiece", remote: base + "/tokenizer.spiece", size: 4_689_013, sha256: "e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03"),
        ])
    }()
    static let embedId = "embed-gemma2-270m"
    var megabytes: Int { Int(files.reduce(0) { $0 + $1.size } / 1_000_000) }
}

enum EmbedPrompts {
    static func query(_ q: String) -> String { "task: search result | query: " + q }
    static func document(_ t: String) -> String { "title: none | text: " + t }
}

/// The text embedder (embed_c.h): 256-d L2-normalized vectors, ≤ 512 tokens.
final class Embedder {
    static let dim = 256
    #if VOX_REAL_READER
    private let h: OpaquePointer
    init(dir: String, threads: Int) throws {
        var err = [CChar](repeating: 0, count: 512)
        guard let p = embed_load(dir, Int32(threads), &err, 512) else { throw ReaderError(description: String(cString: err)) }
        h = p
    }
    deinit { embed_free(h) }
    func embed(_ text: String) -> [Float]? {
        var out = [Float](repeating: 0, count: Self.dim)
        var err = [CChar](repeating: 0, count: 256)
        let n = embed_embed(h, text, &out, &err, 256)
        if n < 0 { StatusLog.add("embed failed: " + String(cString: err)); return nil }
        return out
    }
    func tokens(_ text: String) -> Int { max(0, Int(embed_count(h, text))) }
    #else
    init(dir: String, threads: Int) throws { throw ReaderError(description: "embedder not built") }
    func embed(_ text: String) -> [Float]? { nil }
    func tokens(_ text: String) -> Int { text.count / 3 + 1 }
    #endif
}

/// Loads the embedder lazily, unloads it after 60 s idle; refused while recording on a small phone (Android EmbedHost).
enum EmbedHost {
    private static let idle: TimeInterval = 60
    private static let lock = NSLock()
    // Guarded by `lock` (the embedder is used from the search, Ask and index workers).
    nonisolated(unsafe) private static var emb: Embedder?
    nonisolated(unsafe) private static var lastUse = Date()
    nonisolated(unsafe) private static var timer: DispatchWorkItem?
    static var dir: String { Storage.modelsRoot.appendingPathComponent(ReaderModel.embedId).path }
    /// The three files are there.
    static var ready: Bool { Storage.ready(.embed) }
    static var loaded: Bool { lock.lock(); defer { lock.unlock() }; return emb != nil }
    /// `recording` is the caller's (main-actor) knowledge: under 7 GB the speech engines own the memory while recording.
    static func mayLoad(recording: Bool) -> Bool { !(recording && ProcessInfo.processInfo.physicalMemory < 7 << 30) }

    /// Runs `block` with the loaded embedder (one user at a time); nil when it cannot be loaded.
    static func use<T>(threads: Int = 2, _ block: (Embedder) -> T) -> T? {
        lock.lock(); defer { lock.unlock(); touch() }
        if emb == nil {
            guard ready else { return nil }
            let t0 = Date()
            do { emb = try Embedder(dir: dir, threads: threads) } catch { StatusLog.add("embedder load failed: \(error)"); return nil }
            StatusLog.add(String(format: "embedder loaded in %.0f ms", Date().timeIntervalSince(t0) * 1000))
        }
        return block(emb!)
    }
    private static func touch() {
        lock.lock(); defer { lock.unlock() }
        lastUse = Date(); timer?.cancel()
        let w = DispatchWorkItem { lock.lock(); if Date().timeIntervalSince(lastUse) >= idle - 1 { emb = nil }; lock.unlock() }
        timer = w
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + idle, execute: w)
    }
    static func unload() { lock.lock(); emb = nil; timer?.cancel(); lock.unlock() }
}

/// Per-session vector index, `sessions/<id>.index.bin` (Android SessionIndex, same bytes).
enum SessionIndex {
    static let magic: UInt32 = 0x504D4958, version: UInt32 = 1, dim = 256
    static let chunkTokens = 200, chunkSeconds = 40.0, meetingTokens = 440
    enum Kind: UInt8 { case meeting = 0, note = 1, chunk = 2 }
    struct Unit { let kind: Kind, a: Int32, b: Int32, text: String
        var textHash: Int64 { fnv64(String(kind.rawValue) + "\0" + text) } }
    struct Vector { let kind: Kind, a: Int32, b: Int32, textHash: Int64, q: [Int8] }
    struct Index { let modelId: String, fingerprint: [UInt8], vectors: [Vector] }

    static func fnv64(_ s: String) -> Int64 {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
        return Int64(bitPattern: h)
    }
    static func url(_ id: UUID) -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("sessions").appendingPathComponent(id.uuidString + ".index.bin")
    }
    /// Android notes are raw "#n [m:ss] (TAG) text" lines; the iOS Note is structured — rendered the same way.
    static func noteLine(_ n: Note) -> String { "#\(n.id) [\(n.ts)] " + (n.tag.map { "(\($0)) " } ?? "") + n.text }
    static func noteTime(_ n: Note) -> Double {
        let p = n.ts.split(separator: ":").compactMap { Double($0) }
        return p.count == 3 ? p[0] * 3600 + p[1] * 60 + p[2] : p.count == 2 ? p[0] * 60 + p[1] : 0
    }

    static func fingerprint(_ s: Session) -> [UInt8] {
        var h = SHA256Hasher()
        func put(_ x: String?) { h.update(Array((x ?? "").utf8)); h.update([0]) }
        put(s.title); put(s.summary); put(s.notes.map(noteLine).joined(separator: "\n"))
        for u in s.lines { put(u.text); put(String(u.speaker)); put(String(u.start)) }
        for (k, v) in (s.speakerNames ?? [:]).sorted(by: { $0.key < $1.key }) { put(k); put(v) }
        return h.finalize()
    }

    static func chunkLine(_ s: Session, _ u: Utterance) -> String {
        if let n = s.speakerNames?[String(u.speaker)], !n.isEmpty { return n + ": " + u.text }
        return u.text
    }
    static func units(_ s: Session, tokens: (String) -> Int) -> [Unit] {
        var out: [Unit] = []
        // MEETING: title + summary, split into ≤ 440-token parts on line boundaries.
        let lines = (s.title + "\n" + s.summary).split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        var part: [String] = [], n = 0, idx: Int32 = 0
        for l in lines {
            let t = tokens(l)
            if n > 0 && n + t > meetingTokens { out.append(Unit(kind: .meeting, a: idx, b: 0, text: part.joined(separator: "\n"))); idx += 1; part = []; n = 0 }
            part.append(l); n += t
        }
        if !part.isEmpty { out.append(Unit(kind: .meeting, a: idx, b: 0, text: part.joined(separator: "\n"))) }
        for (i, note) in s.notes.enumerated() where note.text.count >= 6 { out.append(Unit(kind: .note, a: Int32(i), b: 0, text: note.text)) }
        // CHUNK: sliding window over utterances, ≤ 200 tokens and ≤ 40 s, one utterance of overlap.
        let us = s.lines
        var i = 0
        while i < us.count {
            var j = i, n = 0
            while j < us.count {
                let t = tokens(chunkLine(s, us[j]))
                if n > 0 && (n + t > chunkTokens || us[j].end - us[i].start > chunkSeconds) { break }
                n += t; j += 1
            }
            out.append(Unit(kind: .chunk, a: Int32(i), b: Int32(j - 1), text: (i..<j).map { chunkLine(s, us[$0]) }.joined(separator: "\n")))
            i = j - 1 > i ? j - 1 : j
        }
        return out
    }

    static func quantize(_ v: [Float]) -> [Int8] { v.map { Int8(max(-127, min(127, ($0 * 127).rounded()))) } }
    static func dot(_ q: [Int8], _ f: [Float]) -> Float {
        var s: Float = 0
        for i in 0..<min(q.count, f.count) { s += Float(q[i]) * f[i] }
        return s / 127
    }

    /// Embeds the units, reusing `old` vectors of the same model whose text did not change; nil when an embed fails.
    static func build(modelId: String, fingerprint: [UInt8], units: [Unit], old: Index?, embed: (String) -> [Float]?) -> Index? {
        var reuse: [Int64: Vector] = [:]
        if let old, old.modelId == modelId { for v in old.vectors { reuse[v.textHash] = v } }
        var vs: [Vector] = []
        for u in units {
            let h = u.textHash
            if let v = reuse[h] { vs.append(Vector(kind: u.kind, a: u.a, b: u.b, textHash: h, q: v.q)); continue }
            guard let e = embed(EmbedPrompts.document(u.text)) else { return nil }
            vs.append(Vector(kind: u.kind, a: u.a, b: u.b, textHash: h, q: quantize(e)))
        }
        return Index(modelId: modelId, fingerprint: fingerprint, vectors: vs)
    }

    static func encode(_ x: Index) -> Data {
        var d = Data()
        func be32(_ v: UInt32) { d.append(contentsOf: [UInt8(v >> 24), UInt8(v >> 16 & 0xff), UInt8(v >> 8 & 0xff), UInt8(v & 0xff)]) }
        func be16(_ v: UInt16) { d.append(contentsOf: [UInt8(v >> 8), UInt8(v & 0xff)]) }
        func be64(_ v: UInt64) { for s in stride(from: 56, through: 0, by: -8) { d.append(UInt8(v >> UInt64(s) & 0xff)) } }
        be32(magic); be32(version)
        let id = Array(x.modelId.utf8); be16(UInt16(id.count)); d.append(contentsOf: id)
        be16(UInt16(dim)); d.append(contentsOf: x.fingerprint); be32(UInt32(x.vectors.count))
        for v in x.vectors {
            d.append(v.kind.rawValue); be32(UInt32(bitPattern: v.a)); be32(UInt32(bitPattern: v.b)); be64(UInt64(bitPattern: v.textHash))
            d.append(contentsOf: v.q.map { UInt8(bitPattern: $0) })
        }
        be32(crc32(d))
        return d
    }
    static func decode(_ d: Data) -> Index? {
        let b = [UInt8](d); var p = 0
        func u32() -> UInt32? { guard p + 4 <= b.count else { return nil }; defer { p += 4 }; return UInt32(b[p]) << 24 | UInt32(b[p+1]) << 16 | UInt32(b[p+2]) << 8 | UInt32(b[p+3]) }
        func u16() -> UInt16? { guard p + 2 <= b.count else { return nil }; defer { p += 2 }; return UInt16(b[p]) << 8 | UInt16(b[p+1]) }
        func u64() -> UInt64? { guard let hi = u32(), let lo = u32() else { return nil }; return UInt64(hi) << 32 | UInt64(lo) }
        guard b.count >= 4, crc32(d.prefix(b.count - 4)) == UInt32(b[b.count-4]) << 24 | UInt32(b[b.count-3]) << 16 | UInt32(b[b.count-2]) << 8 | UInt32(b[b.count-1]) else { return nil }
        guard u32() == magic, u32() == version, let n = u16(), p + Int(n) <= b.count else { return nil }
        let id = String(decoding: b[p..<p+Int(n)], as: UTF8.self); p += Int(n)
        guard u16() == UInt16(dim), p + 32 <= b.count else { return nil }
        let fp = Array(b[p..<p+32]); p += 32
        guard let count = u32() else { return nil }
        var vs: [Vector] = []; vs.reserveCapacity(Int(count))
        for _ in 0..<count {
            guard p < b.count, let k = Kind(rawValue: b[p]) else { return nil }; p += 1
            guard let a = u32(), let bb = u32(), let h = u64(), p + dim <= b.count else { return nil }
            let q = b[p..<p+dim].map { Int8(bitPattern: $0) }; p += dim
            vs.append(Vector(kind: k, a: Int32(bitPattern: a), b: Int32(bitPattern: bb), textHash: Int64(bitPattern: h), q: q))
        }
        return Index(modelId: id, fingerprint: fp, vectors: vs)
    }
    static func write(_ x: Index, to u: URL) throws { try encode(x).write(to: u, options: .atomic) }
    static func read(_ u: URL) -> Index? { (try? Data(contentsOf: u)).flatMap(decode) }
    static func isCurrent(_ u: URL, modelId: String, fingerprint: [UInt8]) -> Bool {
        guard let x = read(u) else { return false }
        return x.modelId == modelId && x.fingerprint == fingerprint
    }

    private static let crcTable: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i); for _ in 0..<8 { c = c & 1 == 1 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }; return c }
    static func crc32(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xffffffff
        for b in d { c = crcTable[Int((c ^ UInt32(b)) & 0xff)] ^ (c >> 8) }
        return c ^ 0xffffffff
    }
}

/// Streaming SHA-256 over byte slices (CryptoKit's hasher, wrapped so the fingerprint code reads like Android's).
struct SHA256Hasher {
    private var h = CryptoKit.SHA256()
    mutating func update(_ b: [UInt8]) { h.update(data: Data(b)) }
    func finalize() -> [UInt8] { Array(h.finalize()) }
}

/// Thread-safe flag polled by the embed loop (set from the main actor, read on the worker).
final class IndexStop: @unchecked Sendable {
    private let lock = NSLock(); private var v = false
    var value: Bool { get { lock.lock(); defer { lock.unlock() }; return v } set { lock.lock(); v = newValue; lock.unlock() } }
}

/// Builds and refreshes the per-session indexes on one background worker (Android LibraryIndexer):
/// a saved meeting goes first, `catchUp` queues the stale backlog (one at a time, only above
/// `minBattery` % or charging, cancellable), the worker waits while the app is busy. A backlog
/// meeting whose build is running when a job starts is cut between two chunks and re-queued (T10).
@MainActor final class LibraryIndexer: ObservableObject {
    static let shared = LibraryIndexer()
    static let minBattery = 20
    @Published private(set) var indexing: Set<UUID> = []
    @Published private(set) var pending = 0
    /// True while a recording, a transcription or a reading runs (set by the Model).
    var busy: () -> Bool = { false }
    var recording: () -> Bool = { false }
    private var priority: [Session] = [], backlog: [Session] = []
    private var running = false
    nonisolated static let indexThreads = min(2, ProcessInfo.processInfo.activeProcessorCount)

    /// Index (or refresh) one saved session as soon as possible.
    func request(_ s: Session) {
        priority.removeAll { $0.id == s.id }; backlog.removeAll { $0.id == s.id }
        priority.append(s); pending = priority.count + backlog.count; kick()
    }
    /// Sessions whose index is missing, older than their file, or from another model.
    static func stale(_ sessions: [Session]) -> [Session] {
        let root = SessionIndex.url(UUID()).deletingLastPathComponent()
        return sessions.filter { s in
            let iu = SessionIndex.url(s.id)
            guard let im = (try? FileManager.default.attributesOfItem(atPath: iu.path)[.modificationDate]) as? Date else { return true }
            let sm = (try? FileManager.default.attributesOfItem(atPath: root.appendingPathComponent(s.id.uuidString + ".json").path)[.modificationDate]) as? Date ?? .distantPast
            if im < sm { return true }
            return SessionIndex.read(iu)?.modelId != ReaderModel.embedId
        }
    }
    func catchUp(_ sessions: [Session]) {
        guard EmbedHost.ready else { return }
        catchUpCancelled = false
        let ids = Set(priority.map(\.id))
        backlog = Self.stale(sessions).filter { !ids.contains($0.id) }
        pending = priority.count + backlog.count; kick()
    }
    func cancelCatchUp() { catchUpCancelled = true; backlog = []; pending = priority.count }
    private var catchUpCancelled = false
    private func batteryOk() -> Bool {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let st = UIDevice.current.batteryState, lv = UIDevice.current.batteryLevel
        return st == .charging || st == .full || lv < 0 || Int(lv * 100) > Self.minBattery
    }
    func forget(_ id: UUID) { priority.removeAll { $0.id == id }; backlog.removeAll { $0.id == id }; try? FileManager.default.removeItem(at: SessionIndex.url(id)); pending = priority.count + backlog.count }

    private func kick() {
        guard !running else { return }
        running = true
        Task { await loop(); running = false }
    }
    private func loop() async {
        while let s = priority.first ?? backlog.first {
            let fromBacklog = priority.first == nil
            if fromBacklog && catchUpCancelled { backlog = []; pending = 0; continue }
            if busy() || !EmbedHost.mayLoad(recording: recording()) || (fromBacklog && !batteryOk()) {
                try? await Task.sleep(nanoseconds: 1_000_000_000); continue
            }
            guard EmbedHost.ready else { priority = []; backlog = []; pending = 0; return }
            priority.removeAll { $0.id == s.id }; backlog.removeAll { $0.id == s.id }
            pending = priority.count + backlog.count
            indexing.insert(s.id)
            // A backlog build yields to a job (or to cancelCatchUp) between two chunks.
            let stop = IndexStop()
            let watch = fromBacklog ? Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    guard let self else { return }
                    if self.busy() || self.catchUpCancelled { stop.value = true; return }
                }
            } : nil
            let r = await Task.detached(priority: .utility) { Self.index(s, stop: stop) }.value
            watch?.cancel()
            indexing.remove(s.id)
            switch r {
            case .done: break
            case .failed: StatusLog.add("index failed for \(s.id)")
            case .stopped:
                if catchUpCancelled { StatusLog.add("catch-up cancelled during \(s.id)") }
                else { backlog.insert(s, at: 0); pending = priority.count + backlog.count; StatusLog.add("catch-up of \(s.id) yields to a job") }
            }
        }
    }
    enum Outcome { case done, failed, stopped }
    nonisolated static func index(_ s: Session, stop: IndexStop? = nil) -> Outcome {
        if build(s, stop: stop) { return .done }
        return stop?.value == true ? .stopped : .failed
    }
    private nonisolated static func build(_ s: Session, stop: IndexStop?) -> Bool {
        let u = SessionIndex.url(s.id)
        let fp = SessionIndex.fingerprint(s)
        if SessionIndex.isCurrent(u, modelId: ReaderModel.embedId, fingerprint: fp) { return true }
        let t0 = Date()
        let built: SessionIndex.Index?? = EmbedHost.use(threads: indexThreads) { e in
            let units = SessionIndex.units(s) { e.tokens($0) }
            return SessionIndex.build(modelId: ReaderModel.embedId, fingerprint: fp, units: units, old: SessionIndex.read(u), embed: { stop?.value == true ? nil : e.embed($0) })
        }
        guard let b = built, let x = b else { return false }
        do { try SessionIndex.write(x, to: u) } catch { return false }
        StatusLog.add(String(format: "indexed %d units in %.1f s", x.vectors.count, Date().timeIntervalSince(t0)))
        return true
    }
}

/// Library search: lexical (title, summary, notes, open transcript) fused with the semantic index (Android LibrarySearch).
enum LibrarySearch {
    static let maxPassages = 3, minCos: Float = 0.45, rrfK: Float = 60, lexWeight: Float = 0.3
    struct Passage { let kind: SessionIndex.Kind, uttIndex: Int, uttEnd: Int, timeSec: Double, speaker: String?, snippet: String, semanticOnly: Bool
        var score: Float }
    struct Hit { let session: Session, score: Float, passages: [Passage] }

    private static let cc = OpenCC.transcript(.simplified)
    static func norm(_ s: String) -> String { cc.convert(s.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)) }

    static func lexical(_ sessions: [Session], query: String, openId: UUID? = nil) -> [Hit] {
        let q = norm(query); let terms = q.split(separator: " ").map(String.init).filter { !$0.isEmpty }
        guard !terms.isEmpty else { return [] }
        func all(_ t: String) -> Bool { let n = norm(t); return terms.allSatisfy { n.contains($0) } }
        var hits: [Hit] = []
        for s in sessions {
            var ps: [Passage] = []
            if all(s.title) { ps.append(Passage(kind: .meeting, uttIndex: -1, uttEnd: -1, timeSec: 0, speaker: nil, snippet: s.title, semanticOnly: false, score: 1)) }
            for l in s.summary.split(separator: "\n").map(String.init) where all(l) { ps.append(Passage(kind: .meeting, uttIndex: -1, uttEnd: -1, timeSec: 0, speaker: nil, snippet: l.trimmingCharacters(in: .whitespaces), semanticOnly: false, score: 0.9)) }
            for (i, n) in s.notes.enumerated() where all(n.text) { ps.append(Passage(kind: .note, uttIndex: i, uttEnd: i, timeSec: SessionIndex.noteTime(n), speaker: nil, snippet: n.text, semanticOnly: false, score: 0.9)) }
            if s.id == openId { for (i, u) in s.lines.enumerated() where all(u.text) { ps.append(Passage(kind: .chunk, uttIndex: i, uttEnd: i, timeSec: u.start, speaker: s.name(u.speaker), snippet: u.text, semanticOnly: false, score: 0.8)) } }
            guard !ps.isEmpty else { continue }
            ps.sort { $0.score > $1.score }
            hits.append(Hit(session: s, score: ps[0].score, passages: Array(ps.prefix(maxPassages))))
        }
        return hits.sorted { $0.score > $1.score }
    }

    nonisolated(unsafe) private static var cache: [UUID: (Date, SessionIndex.Index)] = [:]   // guarded by cacheLock
    private static let cacheLock = NSLock()
    static func index(_ id: UUID) -> SessionIndex.Index? {
        let u = SessionIndex.url(id)
        guard let m = (try? FileManager.default.attributesOfItem(atPath: u.path)[.modificationDate]) as? Date else { return nil }
        cacheLock.lock(); defer { cacheLock.unlock() }
        if let c = cache[id], c.0 == m { return c.1 }
        guard let x = SessionIndex.read(u) else { return nil }
        cache[id] = (m, x); return x
    }

    static func dense(_ sessions: [Session], qv: [Float], maxPassages: Int = maxPassages, snippetChars: Int = 200) -> [Hit] {
        var hits: [Hit] = []
        for s in sessions {
            guard let x = index(s.id) else { continue }
            // One MEETING passage at most: a long summary has several parts, which would fill every
            // slot and then fold into one title row, leaving the meeting with no passage to show.
            var seenMeeting = false
            let scored = x.vectors.map { ($0, SessionIndex.dot($0.q, qv)) }.filter { $0.1 >= minCos }.sorted { $0.1 > $1.1 }
                .filter { v in guard v.0.kind == .meeting else { return true }; defer { seenMeeting = true }; return !seenMeeting }.prefix(maxPassages)
            guard !scored.isEmpty else { continue }
            let ps = scored.compactMap { passage(s, $0.0, $0.1, snippetChars) }
            guard !ps.isEmpty else { continue }
            hits.append(Hit(session: s, score: ps[0].score, passages: ps))
        }
        return hits.sorted { $0.score > $1.score }
    }
    /// Keyword pass for Ask (Android LibrarySearch.keyword): BM25 (Keywords) over the indexed notes and chunks of
    /// every meeting, folded to one script; `maxPassages` best per meeting. A passage's BM25 doc is kept while its
    /// text is unchanged (folding and term codes are the cost; the passages themselves are cheap to rebuild).
    static func keyword(_ sessions: [Session], query: String, maxPassages: Int = maxPassages, snippetChars: Int = 200) -> [Hit] {
        let table = OpenCC.simplifiedChars
        var units: [(Session, Passage, Keywords.Doc)] = []
        for s in sessions {
            guard let x = index(s.id) else { continue }
            let ps = x.vectors.filter { $0.kind != .meeting }.compactMap { passage(s, $0, 0, snippetChars) }
            kwLock.lock(); let old = kwCache[s.id] ?? [:]; kwLock.unlock()
            var fresh: [String: Keywords.Doc] = [:]
            for p in ps {
                let d = old[p.snippet] ?? fresh[p.snippet] ?? Keywords.doc(Keywords.fold(p.snippet, table))
                fresh[p.snippet] = d; units.append((s, p, d))
            }
            kwLock.lock(); kwCache[s.id] = fresh; kwLock.unlock()
        }
        let ids = Set(sessions.map(\.id))
        kwLock.lock(); kwCache = kwCache.filter { ids.contains($0.key) }; kwLock.unlock()
        let scores = Keywords.score(Keywords.fold(query, table), units.map(\.2))
        var per: [UUID: [Int]] = [:]
        for i in units.indices where scores[i] > 0 { per[units[i].0.id, default: []].append(i) }
        return per.values.map { ix -> Hit in
            let ps = ix.sorted { scores[$0] > scores[$1] }.prefix(maxPassages).map { i -> Passage in
                let p = units[i].1
                return Passage(kind: p.kind, uttIndex: p.uttIndex, uttEnd: p.uttEnd, timeSec: p.timeSec, speaker: p.speaker, snippet: p.snippet, semanticOnly: false, score: scores[i]) }
            return Hit(session: units[ix[0]].0, score: ps[0].score, passages: Array(ps))
        }.sorted { $0.score > $1.score }
    }
    nonisolated(unsafe) private static var kwCache: [UUID: [String: Keywords.Doc]] = [:]   // guarded by kwLock
    private static let kwLock = NSLock()

    private static func passage(_ s: Session, _ v: SessionIndex.Vector, _ cos: Float, _ chars: Int) -> Passage? {
        switch v.kind {
        case .meeting:
            let first = s.summary.split(separator: "\n").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
            return Passage(kind: .meeting, uttIndex: -1, uttEnd: -1, timeSec: 0, speaker: nil, snippet: s.title.isEmpty ? first : s.title, semanticOnly: true, score: cos)
        case .note:
            let i = Int(v.a); guard s.notes.indices.contains(i) else { return nil }
            return Passage(kind: .note, uttIndex: i, uttEnd: i, timeSec: SessionIndex.noteTime(s.notes[i]), speaker: nil, snippet: s.notes[i].text, semanticOnly: true, score: cos)
        case .chunk:
            let a = Int(v.a), b = Int(v.b); guard s.lines.indices.contains(a), s.lines.indices.contains(b), a <= b else { return nil }
            let text = s.lines[a...b].map(\.text).joined(separator: " ")
            return Passage(kind: .chunk, uttIndex: a, uttEnd: b, timeSec: s.lines[a].start, speaker: s.name(s.lines[a].speaker), snippet: String(text.prefix(chars)), semanticOnly: true, score: cos)
        }
    }

    /// Reciprocal-rank fusion of both lists; a passage found by both ways is shown as lexical (no ≈).
    static func fuse(_ lex: [Hit], _ dense: [Hit]) -> [Hit] {
        if dense.isEmpty { return lex }
        struct Key: Hashable { let id: UUID, kind: UInt8, utt: Int }
        var score: [Key: Float] = [:], best: [Key: Passage] = [:], session: [UUID: Session] = [:]
        func add(_ hs: [Hit], _ w: Float) {
            var rank = 0
            for h in hs { session[h.session.id] = h.session
                for p in h.passages {
                    let k = Key(id: h.session.id, kind: p.kind.rawValue, utt: p.uttIndex)
                    score[k, default: 0] += w / (rrfK + Float(rank)); rank += 1
                    if let b = best[k] { if b.semanticOnly && !p.semanticOnly { best[k] = p } } else { best[k] = p }
                }
            }
        }
        add(dense, 1); add(lex, lexWeight)
        var per: [UUID: [Passage]] = [:]
        for (k, sc) in score { var p = best[k]!; p.score = sc; per[k.id, default: []].append(p) }
        return per.compactMap { id, ps -> Hit? in
            guard let s = session[id] else { return nil }
            let top = Array(ps.sorted { $0.score > $1.score }.prefix(maxPassages))
            return Hit(session: s, score: top[0].score + 0.1 * logf(Float(ps.count)), passages: top)
        }.sorted { $0.score > $1.score }
    }

    /// Lexical always; the semantic half when the model is loaded or may be loaded now.
    static func search(_ sessions: [Session], query: String, openId: UUID? = nil, recording: Bool) -> [Hit] {
        let lex = lexical(sessions, query: query, openId: openId)
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= 2, EmbedHost.ready, EmbedHost.mayLoad(recording: recording) else { return lex }
        guard let qv = EmbedHost.use(threads: 2, { $0.embed(EmbedPrompts.query(q)) }) ?? nil else { return lex }
        return fuse(lex, dense(sessions, qv: qv))
    }
}
