import Foundation

// Host stand-ins for the app's globals (Support.swift is not compiled here).
enum StatusLog {
    nonisolated(unsafe) static var lines: [String] = []; static let m = NSLock()
    static func add(_ s: String) { m.lock(); lines.append(s); m.unlock() }
    static var all: [String] { m.lock(); defer { m.unlock() }; return lines }
}
enum Stage { static func set(_ s: String) {} }
enum StopFlag { nonisolated(unsafe) static var on = false }

/// Replies NEXT; every reading turn takes `delay` seconds, so the worker is slow like a phone.
final class SlowReply: ReaderLlm {
    let delay: TimeInterval; var n = 0
    init(delay: TimeInterval) { self.delay = delay }
    // the reader counts every offered line through tokenize, so the delay is paid per line
    func tokenize(_ text: String, special: Bool) -> [Int] { Thread.sleep(forTimeInterval: delay); return text.unicodeScalars.map { Int($0.value) } }
    func append(_ tokens: [Int]) -> Int { n += tokens.count; return n }
    func generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Void) -> String { "NEXT" + stop }
    var seqLength: Int { n }
    func reset() { n = 0 }
}

/// ReaderWorker: a worker that starts paused reads nothing until resume() and then reads everything (the closure
/// once captured the init parameter, so resume() never took effect and drain spun until jetsam); drain never
/// spins; stop() ends the thread.
@main struct ReaderWorkerTests {
    static var fails = 0
    static func check(_ c: Bool, _ what: String) { if !c { fails += 1; print("FAIL \(what)") } }
    static func until(_ s: TimeInterval, _ c: () -> Bool) -> Bool { let end = Date().addingTimeInterval(s); while Date() < end { if c() { return true }; Thread.sleep(forTimeInterval: 0.01) }; return c() }
    nonisolated(unsafe) static var made = 0
    static func make(delay: TimeInterval, paused: Bool) -> ReaderWorker {
        made += 1
        let r = MeetingReader(llm: SlowReply(delay: delay), systemPrompt: "sys"); try! r.start()
        return ReaderWorker(r, paused: paused)
    }
    static func feed(_ w: ReaderWorker, _ n: Int) { for i in 0..<n { w.offer(Line(startS: i * 20, speaker: "S1", text: "line \(i)")) } }

    static func main() {
        // 1. paused: nothing is read; resume() reads every line
        do {
            let w = make(delay: 0, paused: true); feed(w, 5)
            Thread.sleep(forTimeInterval: 0.5)
            check(w.progress.done == 0, "paused worker reads nothing (done=\(w.progress.done))")
            w.resume()
            check(until(5) { w.progress.done == 5 }, "resume() lets the worker read all 5 lines (done=\(w.progress.done))")
            w.stop()
        }
        // 2. not paused: reads at once
        do {
            let w = make(delay: 0, paused: false); feed(w, 3)
            check(until(5) { w.progress.done == 3 }, "unpaused worker reads 3 lines")
            w.stop()
        }
        // 3. drain on a paused worker with a queue resumes it, finishes, and ticks at most once per interval
        do {
            let w = make(delay: 0.15, paused: true); feed(w, 4)
            var ticks = 0; let t0 = Date()
            w.drain(tickEvery: 0.1) { _, _ in ticks += 1 }
            let el = Date().timeIntervalSince(t0)
            check(w.progress.done == 4, "drain returns after every line was read (done=\(w.progress.done))")
            check(ticks >= 1 && Double(ticks) <= el / 0.1 + 2, "ticks bounded: \(ticks) in \(el) s")
            w.stop()
        }
        // 4. drain with a stop request returns early, without spinning
        do {
            let w = make(delay: 0.4, paused: false); feed(w, 20)
            var ticks = 0; let t0 = Date()
            w.drain(tickEvery: 0.1, stopRequested: { Date().timeIntervalSince(t0) > 0.5 }) { _, _ in ticks += 1 }
            let el = Date().timeIntervalSince(t0)
            check(el < 2 && w.progress.done < 20, "stop request ends drain early (\(el) s, done=\(w.progress.done))")
            check(Double(ticks) <= el / 0.1 + 2, "ticks bounded under a stop request: \(ticks)")
            w.stop()
        }
        // 5. drain on an idle worker returns at once
        do {
            let w = make(delay: 0, paused: false); let t0 = Date()
            w.drain { _, _ in }
            check(Date().timeIntervalSince(t0) < 0.5, "empty drain returns at once")
            w.stop()
        }
        // 6. stop() ends the thread, also of a paused worker
        do {
            let a = make(delay: 0, paused: true), b = make(delay: 0, paused: false); feed(a, 2)
            a.stop(); b.stop()
            check(until(5) { StatusLog.all.filter { $0.contains("reader worker ended") }.count == made }, "stop() ends every worker thread (\(made) made)")
            check(a.progress.done == 0, "a stopped paused worker read nothing")
        }
        print(fails == 0 ? "reader worker: all passed" : "reader worker: \(fails) failed"); exit(fails == 0 ? 0 : 1)
    }
}
