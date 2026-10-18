import Foundation

/// A recording or import waiting for the full pipeline (ASR + diarization + reader). Port of the Android persistent
/// processing queue: the audio is on disk before anything else happens, the job list survives an app kill, and a job
/// leaves the list only once its session is saved.
struct Job: Codable, Identifiable, Equatable {
    var id = UUID()
    var date = Date()
    var audio: String        // file name in Application Support/audio
    var title: String? = nil // source title (podcast episode) — wins over a generated one
    /// A podcast episode not downloaded yet (Android download.json): the item exists from the pick on, shows its
    /// download, survives a kill (it resumes from its `.part`) and is processed once `audio` is on disk.
    var download: JobDownload? = nil
}

struct JobDownload: Codable, Equatable {
    var url: String
    /// Why the last attempt failed for good (404, too large…): the row shows it with Retry. Nil while it downloads or waits.
    var error: String? = nil
}

actor JobQueue {
    static let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    static let audioDir = support.appendingPathComponent("audio")
    private let file = support.appendingPathComponent("jobs.json")
    private(set) var jobs: [Job] = []

    init() {
        try? FileManager.default.createDirectory(at: Self.audioDir, withIntermediateDirectories: true)
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
        jobs = (try? dec.decode([Job].self, from: Data(contentsOf: file))) ?? []
    }
    private func persist() {
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
        try? enc.encode(jobs).write(to: file, options: .atomic)
    }
    func add(_ j: Job) { jobs.append(j); persist() }
    func promote(_ id: UUID) { if let i = jobs.firstIndex(where: { $0.id == id }), i > 0 { jobs.insert(jobs.remove(at: i), at: 0); persist() } }
    func contains(_ id: UUID) -> Bool { jobs.contains { $0.id == id } }
    func remove(_ id: UUID) { jobs.removeAll { $0.id == id }; persist() }
    /// The next job to process: one still downloading has no audio yet and waits for it.
    func first(skipping: UUID? = nil) -> Job? { jobs.first { $0.id != skipping && $0.download == nil } }
    func first(excluding: Set<UUID>) -> Job? { jobs.first { !excluding.contains($0.id) && $0.download == nil } }
    func update(_ j: Job) { if let i = jobs.firstIndex(where: { $0.id == j.id }) { jobs[i] = j; persist() } }
    func job(_ id: UUID) -> Job? { jobs.first { $0.id == id } }
    var count: Int { jobs.count }
    var all: [Job] { jobs }
    static func url(_ j: Job) -> URL { audioDir.appendingPathComponent(j.audio) }
}

/// 16 kHz mono 16-bit WAV written while recording. The header is patched on `finish`; after a kill `repair` does it
/// from the file size, so the audio recorded so far is never lost.
final class WavWriter: @unchecked Sendable {
    private let h: FileHandle, lock = NSLock(); private var bytes: UInt32 = 0
    init(_ url: URL) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        h = try FileHandle(forWritingTo: url)
        try h.write(contentsOf: Self.header(0))
    }
    func append(_ pcm: [Float]) {
        var d = Data(capacity: pcm.count * 2)
        for f in pcm { let v = Int16(max(-1, min(1, f)) * 32767); d.append(UInt8(truncatingIfNeeded: v)); d.append(UInt8(truncatingIfNeeded: v >> 8)) }
        lock.lock(); defer { lock.unlock() }
        try? h.write(contentsOf: d); bytes += UInt32(d.count)
    }
    func finish() {
        lock.lock(); defer { lock.unlock() }
        try? h.seek(toOffset: 0); try? h.write(contentsOf: Self.header(bytes)); try? h.close()
    }
    static func repair(_ url: URL) {
        guard url.pathExtension == "wav", let a = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? UInt64, a > 44,
              let h = try? FileHandle(forUpdating: url) else { return }
        try? h.seek(toOffset: 0); try? h.write(contentsOf: header(UInt32(a - 44))); try? h.close()
    }
    private static func header(_ n: UInt32) -> Data {
        func le(_ v: UInt32) -> [UInt8] { (0..<4).map { UInt8(truncatingIfNeeded: v >> (8 * UInt32($0))) } }
        func le16(_ v: UInt16) -> [UInt8] { [UInt8(v & 255), UInt8(v >> 8)] }
        var b = [UInt8]("RIFF".utf8) + le(36 + n) + [UInt8]("WAVEfmt ".utf8) + le(16) + le16(1) + le16(1) + le(16000) + le(32000) + le16(2) + le16(16)
        b += [UInt8]("data".utf8) + le(n); return Data(b)
    }
}

/// Transcript saved when the speech stage ends, so a kill while the reader works (3 GB phones) does not redo the
/// transcription: the retry restores it and runs the reader alone. Times are already in the original audio.
enum Checkpoint {
    static var dir: URL { JobQueue.audioDir.deletingLastPathComponent().appendingPathComponent("checkpoints") }
    private static func url(_ id: UUID) -> URL { dir.appendingPathComponent(id.uuidString + ".json") }
    static func save(_ us: [Utterance], _ id: UUID) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let d = try? JSONEncoder().encode(us) { try? d.write(to: url(id), options: .atomic) }
        try? FileManager.default.removeItem(at: partialUrl(id))   // the complete transcript supersedes the per-minute partial
    }
    static func load(_ id: UUID) -> [Utterance]? { (try? Data(contentsOf: url(id))).flatMap { try? JSONDecoder().decode([Utterance].self, from: $0) } }
    static func remove(_ id: UUID) { try? FileManager.default.removeItem(at: url(id)); try? FileManager.default.removeItem(at: partialUrl(id)) }

    /// Android SessionLibrary.Progress: the frozen transcript of a run stopped before the end (original-audio times),
    /// so Resume transcribes only the audio after `seam`.
    struct Partial: Codable { var lines: [Utterance]; var seam: Double }
    private static func partialUrl(_ id: UUID) -> URL { dir.appendingPathComponent(id.uuidString + ".partial.json") }
    static func savePartial(_ p: Partial, _ id: UUID) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let d = try? JSONEncoder().encode(p) { try? d.write(to: partialUrl(id), options: .atomic) }
    }
    static func loadPartial(_ id: UUID) -> Partial? { (try? Data(contentsOf: partialUrl(id))).flatMap { try? JSONDecoder().decode(Partial.self, from: $0) } }
}

/// Android SeamStitcher: a resumed run restarts the diarizer PREROLL seconds before the seam; the overlap maps its
/// fresh speaker ids onto the saved ones (greedy by overlapping seconds), and only fresh lines centred after the seam are kept.
/// Android SeamEnrollment: before the preroll the restarted diarizer hears a few seconds of every saved speaker, in order
/// of first appearance, cut from saved lines that end before the preroll. Their re-recognised lines map back onto those saved
/// lines, so a speaker silent in the preroll keeps its id (a 45-min import killed at 1000 s came back with 10 speakers, not 8).
/// Engine timeline: [clip, `gap` s of silence]… then the audio from `offset` on. The gap ends the ASR line at each clip edge;
/// a line still running past one is cut at that clip's end: mapped whole, a line spanning two clips 300 s apart overlapped
/// minutes of saved speech and outvoted the preroll (the two main speakers swapped).
struct SeamEnrollment {
    static let perSpeaker = 6.0, maxTotal = 60.0, minClip = 1.0, gap = 1.0
    let clips: [(Double, Double)], offset: Double
    var length: Double { clips.reduce(0) { $0 + $1.1 - $1.0 + Self.gap } }
    static func none(_ offset: Double) -> SeamEnrollment { .init(clips: [], offset: offset) }
    /// (clip start, clip end, engine time where it starts) of the clip or gap holding `t`; nil past the clips.
    private func segment(_ t: Double) -> (Double, Double, Double)? {
        var at = 0.0
        for (s, e) in clips { if t < at + (e - s) + Self.gap { return (s, e, at) }; at += e - s + Self.gap }
        return nil
    }
    /// Engine time (silence skip already restored) → original audio time; a gap maps to its clip's end.
    func toOriginal(_ t: Double) -> Double { segment(t).map { min($0.0 + (t - $0.2), $0.1) } ?? t - length + offset }
    /// An engine-time line → original times, cut at the end of the clip it starts in. The diarizer opens a turn a little
    /// before the voice: a line starting in a gap belongs to what follows the gap (the next clip, or the audio from `offset`),
    /// not to the clip before it (ec45 resumed at 1422 s: speaker 3's whole clip collapsed and it came back as a new speaker).
    func toOriginal(_ start: Double, _ end: Double) -> (Double, Double) {
        guard let (s, e, at) = segment(start) else { return (toOriginal(start), toOriginal(end)) }
        if start - at < e - s { let s0 = s + (start - at); return (s0, max(s0, min(e, s + (end - at)))) }
        guard let (ns, ne, nat) = segment(end) else { return (offset, max(offset, toOriginal(end))) }
        if nat == at { return (e, e) }   // silence only
        return (ns, min(ne, ns + (end - nat)))
    }
    static func plan(_ prior: [Utterance], offset: Double) -> SeamEnrollment {
        guard !prior.isEmpty, offset > 0 else { return .none(offset) }
        let usable = prior.filter { $0.end <= offset && $0.end - $0.start >= minClip }
        var order: [Int] = []; for u in prior where !order.contains(u.speaker) { order.append(u.speaker) }
        var clips: [(Double, Double)] = [], total = 0.0
        for spk in order {
            var got = 0.0
            for u in usable.filter({ $0.speaker == spk }).sorted(by: { $0.end - $0.start > $1.end - $1.start }) {
                if got >= perSpeaker || total >= maxTotal { break }
                let len = min(u.end - u.start, perSpeaker - got, maxTotal - total)
                if len < minClip { break }
                clips.append((u.start, u.start + len)); got += len; total += len
            }
        }
        return .init(clips: clips, offset: offset)
    }
}

