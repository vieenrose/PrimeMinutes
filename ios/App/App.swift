import SwiftUI
import AVFoundation
import UserNotifications
import os
import BackgroundTasks

@main struct PrimeMinutesApp: App {
    @AppStorage("textSize") private var textSize = 0
    @Environment(\.scenePhase) private var phase
    init() { BackgroundWork.register() }
    var body: some Scene {
        WindowGroup { ContentView().modifier(TextSize(steps: Prefs.typeSizeSteps(textSize))) }
            .onChange(of: phase) { if phase == .background { BackgroundWork.schedule() } }
    }
}

/// Queued jobs keep going when the user leaves the app: iOS grants a processing window (usually while charging) that
/// resumes the queue; a job cut short stays queued and restarts at the next launch (Android: foreground service).
enum BackgroundWork {
    static let id = "tw.com.pesi.primeminutes.queue"
    @MainActor static weak var model: Model?
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            StatusLog.add("trace background task started")
            task.expirationHandler = { StatusLog.add("trace background task expired"); schedule() }
            Task { @MainActor in
                if let m = model { m.drain(); await m.drainTask?.value }
                task.setTaskCompleted(success: true)
            }
        }
    }
    static func schedule() {
        Task {
            guard let m = await model, await m.queue.count > 0 else { return }
            let r = BGProcessingTaskRequest(identifier: id)
            r.requiresNetworkConnectivity = false; r.requiresExternalPower = false
            do { try BGTaskScheduler.shared.submit(r) } catch { StatusLog.add("trace background schedule failed: \(error)") }
        }
    }
}

/// Dynamic Type steps from Settings, on top of the system size; nil follows the system.
struct TextSize: ViewModifier {
    let steps: Int?
    @Environment(\.dynamicTypeSize) private var system
    @ViewBuilder func body(content: Content) -> some View {
        if let steps, steps != 0 {
            let all = DynamicTypeSize.allCases
            let i = (all.firstIndex(of: system) ?? 3) + steps
            content.dynamicTypeSize(all[min(max(i, 0), all.count - 1)])
        } else { content }
    }
}

/// Mic chunks arrive on the audio thread, the engine loop drains them on another.
final class ChunkBuffer: @unchecked Sendable {
    private var data: [Float] = []; private let lock = NSLock()
    func add(_ c: [Float]) { lock.lock(); data += c; lock.unlock() }
    func take() -> [Float] { lock.lock(); defer { lock.unlock() }; let d = data; data = []; return d }
    private var shut = false
    var closed: Bool { lock.lock(); defer { lock.unlock() }; return shut }
    func close() { lock.lock(); shut = true; lock.unlock() }
}

/// Local notification when a meeting is ready (Android posts one from its foreground service).
enum Notifier {
    static func requestPermission() {
        if Dev.env["VOX_NONOTIF"] != nil { return }   // DEV: simulator runs over ssh cannot answer the system alert
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
    static func done(_ title: String) {
        let c = UNMutableNotificationContent(); c.title = L("notif_session_ready"); c.body = title; c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }
    /// Android: a queued item that could not be processed (it stays in the queue with Retry).
    static func failed(_ title: String) {
        let c = UNMutableNotificationContent(); c.title = L("svc_queue_item_failed", title); c.body = L("svc_queue_item_failed_hint"); c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }
}

/// Every status line, timestamped, in Documents/status.log (pullable with devicectl: the console is not always attached).
enum StatusLog {
    // DEV builds keep it in Documents for the device test scripts; a release keeps it in Caches, which is
    // never backed up (it carries meeting titles and progress lines).
    #if VOX_DEV
    static let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("status.log")
    #else
    static let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("status.log")
    #endif
    private static let q = DispatchQueue(label: "statuslog", qos: .utility)   // never block the app on file I/O (a stalled devicectl copy held the file)
    nonisolated(unsafe) private static var lastKey = "", lastAt = Date.distantPast
    static func add(_ s: String) {
        let now = Date()
        q.async {
            // progress lines ("轉錄中 12 / 300 秒") differ only by digits: keep one every 15 s
            let key = String(s.filter { !$0.isNumber })
            if key == lastKey && now.timeIntervalSince(lastAt) < 15 { return }
            lastKey = key; lastAt = now
            if let n = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int, n > 1_000_000 { try? FileManager.default.removeItem(at: url) }
            guard let d = (ISO8601DateFormatter().string(from: now) + " " + s + "\n").data(using: .utf8) else { return }
            if let h = try? FileHandle(forWritingTo: url) { defer { try? h.close() }; _ = try? h.seekToEnd(); try? h.write(contentsOf: d) } else { try? d.write(to: url) }
        }
    }
}

/// Debug watchdog: logs the engine stage when it has not changed for 60 s (a hung call shows up in status.log).
enum Stage {
    nonisolated(unsafe) static var name = "idle"; nonisolated(unsafe) static var since = Date()
    static func set(_ n: String) { name = n; since = Date() }
    static let watchdog: Void = { Thread.detachNewThread { while true { Thread.sleep(forTimeInterval: 60)
        let d = Int(Date().timeIntervalSince(since)); if name != "idle" && d >= 60 { StatusLog.add("trace stage \(name) stuck \(d) s, \(os_proc_available_memory() / 1_048_576) MB free") } } } }()
}

/// Runs the reader on its own thread: on slow CPUs (A12: ~12 tok/s prefill) a reader call takes 1-2 min, and inline it
/// would throttle the transcription. Lines queue up; the reader catches up behind the ASR and after it.
/// Test hooks (VOX_* environment variables, the Sample button) exist only in builds made with DEV=1.
/// DEV: VOX_SNAP="3,8" writes the screen to Documents/snap1.png, snap2.png… at those seconds after launch, so a
/// device run can be checked without Xcode (pulled with devicectl). Not compiled into release builds.
enum Snap {
    @MainActor static func schedule(_ spec: String) {
        for (i, t) in spec.split(separator: ",").compactMap({ Double($0) }).enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + t) {
                guard let w = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return }
                let img = UIGraphicsImageRenderer(bounds: w.bounds).image { _ in w.drawHierarchy(in: w.bounds, afterScreenUpdates: true) }
                try? img.pngData()?.write(to: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("snap\(i + 1).png"))
            }
        }
    }
}

/// DEV: VOX_FRAMES=<seconds> writes the screen every <seconds> to Documents/frames/f00001.jpg… (point size, scale 1)
/// until Documents/frames/STOP exists; real-device stand-in for a screen recorder, for the README demo.
enum Frames {
    @MainActor static func start(_ every: Double) {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("frames")
        try? FileManager.default.removeItem(at: dir); try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1
        var n = 0
        Timer.scheduledTimer(withTimeInterval: every, repeats: true) { t in
            MainActor.assumeIsolated {
                if FileManager.default.fileExists(atPath: dir.appendingPathComponent("STOP").path) { t.invalidate(); return }
                guard let w = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return }
                let img = UIGraphicsImageRenderer(bounds: w.bounds, format: fmt).image { _ in w.drawHierarchy(in: w.bounds, afterScreenUpdates: false) }
                n += 1; try? img.jpegData(compressionQuality: 0.8)?.write(to: dir.appendingPathComponent(String(format: "f%05d.jpg", n)))
            }
        }
    }
}

enum Dev {
    /// The root view's onAppear runs again when a cover closes; the launch hooks must run once.
    @MainActor static var ranOnce = false
    #if VOX_DEV
    static let on = true
    static let env = ProcessInfo.processInfo.environment
    #else
    static let on = false
    static let env: [String: String] = [:]
    #endif
}

final class ReaderWorker: @unchecked Sendable {
    private let reader: MeetingReader, lock = NSCondition()
    private var queue: [Line] = [], busy = false, paused = false, stopped = false, snapshot: [Note] = [], total = 0, done = 0
    private var error: Error?
    /// `paused`: hold the lines until `resume()` (low-RAM devices read after the transcription, not during it).
    init(_ r: MeetingReader, paused: Bool = false) {
        reader = r; self.paused = paused
        Thread.detachNewThread { [self] in
            while true {
                lock.lock(); while (queue.isEmpty || paused) && !stopped { busy = false; lock.broadcast(); lock.wait() }
                // Ended by its job: the thread lets go of the reader (and its multi-GB engine) instead of
                // waiting forever, which kept one engine alive per job until jetsam.
                if stopped { busy = false; lock.broadcast(); lock.unlock(); StatusLog.add("trace reader worker ended"); return }
                let l = queue.removeFirst(); busy = true; lock.unlock()
                Stage.set("offer")
                // After a failure the reader's context is unknown: it reads no further (Android ReaderLane keeps its first failure).
                if failure == nil { do { try reader.offer(l) } catch { StatusLog.add("trace reader.offer error: \(error)"); lock.lock(); self.error = error; lock.unlock() } }
                lock.lock(); snapshot = reader.journal; done += 1; lock.unlock()
            }
        }
    }
    func offer(_ l: Line) { lock.lock(); queue.append(l); total += 1; lock.broadcast(); lock.unlock() }
    func resume() { lock.lock(); paused = false; lock.broadcast(); lock.unlock() }
    /// The job is over (finished, stopped or failed): drop what is queued and end the thread after the current line.
    func stop() { lock.lock(); stopped = true; queue.removeAll(); lock.broadcast(); lock.unlock() }
    var journal: [Note] { lock.lock(); defer { lock.unlock() }; return snapshot }
    /// The first reading error, if any: the job then reports "summary unavailable", not an empty meeting.
    var failure: Error? { lock.lock(); defer { lock.unlock() }; return error }
    var progress: (done: Int, total: Int) { lock.lock(); defer { lock.unlock() }; return (done, total) }
    /// Blocks until every queued line has been read.
    func drain(_ tick: (Int, Int) -> Void) {
        lock.lock()
        while (!queue.isEmpty || busy) && !stopped { lock.broadcast(); lock.wait(until: Date().addingTimeInterval(2)); let p = (done, total); lock.unlock(); tick(p.0, p.1); lock.lock() }
        lock.unlock()
    }
}

enum StopFlag { nonisolated(unsafe) static var on = false }

@MainActor
final class Model: ObservableObject {
    @Published var lines: [Utterance] = []
    @Published var notes: [Note] = []
    let agent = AgentUi()
    @Published var title = ""
    @Published var summary = ""
    @Published var status = L("app_ready") { didSet { StatusLog.add(status) } }
    // Dev paths on the Mac (the simulator shares its filesystem).
    let base = Dev.env["VOX_BASE"] ?? ""

    let store = ModelStore()
    let library = LibraryStore()
    /// Application Support holds the meetings (audio, transcripts, search index, checkpoints) and GBs of
    /// models: none of it may go to iCloud backup ("nothing leaves the device", and App Review rejects
    /// re-downloadable data in backups). The flag on the folder covers everything below it.
    nonisolated static func excludeFromBackup() {
        var u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        var v = URLResourceValues(); v.isExcludedFromBackup = true
        do { try u.setResourceValues(v) } catch { StatusLog.add("backup exclusion failed: \(error)") }
        StatusLog.add("app data excluded from backup: \((try? u.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false)")
    }
    init() {
        Self.excludeFromBackup()
        LibraryIndexer.shared.busy = { [weak self] in self.map { $0.activeJob != nil || $0.recording } ?? true }
        LibraryIndexer.shared.recording = { [weak self] in self?.recording ?? true }
        AskEngine.shared.busy = LibraryIndexer.shared.busy; AskEngine.shared.recording = LibraryIndexer.shared.recording
    }
    @Published var sessions: [Session] = []
    @Published var pending = 0
    @Published var waiting: [Job] = []
    @Published var activeJob: UUID?
    func syncQueue() async { waiting = await queue.all; pending = waiting.count }
    /// Android "stop (resumable)": the running job stops at the next chunk and stays queued, parked until resumed.
    @Published var parked: Set<UUID> = []
    func stopProcessing() { guard activeJob != nil else { return }; StopFlag.on = true; status = L("stopping") }
    func resume(_ j: Job) { parked.remove(j.id); failed[j.id] = nil; drain() }
    /// Jobs whose last run failed → their error, shown on the queue row with Retry.
    @Published var failed: [UUID: String] = [:]
    func processNext(_ j: Job) { guard j.id != activeJob else { return }; Task { await queue.promote(j.id); await syncQueue() } }
    func unqueue(_ j: Job) { guard j.id != activeJob else { return }; failed[j.id] = nil; Task {
        await queue.remove(j.id); Checkpoint.remove(j.id)
        // Android: removing from the queue keeps the recording in the library (unprocessed), so it can be transcribed later
        if FileManager.default.fileExists(atPath: JobQueue.url(j).path), !(await library.all()).contains(where: { $0.id == j.id }) {
            var s = Session(title: j.title ?? L("meeting"), summary: "", seconds: AudioDecode.duration(JobQueue.url(j)) ?? 0, lines: [], notes: [])
            s.id = j.id; s.date = j.date; s.audio = j.audio
            try? await library.save(s); sessions = await library.all()
        }
        await syncQueue()
    } }
    func reload() { Task { sessions = await library.all(); await syncQueue(); LibraryIndexer.shared.catchUp(sessions) } }
    func update(_ s: Session) { Task { try? await library.save(s); sessions = await library.all(); LibraryIndexer.shared.request(s) } }
    func remove(_ s: Session) { Task {
        await library.delete(s.id); LibraryIndexer.shared.forget(s.id); sessions = await library.all()
        // Android deletes the whole entry: the audio goes too, unless another session or a queued job still uses it
        if let a = s.audio, !sessions.contains(where: { $0.audio == a }), !(await queue.all).contains(where: { $0.audio == a }) { try? FileManager.default.removeItem(at: JobQueue.audioDir.appendingPathComponent(a)) }
    } }
    func open(_ s: Session) { lines = s.lines; notes = s.notes; title = s.title; summary = s.summary; status = L("archive_of", s.date.formatted(date: .abbreviated, time: .shortened)) }
    private func archive(lines: [Utterance], notes: [Note], title: String, summary: String, seconds: Double, job: Job? = nil) async {
        // No speech: still a library entry with its audio (Android keeps the entry, status_no_speech), never a silent drop.
        let summary = lines.isEmpty && summary.isEmpty ? L("status_no_speech") : summary
        if lines.isEmpty { status = L("status_no_speech") }
        let fallback = lines.first.map { String($0.text.prefix(20)) } ?? L("meeting")
        var s = Session(title: title.isEmpty ? fallback : title, summary: summary, seconds: seconds, lines: lines, notes: notes)
        if let job { s.id = job.id; s.date = job.date; s.audio = job.audio; if let t = job.title, !t.isEmpty { s.title = t } }
        if !summary.isEmpty { s.reader = Prefs.readerId }
        let old = resummarized.removeValue(forKey: s.id)
        // Re-summarize keeps the transcript: the stored one, read NOW — lines, speaker names or a title the user
        // edited while the reader ran were overwritten by the snapshot taken at the tap.
        if let old {
            let cur = await library.all().first { $0.id == s.id } ?? old
            s.lines = cur.lines; s.speakerNames = cur.speakerNames
            if cur.title != old.title { s.title = cur.title }
        }
        do { try await library.save(s) } catch { status = L("session_save_failed"); Notifier.failed(s.title); return }   // Android: never claim success on a failed save
        NotificationCenter.default.post(name: .voxSessionSaved, object: s, userInfo: old.map { ["undo": $0] })
        sessions = await library.all(); await syncQueue()
        LibraryIndexer.shared.request(s)
        Notifier.done(s.title)
    }
    @Published var recording = false
    @Published var elapsed = 0
    private var recorder: Recorder?
    private let buffer = ChunkBuffer()

    private var interruptObs: NSObjectProtocol?
    /// Set from the tap until the recorder exists: a second tap during the permission prompt or the
    /// model download started a second recorder and a second job.
    private var startingRecord = false
    func toggleRecord() {
        // A call, Siri or another app taking the mic: save what was recorded (queued like any stop) and say so, instead of a stalled tap.
        if interruptObs == nil { interruptObs = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            guard (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init) == .began else { return }
            Task { @MainActor in guard let self, self.recording else { return }; self.toggleRecord(); self.status = L("rec_interrupted") }
        } }
        if recording { recorder?.stop(); recorder = nil; liveBuffer?.close(); liveBuffer = nil; recording = false; status = L("stopping"); return }
        guard !startingRecord else { return }
        startingRecord = true
        Task {
            defer { startingRecord = false }   // early returns; cleared as soon as the recorder exists
            guard await Recorder.requestPermission() else { status = L("mic_permission_required"); return }
            guard await downloadSpeech() else { return }
            status = L("loading_models"); lines = []; notes = []; title = ""; summary = ""
            elapsed = 0
            let r = Recorder(); recorder = r; recording = true; startingRecord = false; UIApplication.shared.isIdleTimerDisabled = true
            let job = Job(audio: UUID().uuidString + ".wav")
            guard let wav = try? WavWriter(JobQueue.url(job)) else { recording = false; return }
            recordingJob = job.id
            await queue.add(job)       // on the list before the first sample: a kill mid-recording keeps the audio
            // Android: the reader takes notes while the meeting is recorded, so the summary is ready moments after Stop. One
            // engine pair at a time: with the queue at work (or the previous talk still being read), record only; it is processed after.
            let readerReady = Dev.env["VOX_READER_DIR"] != nil ? true : await store.isComplete(Prefs.reader)
            if liveJob == nil && activeJob == nil && readerReady && Dev.env["VOX_LEGACYREC"] == nil { await recordLive(job, wav, r); return }
            let b = base
            Task.detached { [weak self] in
                guard let self, let eng = NemoEngine(xasr: self.modelPath("xasr_host.bin", b), diar: self.modelPath("diar_host.bin", b), xasrTflite: self.modelPath("xasr_stream_q8_octav.tflite", b), diarTflite: self.modelPath("sortformer_int8_f190.tflite", b), threads: Prefs.effectiveThreads, settle: Double(Prefs.speakerDelay)) else {
                    await MainActor.run { self?.status = L("models_missing"); self?.recording = false; UIApplication.shared.isIdleTimerDisabled = false }; return }
                let agc = LiveAgc()
                do { try r.start { [buffer = self.buffer] c in var c = c; agc.process(&c); wav.append(c); buffer.add(c) } }
                catch { await MainActor.run { self.status = L("mic_error", "\(error)"); self.recording = false }; return }
                var frozen: [Utterance] = []; let conv = TextConv()
                while await MainActor.run(body: { self.recording }) {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    let chunk = self.buffer.take()
                    if !chunk.isEmpty { _ = eng.push(chunk) }
                    let (fresh, tail) = eng.live(); frozen += fresh   // the engine returns only the newly frozen lines
                    await MainActor.run { [frozen] in self.lines = conv.utterances(frozen + tail); self.elapsed = Int(eng.fedSeconds); self.status = L("recording_s", Int(eng.fedSeconds)) }
                }
                wav.finish()
                await MainActor.run { self.recordingJob = nil }
                await MainActor.run { self.drain() }   // full pipeline (diarization settled + notes) from the saved audio
            }
        }
    }
    private var liveBuffer: ChunkBuffer?
    private var liveJob: UUID?
    /// The recording fed straight into `process` (ASR + diarization + reader live), archived at the end like a queued job.
    private func recordLive(_ job: Job, _ wav: WavWriter, _ r: Recorder) async {
        let buf = ChunkBuffer(), agc = LiveAgc()
        do { try r.start { c in var c = c; agc.process(&c); wav.append(c); buf.add(c) } }
        catch { status = L("mic_error", "\(error)"); recording = false; recordingJob = nil; return }
        liveBuffer = buf; liveJob = job.id; activeJob = job.id
        let t0 = Date()   // the timer runs on the clock, not on what the recognizer has caught up with (models still loading)
        Task { while self.recording, self.liveJob == job.id { self.elapsed = Int(Date().timeIntervalSince(t0)); try? await Task.sleep(nanoseconds: 500_000_000) } }
        await process(seconds: 0, job: job, live: true) { [weak self] sink in
            while true {
                // Half-second blocks, as the record-only loop: the live diarizer settles speakers on what it is pushed.
                Thread.sleep(forTimeInterval: 0.5)
                let done = buf.closed, c = buf.take()
                if !c.isEmpty, !sink(c) { break }
                if done { break }
            }
            wav.finish()
            Task { @MainActor in self?.recordingJob = nil }   // the audio is complete: Next talk may start the next one
        }
        // Models failed to load while this talk is still being recorded: stop the mic, the audio stays queued. After
        // Next talk the mic belongs to the next recording (recordingJob changed), which must keep going.
        if recording, recordingJob == job.id { toggleRecord() }
        if StopFlag.on { StopFlag.on = false; parked.insert(job.id); status = L("status_stopped") }
        else if await queue.contains(job.id) { parked.insert(job.id); failed[job.id] = status }
        // After Next talk, recordingJob is the next recording: clearing it would let the queue take that talk while
        // it is still being recorded (processed from its first seconds, the rest of the audio lost).
        if recordingJob == job.id { recordingJob = nil }
        liveJob = nil; activeJob = nil; await syncQueue()
        drain()   // whatever was queued meanwhile
    }
    /// Android "Next talk": save this recording (it is queued like any stop) and start the next one straight away.
    func nextTalk() {
        guard recording else { return }
        toggleRecord()
        Task { while recordingJob != nil || recording { try? await Task.sleep(nanoseconds: 100_000_000) }; toggleRecord() }
    }
    /// Dev override (VOX_BASE, simulator only) else the downloaded copy in Application Support.
    nonisolated func modelPath(_ n: String, _ b: String) -> String {
        if Dev.env["VOX_BASE"] != nil { return "\(b)/models/\(n)" }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("models/nemo/\(n)").path
    }
    func downloadSpeech() async -> Bool {
        if Dev.env["VOX_BASE"] != nil { return true }
        if await store.isComplete(.speech) { return true }
        do {
            try await store.download(.speech) { d, t in Task { @MainActor in self.status = L("download_speech", Int(d / 1_000_000), Int(t / 1_000_000)) } }
            return true
        } catch { status = L("download_failed", "\(error)"); return false }
    }
    /// Search model (semantic search + Ask): percent while it downloads, nil otherwise.
    @Published var embedProgress: Int?
    /// A citation or a search passage opens this meeting at this time (consumed by the next SessionView).
    var openAt: (UUID, Double)?
    func downloadEmbed() {
        guard embedProgress == nil else { return }
        embedProgress = 0
        Task.detached { [store] in
            do {
                try await store.download(.embed) { d, t in Task { @MainActor in self.embedProgress = Int(d * 100 / max(1, t)) } }
                await MainActor.run { StatusLog.add("search model downloaded"); self.embedProgress = nil; LibraryIndexer.shared.catchUp(self.sessions) }
            } catch { await MainActor.run { StatusLog.add("search model download failed: \(error)"); self.embedProgress = nil; self.status = L("search_model_download_failed") + " (\(error))" } }
        }
    }
    func downloadReader() {
        status = L("download_reader")
        Task.detached { [store] in
            do {
                try await store.download(Prefs.reader) { d, t in Task { @MainActor in self.status = L("download_reader_p", Int(d / 1_000_000), Int(t / 1_000_000)) } }
                await MainActor.run { self.status = L("reader_ready") }
            } catch { await MainActor.run { self.status = L("download_failed", "\(error)") } }
        }
    }

    /// Transcribe + read a source of 16 kHz mono chunks (a file, the bundled sample). `feed` pushes chunks until done or false.
    func process(seconds: Double, job: Job? = nil, skipper: SilenceSkipper? = nil, resume: Checkpoint.Partial? = nil, offset: Double = 0, enroll: SeamEnrollment? = nil, live: Bool = false, feed: @escaping @Sendable (([Float]) -> Bool) throws -> Void) async {
        status = L("loading_models"); lines = []; notes = []; title = ""; summary = ""; StopFlag.on = false
        let b = base
        await Task.detached {
            let cached = resume != nil ? nil : job.flatMap { Checkpoint.load($0.id) }   // transcript done by an earlier attempt: only the reader is left
            let speechReady = cached != nil ? true : await self.downloadSpeech()
            guard speechReady else { await MainActor.run { self.status = L("load_failed") }; return }
            let eng = cached != nil ? nil : NemoEngine(xasr: self.modelPath("xasr_host.bin", b), diar: self.modelPath("diar_host.bin", b), xasrTflite: self.modelPath("xasr_stream_q8_octav.tflite", b), diarTflite: self.modelPath("sortformer_int8_f190.tflite", b), threads: Prefs.effectiveThreads, settle: Double(Prefs.speakerDelay))
            if cached == nil && eng == nil { await MainActor.run { self.status = L("load_failed") }; return }
            let rm = Prefs.reader
            if Dev.env["VOX_READER_DIR"] == nil, !(await self.store.isComplete(rm)) {   // like Android: the notes model downloads on first use
                do { try await self.store.download(rm) { d, t in Task { @MainActor in self.status = L("download_reader_p", Int(d / 1_000_000), Int(t / 1_000_000)) } } }
                catch { await MainActor.run { self.status = L("download_failed", "\(error)") } }
            }
            let stored = await self.store.isComplete(rm) ? await self.store.dir(rm).path : nil
            let r = ReaderFactory.make(dir: Dev.env["VOX_READER_DIR"].map { $0.hasPrefix("/") ? $0 : NSHomeDirectory() + "/" + $0 } ?? stored)
            let agentUi = self.agent
            let reader = MeetingReader(llm: r.llm, systemPrompt: r.systemPrompt, events: { e in Task { @MainActor in agentUi.apply(e) } }, budget: Prefs.reader.budget, card: Prefs.reader.card)
            do { try reader.start() } catch { Prefs.reportReadFailure(); await MainActor.run { self.status = L("reader_error", "\(error)") }; return }
            let conv = TextConv(); let lowRam = ProcessInfo.processInfo.physicalMemory < 4_500_000_000   // 3 GB phones cannot hold ASR + diarizer + E2B at once
            let worker = ReaderWorker(reader, paused: lowRam && cached == nil)
            defer { worker.stop() }
            let sk = skipper ?? SilenceSkipper()   // nothing skipped → identity
            // Resume: the saved prefix goes to the reader first; fresh lines are mapped to original-audio time (offset + skipped
            // silence) and stitched onto it, so the reader sees original times and nothing is restored twice.
            let prior = resume?.lines ?? [], seam = resume?.seam ?? 0
            let en = enroll ?? .none(offset)
            func full(_ fresh: [Utterance], stable: Int) -> ([Utterance], Int) {
                SeamStitcher.stitch(prior, seam: seam, sk.restore(fresh).map { var u = $0; (u.start, u.end) = en.toOriginal(u.start, u.end); return u }, stable: stable)
            }
            var kept: [Utterance] = prior, snapshot: [Utterance] = prior, offered = 0, frozen: [Utterance] = [], savedAt = 0.0
            var fedUntil = prior.last?.end ?? -1   // end of the last line handed to the reader: finish() feeds what follows
            if resume != nil { for u in prior { if let l = ReaderSummarizer.toLine(u) { worker.offer(l) } }; offered = prior.count; StatusLog.add("trace resume at \(Int(seam)) s, \(prior.count) utterances kept") }
            do {
                if cached == nil, let eng { try feed { chunk in
                    if StopFlag.on { return false }
                    _ = Stage.watchdog; Stage.set("push")
                    if !eng.push(chunk) { StatusLog.add("trace push failed at \(Int(eng.fedSeconds)) s") }
                    let (fresh, tail) = eng.live(); frozen += fresh   // the engine returns only the newly frozen lines
                    let (all, stable) = full(frozen + tail, stable: frozen.count); kept = Array(all.prefix(stable))
                    let edge = all.last?.end ?? 0; snapshot = all.filter { $0.end <= edge - 10 }   // resume point: everything clear of the live edge
                    for u in kept.dropFirst(offered) { if let l = ReaderSummarizer.toLine(u) { worker.offer(l) }; fedUntil = max(fedUntil, u.end) }   // original-audio times: notes need no restore
                    offered = max(offered, kept.count)
                    let j = worker.journal, fed = eng.fedSeconds
                    // Every minute, the same resume point a Stop leaves: a kill (swipe-away, jetsam) then resumes after it
                    // instead of transcribing the whole file again.
                    if let job, fed - savedAt >= 60, snapshot.count > prior.count, let last = snapshot.last {
                        Checkpoint.savePartial(.init(lines: snapshot, seam: last.end), job.id); savedAt = fed
                    }
                    Task { @MainActor in
                        self.lines = conv.utterances(all); self.notes = conv.notes(j)
                        if live { if self.recording { self.status = L("recording_s", Int(fed)) }; return }
                        self.status = L("transcribing_s", Int(max(0, fed - en.length) + offset), Int(seconds))
                    }
                    Stage.set("idle")
                    return true
                } }
            } catch { if !StopFlag.on { await MainActor.run { self.status = L("audio_error", error.localizedDescription) } }; return }
            // Stopped before the end: keep the frozen prefix (the tail is re-attributed) so Resume continues from there (Android saveProgress).
            if StopFlag.on {
                // A file run has no live lines (diarization settles at finish): close the engine on what was fed, and drop the
                // last line, cut mid-sentence by the stop.
                if snapshot.count <= prior.count, let eng, let f = eng.finish(), !f.isEmpty { snapshot = Array(full(f, stable: f.count).0.dropLast()) }
                StatusLog.add("trace stop: \(snapshot.count) utterances kept for resume"); if let job, cached == nil, let last = snapshot.last { Checkpoint.savePartial(.init(lines: snapshot, seam: last.end), job.id) }; return }
            Stage.set("finish")
            let final: [Utterance]
            if let cached {
                final = cached; StatusLog.add("trace transcript restored from checkpoint, \(cached.count) utterances")
                for u in cached { if let l = ReaderSummarizer.toLine(u) { worker.offer(l) } }
            } else {
                let fresh = eng?.finish() ?? []
                final = full(fresh, stable: fresh.count).0
                // The live view only ever fed frozen lines: the last segment and the settle window are in the
                // final transcript only, and the meeting's closing decisions with them (Android: ReaderLane.finish).
                var tail = 0
                for u in final where u.start >= fedUntil - 0.01 { if let l = ReaderSummarizer.toLine(u) { worker.offer(l); tail += 1 } }
                StatusLog.add("trace finish: \(tail) closing lines to the reader")
                if let job { Checkpoint.save(final, job.id) }
                if lowRam { eng?.close(); StatusLog.add("trace models freed, reader starts (\(os_proc_available_memory() / 1_048_576) MB free)"); worker.resume() }
            }
            worker.drain { d, t in Task { @MainActor in self.status = L("reading_s", d, t) } }
            var readError = worker.failure
            if readError == nil { do { _ = try reader.finish() } catch { readError = error; StatusLog.add("trace reader.finish error: \(error)") } }
            // A failed reading is not an empty meeting: no title or summary from a half-read journal, and the
            // cause on the status line (Android readFailed). The transcript is still archived.
            let journal = readError == nil ? reader.journal : []
            let sum = ReaderSummarizer(llm: r.llm, card: Prefs.reader.card)
            // No notes at all (a short or off-topic recording): one plain line, not five empty sections (Android summary_no_notes).
            let t = journal.isEmpty ? nil : sum.title(journal), prose = journal.isEmpty ? nil : sum.prose(journal) ?? Prefs.reader.card.reader.minutes(journal)
            let (fl, fn, ft, fp) = (conv.utterances(final), conv.notes(journal), conv.text(t ?? ""), prose.map { conv.text($0) } ?? "")
            let summaryText = fp.isEmpty && !fl.isEmpty && readError == nil ? L("summary_no_notes") : fp
            let why = readError.map { String(String(describing: $0).split(separator: "\n").first ?? "").prefix(120) }
            await MainActor.run { self.lines = fl; self.notes = fn; self.title = ft; self.summary = summaryText; let ns = Set(fl.map(\.speaker)).count; self.status = why.map { L("summary_failed") + " [\($0)]" } ?? (ns > 1 ? L("status_transcript_lines_speakers", fl.count, ns) : L("status_transcript_lines", fl.count)); self.agent.apply(.state(.done, notes: fn.count)) }
            await self.archive(lines: fl, notes: fn, title: ft, summary: summaryText, seconds: live ? (eng?.fedSeconds ?? 0) : seconds, job: job)
            if let job { Checkpoint.remove(job.id); await self.queue.remove(job.id) }
            Stage.set("idle")
        }.value
    }

    func run() {
        guard let pcm = loadWav(Bundle.main.path(forResource: "sample", ofType: "wav") ?? "\(base)/clips/diar_ref_2spk_123s.wav") else { status = L("sample_missing"); return }
        Task { await process(seconds: Double(pcm.count) / 16000) { sink in
            for s in stride(from: 0, to: pcm.count, by: 16000) { if !sink(Array(pcm[s..<min(pcm.count, s + 16000)])) { return } }
        } }
    }

    // MARK: processing queue
    let queue = JobQueue()
    private var draining = false
    /// Sessions being re-summarized, as they were: names carried over, and Android's "New summary ready · Undo".
    private var resummarized: [UUID: Session] = [:]
    var drainTask: Task<Void, Never>?
    var recordingJobId: UUID? { recordingJob }
    private var recordingJob: UUID?   // being recorded: not for the queue yet
    /// Processes every queued job in order (also at launch: recordings cut short by a kill are picked up here).
    /// Android re-transcribe / re-summarize: re-queues the session's saved audio under the same id (summarize reuses the stored transcript, so only the reader runs).
    func rerun(_ s: Session, transcribe: Bool) {
        guard let a = s.audio else { StatusLog.add("trace rerun: no audio"); return }
        let j = Job(id: s.id, date: s.date, audio: a, title: s.title)
        guard FileManager.default.fileExists(atPath: JobQueue.url(j).path) else { StatusLog.add("trace rerun: audio file missing"); return }
        Task { guard !(await queue.contains(s.id)) else { return }   // already queued (double tap): one row, one run
        if transcribe { Checkpoint.remove(s.id); resummarized[s.id] = nil } else { Checkpoint.save(s.lines, s.id); resummarized[s.id] = s }
        status = L("status_starting")   // Android: shown until the first model line
        await queue.add(j); await syncQueue(); drain() }
    }
    func drain() {
        guard !draining, liveJob == nil else { return }
        draining = true
        UIApplication.shared.isIdleTimerDisabled = true   // iOS suspends a locked app: stay awake while the queue works (Android: wake lock)
        if Dev.env["VOX_SHOTS"] == nil { Notifier.requestPermission() }   // screenshots: no permission prompt over the UI
        drainTask = Task {
            while let job = await queue.first(excluding: parked.union(recordingJob.map { [$0] } ?? [])) {
                let url = JobQueue.url(job)
                WavWriter.repair(url)
                guard let secs = AudioDecode.duration(url), secs > 0 else { parked.insert(job.id); failed[job.id] = L("import_failed"); status = L("import_failed"); await syncQueue(); continue }   // undecodable: shown with its error and Remove, not dropped silently
                let left = await queue.count; pending = left; activeJob = job.id; await syncQueue()
                if left > 1 { status = L("queue_n", left) }
                let skipper = SilenceSkipper()
                let partial = Checkpoint.loadPartial(job.id)   // left by a Stop, or saved every minute (a kill): continue after its seam
                let from = partial.map { max(0, $0.seam - SeamStitcher.preroll) } ?? 0
                let enroll = partial.map { SeamEnrollment.plan($0.lines, offset: from) } ?? .none(from)
                if !enroll.clips.isEmpty { StatusLog.add("trace resume enrollment: \(enroll.clips.count) clips, \(Int(enroll.length)) s") }
                await process(seconds: secs, job: job, skipper: skipper, resume: partial, offset: from, enroll: enroll) { sink in try AudioPrep.stream(url, skipper: skipper, from: from, clips: enroll.clips, onChunk: sink) }
                if StopFlag.on { StopFlag.on = false; parked.insert(job.id); status = L("status_stopped") }
                else if await queue.contains(job.id) { parked.insert(job.id); failed[job.id] = status; Notifier.failed(job.title ?? L("meeting")) }   // failed run (still queued: a saved one leaves the queue): parked with its error and a Retry (Android), never looped on — whatever sits ahead of it (a parked job, a promoted one)
                activeJob = nil; await syncQueue()
            }
            draining = false
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    /// Podcast episode: download into the audio directory, then queue it like any import.
    @Published var downloading: String?
    /// Import download banner (Android ImportDownloadBanner): fraction done, nil while unknown.
    @Published var downloadFraction: Double?
    private var downloadTask: Task<Void, Never>?
    /// Cancel keeps the `.part`, so the same episode resumes where it stopped.
    func cancelDownload() { downloadTask?.cancel() }
    func addEpisode(_ ep: Episode) {
        guard downloading == nil else { status = L("import_busy"); return }   // the sheet is already gone: say why nothing starts (as addEpisode does)
        let j = Job(audio: UUID().uuidString + "." + Podcast.ext(ep.audioUrl), title: ep.title)
        downloading = ep.title; downloadFraction = nil; status = L("podcast_downloading", 0)
        downloadTask = Task {
            defer { downloading = nil; downloadFraction = nil; downloadTask = nil }
            do {
                _ = try await Podcast.download(ep, name: j.audio) { f in Task { @MainActor in self.downloadFraction = f; self.status = L("podcast_downloading", Int(f * 100)) } }
                await queue.add(j); drain()
            } catch { status = Task.isCancelled ? L("app_ready") : L("download_failed", error.localizedDescription) }
        }
    }

    func importAudio(_ url: URL) {
        let j = Job(audio: UUID().uuidString + "." + (url.pathExtension.isEmpty ? "m4a" : url.pathExtension))
        let before = status; status = L("status_importing")   // Android: a long share (big file, cloud provider) shows progress
        Task {
            let ok = await Task.detached {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return (try? FileManager.default.copyItem(at: url, to: JobQueue.url(j))) != nil
            }.value
            if status == L("status_importing") { status = before }
            guard ok else { status = L("import_failed"); return }
            imported(url, j)
        }
    }
    private func imported(_ url: URL, _ j: Job) {
        if let man = SessionFile.read(JobQueue.url(j)) {   // a Prime Minutes session: restore it instead of re-transcribing
            var s = SessionFile.session(man, audio: j.audio)
            if s.title.isEmpty { s.title = url.deletingPathExtension().lastPathComponent }
            if let t = AVURLAsset(url: JobQueue.url(j)).duration.seconds as Double?, t.isFinite, t > s.seconds { s.seconds = t }
            update(s); return
        }
        Task { await queue.add(j); drain() }
    }
}

struct ContentView: View {
    @StateObject var m = Model()
    @State private var picking = false
    @State private var showSettings = false
    @State private var showPodcast = false
    @State private var showAdd = false
    @State private var path: [Session] = []
    @AppStorage("language") private var language = "system"
    @AppStorage("theme") private var theme = "auto"
    var body: some View {
        NavigationStack(path: $path) {
            LibraryView(m: m, path: $path, onAdd: { showAdd = true }, onSettings: { showSettings = true })
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Session.self) { x in SessionView(session: m.sessions.first { $0.id == x.id } ?? x, save: { m.update($0) }, rerun: { m.rerun($0, transcribe: $1) }, openAt: m.openAt?.0 == x.id ? m.openAt?.1 : nil) }
            .onAppear { let first = !Dev.ranOnce; Dev.ranOnce = true; if first, Dev.env["VOX_WIPE"] != nil { for d in ["sessions", "audio", "checkpoints", "jobs.json"] { try? FileManager.default.removeItem(at: JobQueue.support.appendingPathComponent(d)) }; for d in ["sessions", "audio", "checkpoints"] { try? FileManager.default.createDirectory(at: JobQueue.support.appendingPathComponent(d), withIntermediateDirectories: true) } }; BackgroundWork.model = m; m.reload(); if Dev.env["VOX_MFABENCH"] != nil { Task { let rm: ReaderModel = Dev.env["VOX_MFABENCH"] == "E4B" ? .e4b : Dev.env["VOX_MFABENCH"] == "E2B" ? .e2b : Prefs.reader; let d = await m.store.isComplete(rm) ? await m.store.dir(rm).path : nil; let r = await Task.detached { MfaBench.run(dir: d) }.value; try? r.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/mfabench-\(Dev.env["VOX_MFABENCH"] ?? "")-\(Dev.env["VOX_MFA_BACKEND"] ?? "0")-\(Dev.env["VOX_TAG"] ?? "").txt"), atomically: true, encoding: .utf8) } }; if let b = Dev.env["VOX_SET_BACKEND"] { BackendBench.chosen = b; try? "chosen=\(BackendBench.chosen) gpuGraph=\(BackendBench.gpuGraph(Prefs.reader) ?? "nil")".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/setbackend-\(Dev.env["VOX_TAG"] ?? "").txt"), atomically: true, encoding: .utf8) }; if Dev.env["VOX_BACKENDTEST"] != nil { Task.detached { let rm = ReaderModel.e2b, st = ModelStore(); let dir = await st.dir(rm).path; let g = dir + "/prefill_decode_fused.gpu.tflite"; if !FileManager.default.fileExists(atPath: g) { try? FileManager.default.copyItem(atPath: NSHomeDirectory() + "/Documents/mfa-gpu.tflite", toPath: g) }; let gp = await st.gpuGraph(rm); let t0 = Date(); let r = BackendBench.run(rm, dir: dir, gpu: gp, threads: Prefs.effectiveThreads) { _ in }; BackendBench.chosen = "GPU"; let used = BackendBench.gpuGraph(rm); let t1 = Date(); let f = ReaderFactory.make(dir: dir); let out = "gpu=\(gp ?? "nil") test \(Int(t1.timeIntervalSince(t0)))s " + r.map { "\($0.key) ok=\($0.value.passed) p=\(Int($0.value.prefill)) d=\(String(format: "%.1f", $0.value.decode)) note=\($0.value.note)" }.joined(separator: "; ") + " chosen=\(BackendBench.chosen) used=\(used != nil) real=\(f.real) load \(Int(Date().timeIntervalSince(t1)))s\n" + ((try? String(contentsOf: StatusLog.url, encoding: .utf8)) ?? "").split(separator: "\n").suffix(4).joined(separator: "\n"); try? out.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/backendtest-\(Dev.env["VOX_TAG"] ?? "").txt"), atomically: true, encoding: .utf8) } }; if Dev.env["VOX_BENCH"] != nil { Task { let t0 = Date(); let n = await Prefs.runBench(); try? "bench pick=\(n ?? -1) eff=\(Prefs.effectiveThreads) cores=\(Prefs.cores) \(Int(Date().timeIntervalSince(t0) * 1000))ms".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/bench.txt"), atomically: true, encoding: .utf8) } }; if let th = Dev.env["VOX_THEME"] { theme = th }; if Dev.env["VOX_CANCEL"] != nil { Task { try? await Task.sleep(nanoseconds: 4_000_000_000); let was = m.downloading != nil; m.cancelDownload(); try? await Task.sleep(nanoseconds: 2_000_000_000); try? "cancel was=\(was) now=\(m.downloading != nil) queued=\(m.waiting.count) status=\(m.status)".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/cancel.txt"), atomically: true, encoding: .utf8) } }; if Dev.env["VOX_ACTIONS"] != nil { UserDefaults.standard.set(true, forKey: "showActions") }; if Dev.env["VOX_OPEN"] != nil { Task { let all = await m.library.all(); let p = Dev.env["VOX_OPEN"] == "short" ? all.filter { $0.audio != nil && $0.seconds > 30 }.min { $0.seconds < $1.seconds }.map { [$0] } ?? [] : Array(all.prefix(1)); if let t = Double(Dev.env["VOX_OPEN_AT"] ?? ""), let s = p.first { m.openAt = (s.id, t) }; path = p } }; if let d = Dev.env["VOX_DOWNLOAD"] { if d == "speech" { Task { _ = await m.downloadSpeech() } } else { m.downloadReader() } }; if Dev.env["VOX_AUTORUN"] != nil { m.run() }; if first, let r = Dev.env["VOX_RECORD"] { Task { try? await Task.sleep(nanoseconds: UInt64((Double(r) ?? 0) * 1e9)); m.toggleRecord() } }; if first, let fr = Dev.env["VOX_FRAMES"] { Frames.start(Double(fr) ?? 0.5) }; if first, Dev.env["VOX_TOUR"] != nil { Task {
                // Once both meetings are fully processed (idle for 10 s), open each session in turn, then stop the frames.
                var idle = 0
                while idle < 10 { try? await Task.sleep(nanoseconds: 1_000_000_000); let busy = m.recording || m.activeJob != nil || !m.waiting.isEmpty || m.pending > 0 || m.sessions.count < 2 || m.sessions.contains { $0.summary.isEmpty || $0.seconds < 60 }; idle = busy ? 0 : idle + 1 }
                let all = await m.library.all()
                for s in all.prefix(2) { path = [s]; try? await Task.sleep(nanoseconds: 8_000_000_000); path = []; try? await Task.sleep(nanoseconds: 3_000_000_000) }
                FileManager.default.createFile(atPath: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("frames/STOP").path, contents: nil)
            } }; if Dev.env["VOX_SETTINGS"] != nil { showSettings = true }; if Dev.env["VOX_EMBED_DL"] != nil, !EmbedHost.ready { m.downloadEmbed() }; if let sn = Dev.env["VOX_SNAP"] { Snap.schedule(sn) }; if Dev.env["VOX_EMBEDDIAG"] != nil { Task.detached { var o = ""; if let d = try? Data(contentsOf: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/embed_ref.json")), let refs = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] { for r in refs { let text = r["text"] as? String ?? "", ref = (r["vec"] as? [Double] ?? []).map(Float.init), nt = r["tokens"] as? Int ?? 0; let t0 = Date(); let v = EmbedHost.use(threads: 2) { $0.embed(text) } ?? nil; let ms = Int(Date().timeIntervalSince(t0) * 1000); let tk = EmbedHost.use(threads: 2) { $0.tokens(text) } ?? -1; if let v = v, v.count == ref.count { var dd: Float = 0, na: Float = 0, nb: Float = 0; for i in v.indices { dd += v[i] * ref[i]; na += v[i] * v[i]; nb += ref[i] * ref[i] }; o += String(format: "cos %.4f |v| %.3f tokens %d/%d %d ms  %@\n", dd / (na.squareRoot() * nb.squareRoot()), na.squareRoot(), tk + 2, nt, ms, String(text.prefix(40))) } else { o += "embed nil for \(text.prefix(30))\n" } } } else { o += "no embed_ref.json\n" }; let ss = await MainActor.run { m.sessions }; for q in ["gold", "opening hours", "颱風季節的浪", "抓交替"] { if let qv = EmbedHost.use(threads: 2, { $0.embed(EmbedPrompts.query(q)) }) ?? nil { o += "Q \(q):"; for h in LibrarySearch.dense(Array(ss.prefix(1)), qv: qv, maxPassages: 6) { for p in h.passages { o += String(format: " [%@ %.3f @%.0f]", "\(p.kind)", p.score, p.timeSec) } }; o += "\n" } }; try? o.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/embeddiag.txt"), atomically: true, encoding: .utf8) } }; if let st = Dev.env["VOX_STOP"] { Task { try? await Task.sleep(nanoseconds: UInt64(Int(st) ?? 30) * 1_000_000_000); let before = m.activeJob != nil; m.stopProcessing(); try? await Task.sleep(nanoseconds: 20_000_000_000); try? "active before=\(before) parked=\(m.parked.count) active now=\(m.activeJob != nil) status=\(m.status)".write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/stop.txt"), atomically: true, encoding: .utf8) } }; if Dev.env["VOX_STATUSLOG"] != nil { Task { var lastN = -1; var peakMB = 0.0; while true { NSLog("VOXSTATUS %@", m.status); do { var i = task_vm_info_data_t(); var c = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size); _ = withUnsafeMutablePointer(to: &i) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(c)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &c) } }; peakMB = max(peakMB, Double(i.phys_footprint) / 1048576); NSLog("VOXRSS %.0f peak %.0f MB kpeak %.0f resident %.0f rpeak %.0f MB", Double(i.phys_footprint) / 1048576, peakMB, Double(i.ledger_phys_footprint_peak) / 1048576, Double(i.resident_size) / 1048576, Double(i.resident_size_peak) / 1048576) }; if m.lines.count != lastN { lastN = m.lines.count; for l in m.lines { NSLog("VOXLINE %d %.1f-%.1f %@", l.speaker, l.start, l.end, l.text) } }; try? m.status.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/status.txt"), atomically: true, encoding: .utf8); try? await Task.sleep(nanoseconds: 2_000_000_000) } } }; if Dev.env["VOX_ROUNDTRIP"] != nil { Task { if let s = await m.library.all().first(where: { $0.audio != nil }), let u = await SessionFile.export(s) { let r = SessionFile.read(u); m.status = "rt \((try? FileManager.default.attributesOfItem(atPath: u.path)[.size]) ?? 0)B lines \(r?.utterances?.count ?? -1)/\(s.lines.count) title \(r?.title == s.title)"; m.importAudio(u) } else { m.status = "rt: export failed" }; try? m.status.write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/rt.txt"), atomically: true, encoding: .utf8) } }; if Dev.env["VOX_ADD"] != nil { showAdd = true }; if Dev.env["VOX_DIALOG"] == "podcast" { showPodcast = true }; m.drain(); if let fs = Dev.env["VOX_IMPORT"] { for f in fs.split(separator: ",").map(String.init) { m.importAudio(URL(fileURLWithPath: f.hasPrefix("/") ? f : NSHomeDirectory() + "/" + f)) } }; if let q = Dev.env["VOX_PODCAST"] { Task { if let sr = try? await Podcast.search(q).first, let ep = try? await Podcast.episodes(sr.feedUrl, limit: 3).last { m.addEpisode(ep) } else { m.status = "podcast: no result" } } } }
            .sheet(isPresented: $showAdd) { AddSourceSheet(onFile: { picking = true }, onPodcast: { showPodcast = true }, onSession: { picking = true }) }
            .fullScreenCover(isPresented: Binding(get: { m.recording }, set: { _ in })) { CaptureView(m: m) }
            .sheet(isPresented: $showPodcast) { PodcastView { m.addEpisode($0) } }
            .sheet(isPresented: $showSettings) { SettingsView(m: m, language: $language, theme: $theme) }
            .onOpenURL { m.importAudio($0) }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.audio, .mpeg4Audio]) { if case .success(let u) = $0 { m.importAudio(u) } }
        }
        .preferredColorScheme((Theme(rawValue: theme) ?? .auto).scheme)
        .modifier(ThemeStyle(theme: Theme(rawValue: theme) ?? .auto))
    }
}

struct SettingsView: View {
    @ObservedObject var m: Model
    private var inferenceFooter: String {
        let capped: String = Prefs.capped ? "\n" + L("settings_inference_capped") : ""
        return L("threads_note", Prefs.effectiveThreads, Prefs.cores) + capped + "\n" + L("settings_inference_hint")
    }
    @Binding var language: String
    @Binding var theme: String
    @State private var models = Storage.models()
    @State private var toDelete: Storage.Item?
    @State private var threads = Prefs.threads
    @State private var benching = false
    @State private var benchTick = 0
    @AppStorage("hwMonitor") private var hwMonitor = true
    @State private var reader = Prefs.readerId
    @State private var delay = Prefs.speakerDelay
    @AppStorage("textSize") private var textSize = 0
    @AppStorage("showActions") private var showActions = false
    @AppStorage("askEnabled") private var askEnabled = false
    @ObservedObject private var indexer = LibraryIndexer.shared
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(L("language"), selection: $language) { ForEach(AppLanguage.allCases) { Text($0.autonym).tag($0.rawValue) } }
                } header: { Text(L("settings_language")) } footer: { Text(L("settings_language_hint")) }
                Section { Picker(L("theme"), selection: $theme) { ForEach(Theme.allCases) { Text($0.label).tag($0.rawValue) } }.pickerStyle(.segmented) }
                    header: { Text(L("settings_appearance")) } footer: { Text(L("theme_eink_hint")) }
                Section {
                    Picker(L("text_size"), selection: $textSize) { ForEach(0..<Prefs.textSizeLabels.count, id: \.self) { Text(L(Prefs.textSizeLabels[$0])).tag($0) } }
                } header: { Text(L("text_size")) } footer: { Text(L("settings_font_size_hint")) }
                Section {
                    Stepper(value: $delay, in: 5...30, step: 5) { HStack { Text(L("settings_speaker_delay")); Spacer(); Text(L("settings_seconds", delay)).foregroundStyle(.secondary) } }
                        .onChange(of: delay) { Prefs.speakerDelay = delay }
                } header: { Text(L("settings_recording")) } footer: { Text(L("settings_speaker_delay_hint")) }
                Section {
                    Picker(L("notes_model"), selection: $reader) {
                        Text(L("reader_model_e2b") + " · 2.2 GB").tag("E2B")
                        if Prefs.e4bAllowed { Text(L("reader_model_e4b") + " · 3.3 GB").tag("E4B") }
                    }.pickerStyle(.inline).labelsHidden()
                    .onChange(of: reader) { Prefs.readerId = reader }
                } header: { Text(L("settings_reader_model")) } footer: {
                    Text(L("reader_model_hint", 2200, 3300) + (Prefs.e4bAllowed ? "" : "\n" + L("reader_model_e4b_ram")))
                }
                Section { Toggle(L("settings_hw_monitor"), isOn: $hwMonitor) } footer: { Text(L("settings_hw_monitor_hint")) }
                Section {
                    Toggle(L("threads_auto"), isOn: Binding(get: { threads == 0 }, set: { threads = $0 ? 0 : Prefs.effectiveThreads; Prefs.threads = threads }))
                    if threads > 0 {
                        Stepper(L("threads_n", threads), value: Binding(get: { threads }, set: { threads = $0; Prefs.threads = $0 }), in: 2...max(2, Prefs.cores))
                    }
                    Button(L(benching ? "settings_inference_running" : "settings_inference_run")) {
                        benching = true; threads = 0; Prefs.threads = 0
                        Task { _ = await Prefs.runBench(); benchTick += 1; benching = false }
                    }.disabled(benching)
                } header: { Text(L("settings_inference")) } footer: { Text(inferenceFooter).id(benchTick) }
                Section {
                    if !EmbedHost.ready {
                        if let p = m.embedProgress { Text(L("search_semantic_downloading", p)).foregroundStyle(.secondary) }
                        else { Button(L("settings_search_model_get", ByteCountFormatter.string(fromByteCount: ReaderModel.embed.files.reduce(0) { $0 + $1.size }, countStyle: .file))) { m.downloadEmbed() } }
                    } else {
                        let stale = LibraryIndexer.stale(m.sessions).count
                        if indexer.pending > 0 || !indexer.indexing.isEmpty { Text(L("settings_index_pending", indexer.pending + indexer.indexing.count)).foregroundStyle(.secondary) }
                        else if stale == 0 { Text(L("settings_index_all_done")).foregroundStyle(.secondary) }
                        else { Button(L("settings_index_old") + " (\(stale))") { indexer.catchUp(m.sessions) } }
                    }
                } header: { Text(L("model_kind_embed")) } footer: { Text(L("settings_search_hint")) }
                Section {
                    Toggle(L("settings_show_actions"), isOn: $showActions)
                    Toggle(L("settings_ask"), isOn: $askEnabled)
                } header: { Text(L("settings_experimental")) } footer: { Text(L("settings_show_actions_hint") + "\n\n" + L("settings_ask_hint")) }
                Section {
                    if !Storage.ready(Prefs.reader) { Text(L("storage_model_pending", Int(Prefs.reader.files.reduce(0) { $0 + $1.size } / 1_000_000))).font(.footnote).foregroundStyle(.secondary) }
                    if models.isEmpty { Text(L("storage_none")).foregroundStyle(.secondary) }
                    ForEach(models) { i in
                        HStack { VStack(alignment: .leading) { Text(i.label); Text(L(i.kind)).font(.caption2).foregroundStyle(.secondary)
                            if i.cacheBytes > 0 { Text(L("storage_compile_cache", ByteCountFormatter.string(fromByteCount: i.cacheBytes, countStyle: .file))).font(.caption2).foregroundStyle(.secondary) } }; Spacer(); Text(ByteCountFormatter.string(fromByteCount: i.bytes, countStyle: .file)).foregroundStyle(.secondary) }
                            .swipeActions { if m.activeJob == nil && !m.recording { Button(L("storage_delete"), role: .destructive) { toDelete = i } } }   // not under a run: its models are mmaped
                    }
                } header: { Text(L("settings_storage")) } footer: { if !models.isEmpty { Text(L("storage_total", ByteCountFormatter.string(fromByteCount: models.reduce(0) { $0 + $1.bytes }, countStyle: .file))) } }
                Section(L("settings_about")) {
                    HStack { Text("Prime Minutes"); Spacer(); Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "").foregroundStyle(.secondary) }
                    Text(L("about_license"))
                    DisclosureGroup(L("about_components")) {
                        ForEach(["lic_nemo", "lic_crispasr", "lic_audiocpp", "lic_ggml", "lic_xasr", "lic_nemotron", "lic_opencc"], id: \.self) { Text(L($0)).font(.footnote) }
                    }
                }
            }
            .confirmationDialog(L("storage_delete_title"), isPresented: Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } }), titleVisibility: .visible) {
                Button(L("storage_delete"), role: .destructive) { if let i = toDelete { Storage.delete(i); models = Storage.models() } }
            } message: { if let i = toDelete { Text(L("storage_delete_body", i.label, ByteCountFormatter.string(fromByteCount: i.bytes, countStyle: .file))) } }
            .navigationTitle(L("settings"))
            .toolbar { Button(L("done")) { dismiss() } }
        }
    }
}

struct PodcastView: View {
    let pick: (Episode) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var series: [PodcastSeries] = []
    @State private var episodes: [Episode] = []
    @State private var current: PodcastSeries?
    @State private var busy = false
    @State private var busyLabel = "dl_searching"
    @State private var error: String?
    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error).foregroundStyle(.red) }
                if current == nil {
                    ForEach(series) { s in
                        Button { open(s) } label: {
                            VStack(alignment: .leading) { Text(s.title).font(.headline); Text(s.artist).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                } else {
                    ForEach(episodes) { e in
                        Button { pick(e); dismiss() } label: {
                            VStack(alignment: .leading) {
                                Text(e.title)
                                Text(L("podcast_transcribe")).font(.caption2.weight(.semibold)).foregroundStyle(.blue)
                                Text([e.duration, String(e.published.prefix(16))].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                if busy { HStack(spacing: 8) { ProgressView(); Text(L(busyLabel)).font(.footnote).foregroundStyle(.secondary) } }
            }
            .navigationTitle(current?.title ?? L("podcast"))
            .searchable(text: $query, prompt: L("podcast_search"))
            .onSubmit(of: .search) { search() }
            .toolbar {
                if current != nil { ToolbarItem(placement: .navigation) { Button(L("back")) { current = nil; episodes = [] } } }
                ToolbarItem { Button(L("done")) { dismiss() } }
            }
        }
    }
    private func search() {
        busy = true; busyLabel = "dl_searching"; error = nil; current = nil
        Task { defer { busy = false }; do { series = try await Podcast.search(query) } catch { self.error = L("status_error", error.localizedDescription) } }
    }
    private func open(_ s: PodcastSeries) {
        busy = true; busyLabel = "dl_loading_episodes"; error = nil; current = s
        Task { defer { busy = false }; do { episodes = try await Podcast.episodes(s.feedUrl) } catch { self.error = L("status_error", error.localizedDescription) } }
    }
}

extension Notification.Name { static let voxSessionSaved = Notification.Name("voxSessionSaved") }
