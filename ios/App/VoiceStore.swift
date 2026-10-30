import Foundation
import UIKit

/// The speaker graph through the C bridge (speaker_c.h). Loaded by the voice lane and the "Me" enrolment.
final class SpeakerEmbedder: @unchecked Sendable {
    private let h: UnsafeMutableRawPointer
    let dim: Int
    init?(path: String, threads: Int, card: SpeakerCard) {
        guard let p = pm_spk_create(path, Int32(threads), Int32(card.mels), card.meanNorm ? 1 : 0, card.int16Scale ? 1 : 0) else { return nil }
        h = p; dim = Int(pm_spk_dim(p))
    }
    deinit { pm_spk_free(h) }
    /// `pcm`: the pieces back to back (16 kHz mono); `lens`: their lengths. Nil when there is no speech or on error.
    func embed(_ pcm: [Float], lens: [Int]) -> [Float]? {
        var out = [Float](repeating: 0, count: dim)
        let l = lens.map { Int32($0) }
        let n = pm_spk_embed(h, pcm, l, Int32(l.count), &out, Int32(dim))
        return n > 0 ? Array(out.prefix(Int(n))) : nil
    }
}

/// Voice recognition (Phase 2, opt-in; docs/design/speaker-tags.md §2.5, §2.6, §4; Android VoiceStore.kt): each
/// meeting's sidecar (Application Support/voices/<session id>.bin, every speaker's vector), the people's confirmed
/// exemplars (people/voices.bin), and the background lane that computes the sidecars from the meetings' audio —
/// never beside a recording, a transcription or a reading, only above 20 % battery or charging. Exemplars follow the
/// links: a speaker the user linked to a person teaches that person's voice (unless it does not match their other
/// meetings: then it waits for "Learn anyway"). Nothing here ever names a speaker: it only ranks suggestions.
/// Local only: Application Support is excluded from backups, and nothing here is exported.
@MainActor final class VoiceStore: ObservableObject, VoiceHooks.Impl, VoiceLinks.Impl {
    static let shared = VoiceStore()
    static let minBattery = 20
    static let meEnrolment = "me-enrolment"
    nonisolated static let card = SpeakerCard.shipped

    /// Bumped when a sidecar or an exemplar changes (the rename sheet re-ranks).
    @Published private(set) var version = 0
    /// Meetings whose vectors are being computed (the sheet says "Comparing voices…").
    @Published private(set) var working: Set<UUID> = []
    /// Where each queued, running or failed meeting stands (the sheet's note); absent once its vectors are written.
    @Published private(set) var status: [UUID: VoiceLane.Status] = [:]
    /// Download of the model (fraction), nil when idle.
    @Published private(set) var download: Double?
    /// The last download of the model failed (the sheet says so, with Retry); cleared when one starts.
    @Published private(set) var downloadFailed = false
    /// The download is paused by DownloadResumer (no network, suspended): it resumes on its own.
    @Published private(set) var downloadPaused = false
    /// A confirmed name whose voice does not match the person's other meetings.
    struct Outlier: Equatable { let session: UUID, speaker: Int, person: String }
    @Published var outlier: Outlier?

    /// Speakers whose name was just confirmed, per meeting: whichever sync of the meeting runs first (this hook's,
    /// or the lane's after the save) announces their outlier.
    private var announcing: [UUID: Set<Int>] = [:]
    private var exemplars: [VoiceFiles.Exemplar]?
    private var queue: [UUID] = []
    private var running = false
    /// The meeting whose rename sheet is open: computed first, even while the app is busy.
    private var front: UUID?

    private nonisolated static let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    nonisolated static let peopleFile = support.appendingPathComponent("people/voices.bin")
    nonisolated static let sidecarDir = support.appendingPathComponent("voices")
    nonisolated static func sidecar(_ id: UUID) -> URL { sidecarDir.appendingPathComponent(id.uuidString + ".bin") }

    /// The model as a ModelStore download: the card's files under its revision URL, in models/<dirName>.
    nonisolated static let model = ReaderModel(id: card.dirName, repo: "", rev: card.revision, files: card.files.sorted { $0.key < $1.key }.map {
        ModelFile(name: $0.key, remote: card.revision + "/" + $0.key, size: $0.value.size, sha256: $0.value.sha256)
    })
    nonisolated static var modelDir: URL { support.appendingPathComponent("models").appendingPathComponent(card.dirName) }
    /// Every file at its size and the revision marker on the card's revision (as Android speakerReady).
    nonisolated static func modelReady() -> Bool {
        let fm = FileManager.default
        guard card.files.allSatisfy({ (try? fm.attributesOfItem(atPath: modelDir.appendingPathComponent($0.key).path)[.size] as? Int64) == $0.value.size })
        else { return false }
        return (try? String(contentsOf: modelDir.appendingPathComponent(ModelStore.revisionMarker), encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines) == card.revision
    }
    nonisolated static let threads = min(2, ProcessInfo.processInfo.activeProcessorCount)
    /// Bumped by `disable` / `deleteAll`: a computation that started before stores nothing (Android generation).
    nonisolated static let generation = VoiceGeneration()

    private init() {}
    /// Attaches the hooks and starts the backfill (Model, at launch).
    func attach() { VoiceHooks.impl = self; VoiceLinks.impl = self; catchUp() }

    var enabled: Bool { PeopleStore.shared.doc.voice }
    private var library: SessionLibrary? { PeopleStore.shared.library }

    private func all() -> [VoiceFiles.Exemplar] {
        if let e = exemplars { return e }
        let e = (try? Data(contentsOf: Self.peopleFile)).flatMap(VoiceFiles.decodePeople) ?? []
        exemplars = e
        return e
    }
    private func edit(_ f: ([VoiceFiles.Exemplar]) -> [VoiceFiles.Exemplar]) {
        let cur = all(), n = f(cur)
        guard n != cur else { return }
        exemplars = n
        do {
            try FileManager.default.createDirectory(at: Self.peopleFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try VoiceFiles.encodePeople(n).write(to: Self.peopleFile, options: .atomic)
        } catch { StatusLog.add("voice: could not write voices: \(error)") }
        version += 1
    }

    nonisolated static func readMeeting(_ id: UUID) -> VoiceFiles.MeetingVoices? {
        (try? Data(contentsOf: sidecar(id))).flatMap(VoiceFiles.decodeMeeting).flatMap { $0.modelId == card.id ? $0 : nil }
    }

    /// Each person's voice (confirmed exemplars of the current model), leaving out `exceptMeeting`'s own.
    func voices(exceptMeeting: String? = nil) -> [String: [Float]] {
        let off = Set(PeopleStore.shared.doc.people.filter { !$0.voiceEnabled }.map(\.id))
        return VoiceFiles.Exemplars.voices(all().filter { $0.meeting != exceptMeeting && !off.contains($0.person) }, Self.card.id)
    }
    /// People with a learned voice: person → (meetings, seconds).
    func known() -> [String: (meetings: Int, seconds: Float)] {
        var out: [String: (meetings: Int, seconds: Float)] = [:]
        for (p, es) in Dictionary(grouping: all().filter { $0.confirmed && $0.modelId == Self.card.id }, by: \.person) {
            out[p] = (Set(es.map(\.meeting)).count, es.reduce(0) { $0 + $1.seconds })
        }
        return out
    }
    func pendingOutliers(_ person: String) -> [VoiceFiles.Exemplar] {
        all().filter { $0.person == person && !$0.confirmed && $0.modelId == Self.card.id }
    }

    // MARK: switches

    /// Turns recognition on (downloading the model once) and starts the backfill of the library.
    func enable() async -> Bool {
        if !Self.modelReady() {
            download = 0; downloadFailed = false
            let store = ModelStore(), m = Self.model
            do {
                try await DownloadResumer.shared.run("voice model", onPause: { p in VoiceStore.shared.downloadPaused = p }) {
                    try await store.download(m) { d, t in Task { @MainActor in let v = VoiceStore.shared; v.download = Double(d) / Double(max(t, 1)); v.downloadPaused = false } }
                }
            } catch { StatusLog.add("voice: model download failed: \(error)") }
            download = nil; downloadPaused = false
            guard Self.modelReady() else { downloadFailed = true; return false }
            StatusLog.add("voice: model downloaded (\(Self.card.id))")
        }
        PeopleStore.shared.setVoice(true)
        catchUp()
        return true
    }
    /// Off: `deleteData` also removes every voice vector (people and meetings).
    func disable(deleteData: Bool) {
        PeopleStore.shared.setVoice(false)
        queue = []; Self.generation.bump()
        if deleteData { deleteAll() }
    }
    /// "Delete all voice data": the people's exemplars and every meeting's sidecar; the switch goes off.
    func deleteAll() {
        PeopleStore.shared.setVoice(false)
        queue = []; exemplars = []
        Self.generation.bump {
            try? FileManager.default.removeItem(at: Self.peopleFile)
            try? FileManager.default.removeItem(at: Self.sidecarDir)
        }
        StatusLog.add("voice: all voice data deleted")
        version += 1
    }
    /// "Forget Mark's voice": the samples go and learning stops for Mark until the user turns it back on.
    func forget(_ person: String) {
        PeopleStore.shared.setVoiceEnabled(person, false)
        edit { VoiceFiles.Exemplars.removePerson($0, person) }
        version += 1
    }
    /// "Learn this voice" back on: relearned from the meetings where the person is named.
    func setLearning(_ person: String, _ on: Bool) {
        PeopleStore.shared.setVoiceEnabled(person, on)
        if on { catchUp() }
        version += 1
    }
    /// "Learn anyway" after the outlier warning.
    func learnAnyway(_ person: String, meeting: String) {
        edit { $0.map { var e = $0; if e.person == person && e.meeting == meeting { e.confirmed = true }; return e } }
    }

    // MARK: hooks from the directory and the rename sheet

    func onMerge(from: String, into: String) { edit { VoiceFiles.Exemplars.move($0, from, into) } }
    func onDeletePerson(_ id: String) { edit { VoiceFiles.Exemplars.removePerson($0, id) } }
    func onSplit(session: UUID, from: String, to: String) { edit { VoiceFiles.Exemplars.moveMeeting($0, session.uuidString, from, to) } }
    func onMeetingDeleted(_ id: UUID) {
        // The user's decision: a deleted meeting's voice samples go, even when it was a person's only source.
        edit { VoiceFiles.Exemplars.removeMeeting($0, id.uuidString) }
        queue.removeAll { $0 == id }
        try? FileManager.default.removeItem(at: Self.sidecar(id))
    }
    func confirmed(session: UUID, speaker: Int, person: String) {
        guard enabled else { return }
        announcing[session, default: []].insert(speaker)
        Task {
            // The sheet's save reaches the library a moment later: wait for the link before reading it.
            for _ in 0..<30 {
                if library?.sessions.first(where: { $0.id == session })?.speakerPeople?[String(speaker)] == person { break }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            if syncMeeting(session, announce: [speaker]) == nil { request(session) }
        }
    }
    func unlinked(session: UUID, speaker: Int, person: String) {
        edit { $0.filter { !($0.person == person && $0.meeting == session.uuidString && $0.speaker == speaker) } }
    }

    /// The meeting's exemplars follow its links (speaker → person) and its sidecar. Nil when the sidecar is missing
    /// or stale (the lane computes it first). `announce`: speakers whose new exemplar that does not match is
    /// announced (a name just confirmed); a backfill stays silent.
    @discardableResult func syncMeeting(_ id: UUID, announce: Set<Int> = []) -> Bool? {
        guard let mv = Self.readMeeting(id), let sessions = library?.sessions, let s = sessions.first(where: { $0.id == id }) else { return nil }
        let announce = announce.union(announcing[id] ?? [])
        let links = PeopleStore.links(s), doc = PeopleStore.shared.doc, card = Self.card, key = id.uuidString
        let order = Dictionary(sessions.map { ($0.id.uuidString, Int64($0.date.timeIntervalSince1970)) }, uniquingKeysWith: { a, _ in a })
        var found: Outlier?
        edit { cur in
            // Recognition turned off (or its data deleted) meanwhile: nobody learns from this meeting.
            guard enabled else { return cur }
            var l = cur.filter { e in !(e.meeting == key && links[e.speaker]?.person != e.person) }
            for (sid, link) in links.sorted(by: { $0.key < $1.key }) {
                guard let p = doc.byId(link.person), p.voiceEnabled, let v = mv.of(sid), Double(v.seconds) >= card.minCleanSec else { continue }
                let old = l.first { $0.person == p.id && $0.meeting == key && $0.modelId == card.id }
                let others = VoiceFiles.Exemplars.voices(l.filter { $0.meeting != key }, card.id)[p.id]
                let fits = others.map { VoiceMath.cos(v.vector, $0) >= card.outlier } ?? true
                let confirmed = old?.confirmed ?? fits
                if !confirmed && old == nil && announce.contains(sid) { found = Outlier(session: id, speaker: sid, person: p.id) }
                l = VoiceFiles.Exemplars.put(l, VoiceFiles.Exemplar(person: p.id, modelId: card.id, meeting: key, speaker: sid,
                                                                     seconds: v.seconds, confirmed: confirmed, vector: v.vector)) { order[$0] ?? 0 }
            }
            return l
        }
        // A pending speaker is announced once its link is there (the save may not have reached the library yet).
        if let p = announcing[id] { let left = p.filter { links[$0] == nil }; announcing[id] = left.isEmpty ? nil : left }
        if let found { outlier = found }
        return true
    }

    // MARK: the lane

    func request(_ id: UUID, front isFront: Bool = false) {
        guard enabled else { return }
        if isFront { front = id }
        guard Self.modelReady() else { status[id] = .modelMissing; return }
        queue.removeAll { $0 == id }; queue.insert(id, at: 0)
        if status[id] != .computing { status[id] = .queued(.queue) }
        kick()
    }
    /// "Retry" after a failure.
    func retry(_ id: UUID) { status[id] = nil; request(id, front: true) }
    /// "Download" when the model is missing: downloads it, then backfills.
    /// The model's download as the sheet shows it.
    var modelState: VoiceLane.Model? { download.map { downloadPaused ? .paused : .downloading($0) } ?? (downloadFailed ? .failed : nil) }
    func downloadModel() { Task { if await enable() { for (id, st) in status where st == .modelMissing { status[id] = nil; request(id) } } } }
    /// Every finished meeting with audio and speech (a fresh sidecar makes the lane skip it at once).
    func catchUp() {
        guard enabled, Self.modelReady(), let library else { return }
        for s in library.sessions where s.audio != nil && !s.lines.isEmpty && !s.noSpeech && !queue.contains(s.id) { queue.append(s.id) }
        kick()
    }
    private func kick() {
        guard !running, !queue.isEmpty else { return }
        running = true
        Task { await loop(); running = false }
    }
    private func busy() -> Bool { LibraryIndexer.shared.busy() || AskEngine.shared.phase.working }
    private func batteryOk() -> Bool {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let st = UIDevice.current.batteryState, lv = UIDevice.current.batteryLevel
        return st == .charging || st == .full || lv < 0 || Int(lv * 100) > Self.minBattery
    }
    private func loop() async {
        var model: SpeakerEmbedder?
        while !queue.isEmpty {
            guard enabled else { for id in queue { status[id] = nil }; queue = []; break }
            guard Self.modelReady() else {
                // Never a silent stop: every meeting that waited says the model is missing (Download).
                StatusLog.add("voice: model missing, \(queue.count) meeting(s) waiting")
                for id in queue { status[id] = .modelMissing }; queue = []; break
            }
            // The meeting on screen first, wherever it is in the backlog.
            if let f = front, let i = queue.firstIndex(of: f), i > 0 { queue.remove(at: i); queue.insert(f, at: 0) }
            let id = queue[0]
            if let w = VoiceLane.wait(front: id == front, busy: busy(), batteryOk: batteryOk()) {
                if status[id] != .queued(w) { status[id] = .queued(w) }
                try? await Task.sleep(nanoseconds: 1_000_000_000); continue
            }
            queue.removeFirst()
            // Not in the library yet: a meeting still being transcribed (named mid-run). JobRunner requests it at the end.
            guard let s = library?.sessions.first(where: { $0.id == id }) else { status[id] = .queued(.transcription); continue }
            working.insert(id); status[id] = .computing
            if model == nil {
                let path = Self.modelDir.appendingPathComponent(Self.card.graph).path
                model = await Task.detached(priority: .utility) { SpeakerEmbedder(path: path, threads: Self.threads, card: Self.card) }.value
                if model == nil {
                    StatusLog.add("voice: model load failed")
                    working.remove(id); status[id] = .failed
                    for q in queue { status[q] = nil }; queue = []; break
                }
            }
            let m = model!, gen = Self.generation.current
            let r = await Task.detached(priority: .utility) { Self.compute(s, m, gen: gen) }.value
            working.remove(id)
            switch r {
            case .written(let cost): StatusLog.add(cost); status[id] = nil; version += 1
            case .unchanged: status[id] = nil
            case .failed: status[id] = .failed; version += 1
            }
            if id == front { front = nil }
            if gen == Self.generation.current { syncMeeting(id) }
        }
    }

    enum Computed: Sendable { case written(String), unchanged, failed }

    /// Computes the meeting's sidecar if it is missing or stale. Only the lines the vectors use are decoded (seeking
    /// into the recording), so a long meeting costs about the same as a short one and an edit does not re-decode it all.
    /// Nothing is written when recognition went off or its data was deleted since `gen`.
    nonisolated static func compute(_ s: Session, _ m: SpeakerEmbedder, gen: Int) -> Computed {
        let card = Self.card
        let turns = CleanSpeech.voiceTurns(s.lines)
        let speakers = Array(Set(s.lines.map(\.speaker))).sorted()
        var spans: [Int: [CleanSpeech.Span]] = [:]
        for k in speakers { spans[k] = CleanSpeech.ordered(turns, speaker: k) }
        let fp = VoiceFiles.fingerprint(spans)
        if readMeeting(s.id)?.fingerprint == fp { return .unchanged }
        guard let a = s.audio else { StatusLog.add("voice \(s.id): no audio"); return .failed }
        let url = JobQueue.audioDir.appendingPathComponent(a)
        guard FileManager.default.fileExists(atPath: url.path) else { StatusLog.add("voice \(s.id): audio file missing"); return .failed }
        let t0 = Date()
        var decodeSec = 0.0, failed = false
        /// [a, b) samples at 16 kHz on the player timeline, decoded from the file with a seek.
        func read(_ a: Int, _ b: Int) -> [Float] {
            guard b > a else { return [] }
            let need = b - a, td = Date()
            var out: [Float] = []; out.reserveCapacity(need)
            do {
                try AudioDecode.stream(url, from: Double(a) / 16_000) { c in
                    out.append(contentsOf: c.prefix(need - out.count)); return out.count < need
                }
            } catch { failed = true; StatusLog.add("voice \(s.id): audio decode failed at \(a / 16_000) s: \(error)") }
            decodeSec += Date().timeIntervalSince(td)
            return out
        }
        let t1 = Date()
        let sr = 16_000, cap = Int(card.capSec * Double(sr))
        var out: [VoiceFiles.SpeakerVoice] = [], speech = 0.0
        for k in speakers {
            // Lines in selection order, each gated to its voiced frames, until capSec of voice.
            var pieces: [[Float]] = [], have = 0
            for sp in spans[k] ?? [] where have < cap {
                let line = read(Int(sp.start * Double(sr)), Int(sp.end * Double(sr)))
                for run in VoiceGate.runs(line, sr: sr) where have < cap {
                    let take = min(run.count, cap - have)
                    pieces.append(Array(line[run.lowerBound..<run.lowerBound + take])); have += take
                }
            }
            let sec = Double(have) / Double(sr)
            if sec < card.minCleanSec { continue }
            if failed { return .failed }
            if let v = m.embed(pieces.flatMap { $0 }, lens: pieces.map(\.count)) {
                out.append(VoiceFiles.SpeakerVoice(speaker: k, seconds: Float(sec), vector: v))
                speech += sec
            }
        }
        let t2 = Date()
        do {
            let mv = VoiceFiles.MeetingVoices(modelId: card.id, fingerprint: fp, speakers: out)
            guard try storeSidecar(s.id, mv, gen: gen) else { StatusLog.add("voice \(s.id): dropped, recognition off or its data deleted meanwhile"); return .unchanged }
        } catch { StatusLog.add("voice \(s.id): could not write: \(error)"); return .failed }
        return .written(String(format: "voice %@: %d/%d speakers, %.0f s speech, decode %d ms, embed %d ms (total %d ms)", s.id.uuidString, out.count,
                               speakers.count, speech, Int(decodeSec * 1000), Int((t2.timeIntervalSince(t1) - decodeSec) * 1000),
                               Int(t2.timeIntervalSince(t0) * 1000)))
    }

    /// Writes a meeting's sidecar computed under `gen`; false (nothing written) after a `disable` / `deleteAll` since.
    nonisolated static func storeSidecar(_ id: UUID, _ mv: VoiceFiles.MeetingVoices, gen: Int, dir: URL = sidecarDir) throws -> Bool {
        try generation.store(gen) {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try VoiceFiles.encodeMeeting(mv).write(to: dir.appendingPathComponent(id.uuidString + ".bin"), options: .atomic)
        }
    }

    /// "Me": a voice from a short recording of the phone's owner (`pcm` 16 kHz mono). Only the vector is kept.
    func enrolMe(_ pcm: [Float], name: String) async -> Person? {
        guard Self.modelReady() else { return nil }
        let path = Self.modelDir.appendingPathComponent(Self.card.graph).path
        let v = await Task.detached(priority: .userInitiated) { () -> [Float]? in
            SpeakerEmbedder(path: path, threads: Self.threads, card: Self.card)?.embed(pcm, lens: [pcm.count])
        }.value
        guard let v else { return nil }
        let me = PeopleStore.shared.doc.people.first { $0.me } ?? PeopleStore.shared.create(name, me: true)
        edit { VoiceFiles.Exemplars.put($0, VoiceFiles.Exemplar(person: me.id, modelId: Self.card.id, meeting: Self.meEnrolment, speaker: -1,
                                                               seconds: Float(pcm.count) / 16000, confirmed: true, vector: v)) }
        StatusLog.add("voice: me enrolled (\(pcm.count / 16000) s)")
        return me
    }

    /// Suggestions for `speaker` of `s`, and the note code when there are none (Android rememberVoiceSuggestions):
    /// 1 too little voice, 2 not computed yet, 3 no voice learned yet, 0 ranked. A missing or stale sidecar is requested.
    func rank(_ s: Session, speaker: Int) -> ([VoiceSuggestion], Int) {
        let card = Self.card
        let turns = CleanSpeech.voiceTurns(s.lines)
        let clean = CleanSpeech.spans(turns, speaker: speaker, cap: card.capSec).reduce(0) { $0 + $1.sec }
        let mv = Self.readMeeting(s.id)
        var spans: [Int: [CleanSpeech.Span]] = [:]
        for k in Set(s.lines.map(\.speaker)) { spans[k] = CleanSpeech.ordered(turns, speaker: k) }
        if mv == nil || mv?.fingerprint != VoiceFiles.fingerprint(spans) { request(s.id, front: true) }
        if clean < card.minCleanSec { return ([], 1) }
        guard let mv else { return ([], 2) }
        if mv.of(speaker) == nil { return ([], 1) }   // its lines hold too little voice
        let voices = voices(exceptMeeting: s.id.uuidString)
        let linked = Set(PeopleStore.links(s).values.compactMap(\.person))
        let excluded = Dictionary(uniqueKeysWithValues: mv.speakers.map { ($0.speaker, linked) })
        let all = VoiceMath.suggest(Dictionary(uniqueKeysWithValues: mv.speakers.map { ($0.speaker, $0.vector) }), voices, card, excluded: excluded)
        return (all[speaker] ?? [], voices.isEmpty ? 3 : 0)
    }

    /// DEV (device checks without taps): VOX_VOICE_DO=op;op… after launch, then Documents/voice-dev.txt reports.
    /// Ops: enable, wait (the lane drains), rank:<id>:<speaker>, pairs:<id>:<id>, forget:<name>, learn:<name>, deleteall.
    func devRun(_ spec: String) async {
        var out = "ops \(spec)\n"
        func person(_ n: String) -> Person? { PeopleStore.shared.doc.people.first { PersonNames.same($0.name, n, PeopleStore.fold) } }
        for op in spec.split(separator: ";").map(String.init) {
            let a = op.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            switch a.first {
            case "enable": let t0 = Date(); out += "enable \(await enable()) in \(Int(Date().timeIntervalSince(t0))) s ready=\(Self.modelReady())\n"
            case "wait":
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                for _ in 0..<600 where running || !queue.isEmpty { try? await Task.sleep(nanoseconds: 1_000_000_000) }
                out += "lane idle\n"
            case "rank" where a.count == 3:
                guard let s = library?.sessions.first(where: { $0.id.uuidString.hasPrefix(a[1].uppercased()) }), let k = Int(a[2]) else { continue }
                let r = rank(s, speaker: k)
                out += "rank \(a[1]):\(k) note=\(r.1) " + r.0.map { "\(PeopleStore.shared.doc.byId($0.person)?.name ?? $0.person)=\(String(format: "%.3f", $0.score))/L\($0.level)" }.joined(separator: " ") + "\n"
            case "pairs" where a.count == 3:   // cosine of every speaker pair across two meetings, best first
                func mv(_ p: String) -> VoiceFiles.MeetingVoices? { library?.sessions.first { $0.id.uuidString.hasPrefix(p.uppercased()) }.flatMap { Self.readMeeting($0.id) } }
                guard let x = mv(a[1]), let y = mv(a[2]) else { out += "pairs: no sidecar\n"; continue }
                let ps = x.speakers.flatMap { u in y.speakers.map { v in (u.speaker, v.speaker, VoiceMath.cos(u.vector, v.vector)) } }.sorted { $0.2 > $1.2 }
                out += "pairs " + ps.prefix(8).map { "\($0.0)-\($0.1)=\(String(format: "%.3f", $0.2))" }.joined(separator: " ") + "\n"
            case "forget" where a.count == 2: if let p = person(a[1]) { forget(p.id) }
            case "learn" where a.count == 2: if let p = person(a[1]) { setLearning(p.id, true) }
            case "deleteall": deleteAll()
            default: break
            }
        }
        out += "voice=\(enabled) ready=\(Self.modelReady()) exemplars=\(all().count) sidecars=\((try? FileManager.default.contentsOfDirectory(atPath: Self.sidecarDir.path))?.count ?? 0) peopleFile=\(FileManager.default.fileExists(atPath: Self.peopleFile.path))\n"
        for (p, k) in known() { out += "known \(PeopleStore.shared.doc.byId(p)?.name ?? p) meetings=\(k.meetings) sec=\(Int(k.seconds))\n" }
        for e in all() { out += "ex \(PeopleStore.shared.doc.byId(e.person)?.name ?? e.person) \(e.meeting.prefix(8)) spk=\(e.speaker) \(Int(e.seconds))s confirmed=\(e.confirmed)\n" }
        for id in (try? FileManager.default.contentsOfDirectory(atPath: Self.sidecarDir.path)) ?? [] {
            if let u = UUID(uuidString: String(id.dropLast(4))), let mv = Self.readMeeting(u) { out += "sidecar \(id.prefix(8)) " + mv.speakers.map { "\($0.speaker):\(Int($0.seconds))s" }.joined(separator: " ") + "\n" }
        }
        try? out.write(to: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("voice-dev.txt"), atomically: true, encoding: .utf8)
    }
}
