import SwiftUI
import os

/// Runs the reader on its own thread: on slow CPUs (A12: ~12 tok/s prefill) a reader call takes 1-2 min, and inline it
/// would throttle the transcription. Lines queue up; the reader catches up behind the ASR and after it.
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

/// The processing queue (Android foreground service + persistent queue): works through the queued recordings and
/// imports one at a time (ASR + diarization + reader), checkpoints them, parks a stopped or failed job, archives the
/// result. Also runs the live pipeline of a recording (`process(live:)`) on behalf of `CaptureController`.
@MainActor final class JobRunner: ObservableObject {
    let queue: JobQueue
    private let screen: MeetingScreen
    private let library: SessionLibrary
    private let downloads: ModelDownloads
    /// The recorder: its job is not for the queue yet, and a recording owns the screen.
    weak var capture: CaptureController?
    init(queue: JobQueue, screen: MeetingScreen, library: SessionLibrary, downloads: ModelDownloads) {
        self.queue = queue; self.screen = screen; self.library = library; self.downloads = downloads
    }
    private var recording: Bool { capture?.recording ?? false }
    private var recordingJob: UUID? { capture?.recordingJob }

    @Published var pending = 0
    @Published var waiting: [Job] = []
    @Published var activeJob: UUID?
    func syncQueue() async { waiting = await queue.all; pending = waiting.count }
    /// Android "stop (resumable)": the running job stops at the next chunk and stays queued, parked until resumed.
    @Published var parked: Set<UUID> = []
    func stopProcessing() { guard activeJob != nil else { return }; StopFlag.on = true; screen.status = L("stopping") }
    /// Set by [yieldToSystem]: the stop that follows keeps the job queued instead of parking it.
    private var yielding = false
    /// The background window is over: stop the queued job at its checkpoint; the next window or launch resumes it.
    func yieldToSystem() { guard activeJob != nil, liveJob == nil else { return }; yielding = true; StopFlag.on = true }
    func resume(_ j: Job) { parked.remove(j.id); failed[j.id] = nil; drain() }
    /// Jobs whose last run failed → their error, shown on the queue row with Retry.
    @Published var failed: [UUID: String] = [:]
    func processNext(_ j: Job) { guard j.id != activeJob else { return }; Task { await queue.promote(j.id); await syncQueue() } }
    func unqueue(_ j: Job) { guard j.id != activeJob else { return }; failed[j.id] = nil
        if j.download != nil { Model.shared.imports.remove(j); return }   // not downloaded yet: its download stops, its partial file goes
        Task {
        await queue.remove(j.id); Checkpoint.remove(j.id)
        // Android: removing from the queue keeps the recording in the library (unprocessed), so it can be transcribed later
        if FileManager.default.fileExists(atPath: JobQueue.url(j).path), !(await library.all()).contains(where: { $0.id == j.id }) {
            var s = Session(title: j.title ?? L("meeting"), summary: "", seconds: AudioDecode.duration(JobQueue.url(j)) ?? 0, lines: [], notes: [])
            s.id = j.id; s.date = j.date; s.audio = j.audio; s.captureStart = j.captureStart
            try? await library.store.save(s); await library.refresh()
        }
        await syncQueue()
    } }
    private func archive(lines: [Utterance], notes: [Note], title: String, summary: String, seconds: Double, job: Job? = nil, readerGuard: ReaderGuard? = nil) async {
        // No speech: still a library entry with its audio (Android keeps the entry, status_no_speech), never a silent drop.
        // Only fillers or noise tags count as no speech too (SpeechCheck): no "no notes" summary, its own status.
        let silent = !SpeechCheck.hasSpeech(lines.map(\.text))
        let summary = silent && (summary.isEmpty || summary == L("summary_no_notes")) ? L("status_no_speech") : summary
        if silent && !recording { screen.status = L("status_no_speech") }
        let fallback = lines.first.map { String($0.text.prefix(20)) } ?? L("meeting")
        var s = Session(title: silent ? L("status_no_speech") : title.isEmpty ? fallback : title, summary: summary, seconds: seconds, lines: lines, notes: silent ? [] : notes)
        if let job { s.id = job.id; s.date = job.date; s.audio = job.audio; s.captureStart = job.captureStart; if let t = job.title, !t.isEmpty { s.title = t } }
        if !summary.isEmpty && !silent { s.reader = Prefs.readerId }
        s.readerGuard = readerGuard
        let old = resummarized.removeValue(forKey: s.id)
        // Names confirmed on the live screen during this run (Android: what the screen's speakerNames holds is what
        // persists after a run, through its edit journal). They are the user's answer, so a re-run's carried names
        // only fill a run that has none — exactly Android's carry rule (`if (s.speakerNames.isEmpty() && …)`).
        let live = screen.names, liveNamed = LiveNames.stored(live)
        // Re-summarize keeps the transcript: the stored one, read NOW — lines, speaker names or a title the user
        // edited while the reader ran were overwritten by the snapshot taken at the tap.
        if let old {
            let cur = await library.all().first { $0.id == s.id } ?? old
            s.lines = cur.lines; s.speakerNames = cur.speakerNames; s.speakerPeople = cur.speakerPeople; s.linesUserEdited = cur.linesUserEdited
            if cur.title != old.title { s.title = cur.title }
        } else if liveNamed.isEmpty, job != nil, let prev = await library.all().first(where: { $0.id == s.id }), prev.speakerNames?.isEmpty == false, !prev.lines.isEmpty, !lines.isEmpty {
            // A re-transcribe renumbers the speakers: the names (and person links) follow the new speakers that cover
            // the same speech (SpeakerCarryOver, Android SessionReducer carry).
            let old = Dictionary(uniqueKeysWithValues: (prev.speakerNames ?? [:]).compactMap { k, v in Int(k).map { ($0, SpeakerLink(name: v, person: prev.speakerPeople?[k])) } })
            let kept = SpeakerCarryOver.carry(SpeakerCarryOver.turns(prev.lines), old, SpeakerCarryOver.turns(lines))
            PeopleStore.apply(kept, to: &s)
            if !kept.isEmpty { StatusLog.add("people: \(kept.count) speaker names kept by the re-run") }
        }
        // A title the user typed (before the run or while it ran) survives every run (TitleEdit).
        if job != nil, let stored = await library.all().first(where: { $0.id == s.id }) {
            let t = TitleEdit.merged(run: s.title, stored: stored.title, storedUserSet: stored.titleUserSet); s.title = t.title; s.titleUserSet = t.userSet
        }
        // The live names land in the meeting over what it had (a clear stays cleared; SessionView writes them the same
        // way for a finished meeting), so the saved meeting shows what the live rows showed.
        if !liveNamed.isEmpty {
            PeopleStore.apply(LiveNames.overlaid(PeopleStore.links(s), live), to: &s)
            StatusLog.add("people: \(liveNamed.count) speaker names from the live screen")
        }
        do { try await library.store.save(s) } catch { screen.status = L("session_save_failed"); Notifier.failed(s.title); return }   // Android: never claim success on a failed save
        NotificationCenter.default.post(name: .voxSessionSaved, object: s, userInfo: old.map { ["undo": $0] })
        await library.refresh(); await syncQueue()
        LibraryIndexer.shared.request(s)
        VoiceStore.shared.request(s.id)   // voice vectors (only when recognition is on)
        Notifier.done(s.title)
    }

    // MARK: live recording (driven by CaptureController)
    /// The recording being fed straight into `process` (ASR + diarization + reader live); the queue waits for it.
    private(set) var liveJob: UUID?
    func beginLive(_ id: UUID) { liveJob = id; activeJob = id }
    /// The live run is over: a stopped one is parked for Resume, a failed one (still queued) parked with its error.
    func settleLive(_ id: UUID) async {
        if StopFlag.on { StopFlag.on = false; parked.insert(id); screen.status = L("status_stopped") }
        else if await queue.contains(id) { parked.insert(id); failed[id] = screen.status }
    }
    func endLive() async {
        liveJob = nil; activeJob = nil; await syncQueue()
        drain()   // whatever was queued meanwhile
    }

    /// Transcribe + read a source of 16 kHz mono chunks (a file, the bundled sample). `feed` pushes chunks until done or false.
    func process(seconds: Double, job: Job? = nil, skipper: SilenceSkipper? = nil, resume: Checkpoint.Partial? = nil, offset: Double = 0, enroll: SeamEnrollment? = nil, live: Bool = false, feed: @escaping @Sendable (([Float]) -> Bool) throws -> Void) async {
        screen.status = L("loading_models"); screen.clear(); StopFlag.on = false
        // A job starting mid-answer cancels Ask: the pipeline wins (Android AskEngine). Its engine stays loaded but idle.
        if AskEngine.shared.phase.working { AskEngine.shared.cancel() }
        await Task.detached {
            let cached = resume != nil ? nil : job.flatMap { Checkpoint.load($0.id) }   // transcript done by an earlier attempt: only the reader is left
            let speechReady = cached != nil ? true : await self.downloads.downloadSpeech()
            guard speechReady else { await MainActor.run { self.screen.status = L("load_failed") }; return }
            let eng = cached != nil ? nil : ModelDownloads.nemoEngine()
            if cached == nil && eng == nil { await MainActor.run { self.screen.status = L("load_failed") }; return }
            let rm = Prefs.readerForLoad()
            // Wait for the prefetch only when it holds the reader files this run needs (not for a speech-model update).
            if Dev.env["VOX_READER_DIR"] == nil, !(await self.downloads.store.isComplete(rm)) { await self.downloads.awaitPrefetch(speechOnly: false) }
            if Dev.env["VOX_READER_DIR"] == nil, !(await self.downloads.store.isComplete(rm)) {   // like Android: the notes model downloads on first use
                do { try await self.downloads.fetch(rm, "reader", onPause: { self.downloads.pausedStatus($0) }) { d, t in Task { @MainActor in self.screen.status = L("download_reader_p", SizeFormat.bytes(d), SizeFormat.bytes(t)) } } }
                catch { await MainActor.run { self.screen.status = L("download_failed", "\(error)") } }
            }
            let stored = await self.downloads.store.isComplete(rm) ? await self.downloads.store.dir(rm).path : nil
            let r = ReaderFactory.make(dir: Dev.env["VOX_READER_DIR"].map { $0.hasPrefix("/") ? $0 : NSHomeDirectory() + "/" + $0 } ?? stored, model: rm)
            let agentUi = self.screen.agent
            let reader = MeetingReader(llm: r.llm, systemPrompt: r.systemPrompt, events: { e in Task { @MainActor in agentUi.apply(e) } }, budget: rm.budget, card: rm.card)
            do { try reader.start() } catch { Prefs.reportReadFailure(); await MainActor.run { self.screen.status = L("reader_error", "\(error)") }; return }
            Storage.reclaimReplaced()   // the chosen model loads: a model it replaced (kept during a migration) can go
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
                        // A recording started while this queued job runs owns the screen (it streams its own lines):
                        // the job keeps going and archives, it just stops painting over it.
                        guard live || !self.recording else { return }
                        self.screen.lines = conv.utterances(all); self.screen.notes = conv.notes(j)
                        if live { if self.recording { self.screen.status = L("recording_s", Int(fed)) }; return }
                        self.jobProgress = 0.8 * min(1, (max(0, fed - en.length) + offset) / max(seconds, 1))
                        self.screen.status = L("transcribing_s", Int(max(0, fed - en.length) + offset), Int(seconds))
                    }
                    Stage.set("idle")
                    return true
                } }
            } catch { if !StopFlag.on { await MainActor.run { self.screen.status = L("audio_error", error.localizedDescription) } }; return }
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
                if SpeechCheck.hasSpeech(cached.map(\.text)) { for u in cached { if let l = ReaderSummarizer.toLine(u) { worker.offer(l) } } }
            } else {
                let fresh = eng?.finish() ?? []
                final = full(fresh, stable: fresh.count).0
                // The live view only ever fed frozen lines: the last segment and the settle window are in the
                // final transcript only, and the meeting's closing decisions with them (Android: ReaderLane.finish).
                var tail = 0
                if SpeechCheck.hasSpeech(final.map(\.text)) { for u in final where u.start >= fedUntil - 0.01 { if let l = ReaderSummarizer.toLine(u) { worker.offer(l); tail += 1 } } }
                StatusLog.add("trace finish: \(tail) closing lines to the reader")
                if let job { Checkpoint.save(final, job.id) }
                if lowRam { eng?.close(); StatusLog.add("trace models freed, reader starts (\(os_proc_available_memory() / 1_048_576) MB free)"); worker.resume() }
            }
            // No speech (silence, fillers, noise tags): the AI notes model is not run on it (Android SpeechCheck).
            let silent = !SpeechCheck.hasSpeech(final.map(\.text))
            if silent { worker.stop(); StatusLog.add("trace no speech: AI notes model not run") }
            else { worker.drain { d, t in Task { @MainActor in self.jobProgress = 0.8 + 0.2 * Double(d) / Double(max(t, 1)); guard live || !self.recording else { return }; self.screen.status = L("reading_s", d, t) } } }
            var readError = worker.failure
            if readError == nil && !silent { do { _ = try reader.finish() } catch { readError = error; StatusLog.add("trace reader.finish error: \(error)") } }
            // Model guardrail: this job's off-protocol counts, logged (a warning above the card's threshold), shown, saved.
            let rg = reader.guardCounts, gc = rm.card.reader.guardrail, over = rg.exceeds(gc)
            StatusLog.add((over ? "WARNING guard: off-protocol output above \(gc.maxOffProtocolRate): " : "guard: ") + rg.summary)
            await MainActor.run { agentUi.apply(.guardCounts(rg, exceeded: over)) }
            // A failed reading is not an empty meeting: no title or summary from a half-read journal, and the
            // cause on the status line (Android readFailed). The transcript is still archived.
            let journal = readError == nil && !silent ? reader.journal : []
            // Title, prose and the minutes in the meeting's language, as the reader heard it (the card's variant for it).
            let langCard = rm.card.forLanguage(reader.language)
            let sum = ReaderSummarizer(llm: r.llm, card: langCard)
            // No notes at all (a short or off-topic recording): one plain line, not five empty sections (Android summary_no_notes).
            let t = journal.isEmpty ? nil : sum.title(journal), prose = journal.isEmpty ? nil : sum.prose(journal) ?? langCard.reader.minutes(journal)
            let (fl, fn, ft, fp) = (conv.utterances(final), conv.notes(journal), conv.text(t ?? ""), prose.map { conv.text($0) } ?? "")
            let summaryText = fp.isEmpty && !fl.isEmpty && readError == nil ? L("summary_no_notes") : fp
            let why = readError.map { String(String(describing: $0).split(separator: "\n").first ?? "").prefix(120) }
            await MainActor.run { guard live || !self.recording else { return }; self.screen.lines = fl; self.screen.notes = fn; self.screen.title = ft; self.screen.summary = summaryText; let ns = Set(fl.map(\.speaker)).count; self.screen.status = why.map { L("summary_failed") + " [\($0)]" } ?? (ns > 1 ? L("status_transcript_lines_speakers", fl.count, ns) : L("status_transcript_lines", fl.count)); self.screen.agent.apply(.state(.done, notes: fn.count)) }
            await self.archive(lines: fl, notes: fn, title: ft, summary: summaryText, seconds: live ? (eng?.fedSeconds ?? 0) : seconds, job: job, readerGuard: silent ? nil : rg)
            if let job { Checkpoint.remove(job.id); await self.queue.remove(job.id) }
            Stage.set("idle")
        }.value
    }

    /// DEV (VOX_AUTORUN): the bundled sample through the whole pipeline, not queued.
    func runSample() {
        guard let pcm = loadWav(Bundle.main.path(forResource: "sample", ofType: "wav") ?? "\(Dev.env["VOX_BASE"] ?? "")/clips/diar_ref_2spk_123s.wav") else { screen.status = L("sample_missing"); return }
        Task { await process(seconds: Double(pcm.count) / 16000) { sink in
            for s in stride(from: 0, to: pcm.count, by: 16000) { if !sink(Array(pcm[s..<min(pcm.count, s + 16000)])) { return } }
        } }
    }

    // MARK: processing queue
    private var draining = false
    /// One continued-processing task per drain, asked for when its first job starts.
    private var continuedSubmitted = false
    /// Sessions being re-summarized, as they were: names carried over, and Android's "New summary ready · Undo".
    private var resummarized: [UUID: Session] = [:]
    var drainTask: Task<Void, Never>?
    /// Android re-transcribe / re-summarize: re-queues the session's saved audio under the same id (summarize reuses the stored transcript, so only the reader runs).
    func rerun(_ s: Session, transcribe: Bool) {
        guard let a = s.audio else { StatusLog.add("trace rerun: no audio"); return }
        var j = Job(id: s.id, date: s.date, audio: a, title: s.titleUserSet == true ? s.title : RerunTitle.kept(s.title, placeholders: [L("meeting"), L("status_no_speech")], firstLine: s.lines.first?.text))
        j.captureStart = s.captureStart
        guard FileManager.default.fileExists(atPath: JobQueue.url(j).path) else { StatusLog.add("trace rerun: audio file missing"); return }
        Task { guard !(await queue.contains(s.id)) else { return }   // already queued (double tap): one row, one run
        if transcribe { Checkpoint.remove(s.id); resummarized[s.id] = nil } else { Checkpoint.save(s.lines, s.id); resummarized[s.id] = s }
        screen.status = L("status_starting")   // Android: shown until the first model line
        await queue.add(j); await syncQueue(); drain() }
    }
    /// The queue is being worked through (the background task lasts as long).
    var isDraining: Bool { draining }
    /// Counts drains: a continued-processing task follows the drain it was asked for, not a later one (it polls once a
    /// second, and a drain started within that second kept the old task alive next to the new one's).
    private(set) var drainCount = 0
    /// The running job's progress, 0…1: transcription up to 0.8, then the reading.
    @Published var jobProgress = 0.0
    /// Processes every queued job in order (also at launch: recordings cut short by a kill are picked up here).
    func drain() {
        guard !draining, liveJob == nil else { return }
        draining = true; drainCount += 1
        UIApplication.shared.isIdleTimerDisabled = true   // iOS suspends a locked app: stay awake while the queue works (Android: wake lock)
        if Dev.env["VOX_SHOTS"] == nil { Notifier.requestPermission() }   // screenshots: no permission prompt over the UI
        drainTask = Task {
            while let job = await queue.first(excluding: parked.union(recordingJob.map { [$0] } ?? [])) {
                let url = JobQueue.url(job)
                WavWriter.repair(url)
                guard let secs = AudioDecode.duration(url), secs > 0 else { parked.insert(job.id); failed[job.id] = L("import_failed"); screen.status = L("import_failed"); await syncQueue(); continue }   // undecodable: shown with its error and Remove, not dropped silently
                let left = await queue.count; pending = left; activeJob = job.id; jobProgress = 0; await syncQueue()
                if !continuedSubmitted { continuedSubmitted = true; BackgroundWork.continueInBackground(drain: drainCount) }
                if left > 1 { screen.status = L("queue_n", left) }
                let skipper = SilenceSkipper()
                // A complete transcript (ASR had ended, killed while reading) means the reader alone (process() restores it);
                // else a partial left by a Stop, or saved every minute (a kill): continue after its seam.
                let plan = ResumePlan.decide(full: Checkpoint.load(job.id), partial: Checkpoint.loadPartial(job.id))
                if case .readerOnly = plan { StatusLog.add("trace resume: transcript complete, reader only") }
                let partial = plan.partial
                let from = partial.map { max(0, $0.seam - SeamStitcher.preroll) } ?? 0
                let enroll = partial.map { SeamEnrollment.plan($0.lines, offset: from) } ?? .none(from)
                if !enroll.clips.isEmpty { StatusLog.add("trace resume enrollment: \(enroll.clips.count) clips, \(Int(enroll.length)) s") }
                await process(seconds: secs, job: job, skipper: skipper, resume: partial, offset: from, enroll: enroll) { sink in try AudioPrep.stream(url, skipper: skipper, from: from, clips: enroll.clips, onChunk: sink) }
                // A stop that came after the transcription (the reader does not check it) finds the job saved and gone.
                let stopped = StopFlag.on, yielded = yielding, queued = await queue.contains(job.id)
                StopFlag.on = false; yielding = false
                if stopped && yielded && queued {
                    activeJob = nil; await syncQueue()
                    StatusLog.add("trace queue yielded to the system: \(job.id) stays queued"); break
                }
                if stopped && queued { parked.insert(job.id); screen.status = L("status_stopped") }
                else if queued { parked.insert(job.id); failed[job.id] = screen.status; Notifier.failed(job.title ?? L("meeting")) }   // failed run (still queued: a saved one leaves the queue): parked with its error and a Retry (Android), never looped on — whatever sits ahead of it (a parked job, a promoted one)
                activeJob = nil; await syncQueue()
            }
            draining = false; continuedSubmitted = false
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }
}
