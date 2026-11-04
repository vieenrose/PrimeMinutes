import Foundation

/// Runs the reader on its own thread: on slow CPUs (A12: ~12 tok/s prefill) a reader call takes 1-2 min, and inline it
/// would throttle the transcription. Lines queue up; the reader catches up behind the ASR and after it.
final class ReaderWorker: @unchecked Sendable {
    private let reader: MeetingReader, lock = NSCondition()
    private var queue: [Line] = [], busy = false, paused = false, stopped = false, snapshot: [Note] = [], total = 0, done = 0
    private var error: Error?
    /// `paused`: hold the lines until `resume()` (low-RAM devices read after the transcription, not during it).
    init(_ r: MeetingReader, paused: Bool = false) {
        reader = r; self.paused = paused
        // Low priority (like the Android reader lane): ASR is the only lane that loses data when it runs late.
        let t = Thread { [self] in
            while true {
                // `self.paused`, not the init parameter of the same name: the closure would capture the parameter,
                // `resume()` could never lift it, and the worker and `drain` woke each other forever.
                lock.lock(); while (queue.isEmpty || self.paused) && !stopped { busy = false; lock.broadcast(); lock.wait() }
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
        t.qualityOfService = .utility; t.start()
    }
    func offer(_ l: Line) { lock.lock(); queue.append(l); total += 1; lock.broadcast(); lock.unlock() }
    func resume() { lock.lock(); paused = false; lock.broadcast(); lock.unlock() }
    /// The job is over (finished, stopped or failed): drop what is queued and end the thread after the current line.
    func stop() { lock.lock(); stopped = true; queue.removeAll(); lock.broadcast(); lock.unlock() }
    var journal: [Note] { lock.lock(); defer { lock.unlock() }; return snapshot }
    /// The first reading error, if any: the job then reports "summary unavailable", not an empty meeting.
    var failure: Error? { lock.lock(); defer { lock.unlock() }; return error }
    var progress: (done: Int, total: Int) { lock.lock(); defer { lock.unlock() }; return (done, total) }
    /// Blocks until every queued line has been read. Waiting for lines that are held back would never end, so a
    /// paused worker with a queue is resumed first. `tick` runs at most once per `tickEvery` seconds (it posts to the
    /// main actor), and the wait never wakes the worker: the worker's own broadcasts (a line read, idle) wake this.
    func drain(tickEvery: TimeInterval = 1, stopRequested: () -> Bool = { StopFlag.on }, _ tick: (Int, Int) -> Void) {
        lock.lock()
        var last = Date.distantPast
        while (!queue.isEmpty || busy) && !stopped && !stopRequested() {
            if paused && !queue.isEmpty { paused = false; lock.broadcast() }
            let now = Date()
            if now.timeIntervalSince(last) >= tickEvery { last = now; let p = (done, total); lock.unlock(); tick(p.0, p.1); lock.lock(); continue }
            lock.wait(until: last.addingTimeInterval(tickEvery))
        }
        lock.unlock()
    }
}
