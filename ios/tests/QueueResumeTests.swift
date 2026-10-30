import Foundation

// "Process all" on the queue bar resumes a job the user stopped (it used to stay parked until a relaunch).
@main struct QueueResumeTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }
    static func main() {
        let a = UUID(), b = UUID(), c = UUID(), d = UUID()
        let w = [(id: a, downloading: false), (id: b, downloading: false), (id: c, downloading: true), (id: d, downloading: false)]
        // a stopped (parked), b failed (parked with its error), c downloading, d plain queued
        let p = QueueResume.plan(waiting: w, parked: [a, b], failed: [b])
        check(p.run == [a, d], "runs the stopped and the queued job, in order: \(p.run)")
        check(p.unpark == [a], "unparks the stopped job only")
        let only = QueueResume.plan(waiting: [(id: a, downloading: false)], parked: [a], failed: [])
        check(only.run.count == 1 && only.unpark == [a], "a lone paused job: count 1 and it gets unparked (the reported bug)")
        check(QueueResume.plan(waiting: [], parked: [a], failed: []).unpark.isEmpty, "nothing waiting, nothing unparked")
        print("QueueResumeTests PASS")
    }
}
