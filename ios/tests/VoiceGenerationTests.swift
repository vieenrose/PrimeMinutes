import Foundation

/// "Delete all voice data" while the voice lane is still computing a meeting (Android VoiceStoreTest): the sidecar the
/// computation writes afterwards must not bring the data back. VoiceStore.storeSidecar = VoiceGeneration.store + write.
/// ios/tests/run.sh
struct Utterance { var speaker: Int, start: Double, end: Double, text: String }   // the app's (Engine.swift) without the bridge

@main struct VoiceGenerationTests {
    nonisolated(unsafe) static var failures = 0
    static func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL \(what)") } }

    static func main() {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("voice-gen-\(UUID().uuidString)/voices")
        let file = dir.appendingPathComponent("meeting.bin")
        let mv = VoiceFiles.MeetingVoices(modelId: "m", fingerprint: "fp", speakers: [VoiceFiles.SpeakerVoice(speaker: 0, seconds: 30, vector: [1, 2, 3])])
        let g = VoiceGeneration()
        func store(_ gen: Int) -> Bool {
            (try? g.store(gen) {
                try fm.createDirectory(at: dir, withIntermediateDirectories: true)
                try VoiceFiles.encodeMeeting(mv).write(to: file, options: .atomic)
            }) ?? false
        }
        func deleteAll() { g.bump { try? fm.removeItem(at: dir) } }

        do {   // the lane starts on the meeting, then the user deletes all voice data: nothing is written
            let started = g.current
            deleteAll()
            check(!store(started), "a computation from before the deletion is not stored")
            check(!fm.fileExists(atPath: file.path), "no sidecar after the deletion")
        }
        do {   // a computation started after it is stored
            check(store(g.current), "a fresh computation is stored")
            check(fm.fileExists(atPath: file.path) && (try? Data(contentsOf: file)).flatMap(VoiceFiles.decodeMeeting)?.speakers.count == 1, "the sidecar is readable")
        }
        do {   // turning recognition off (data kept) also drops a computation under way
            let again = g.current
            g.bump()
            check(!store(again), "off: the computation under way is dropped")
        }
        do {   // the race itself: the lane computes off the main thread while "Delete all" runs
            try? fm.removeItem(at: dir)
            let started = g.current, done = DispatchSemaphore(value: 0), deleted = DispatchSemaphore(value: 0)
            nonisolated(unsafe) var stored = true
            Thread.detachNewThread {
                deleted.wait()                     // the computation outlives the deletion (the 50 s of the report)
                stored = store(started); done.signal()
            }
            deleteAll(); deleted.signal(); done.wait()
            check(!stored && !fm.fileExists(atPath: file.path), "deletion during the computation wins")
        }
        try? fm.removeItem(at: dir.deletingLastPathComponent())
        print(failures == 0 ? "VoiceGenerationTests PASS" : "VoiceGenerationTests FAILED (\(failures))")
        exit(failures == 0 ? 0 : 1)
    }
}
