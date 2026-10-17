import Foundation

/// Voice recognition's pure parts (docs/design/speaker-tags.md §2.6, §4): the same cases as Android VoiceTest.kt.
/// ios/tests/run.sh
struct Utterance { var speaker: Int, start: Double, end: Double, text: String }   // the app's (Engine.swift) without the bridge

@main struct VoiceTests {
    nonisolated(unsafe) static var failures = 0
    static func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL \(what)") } }
    static func v(_ x: Float...) -> [Float] { VoiceMath.normalize(x) }
    typealias Exemplar = VoiceFiles.Exemplar
    typealias Turn = SpeakerCarryOver.Turn

    /// Seeded Gaussian (SplitMix64 + Box-Muller), so the gate case is reproducible.
    struct Rng {
        var s: UInt64
        mutating func next() -> Double {
            s &+= 0x9E3779B97F4A7C15
            var z = s
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9; z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return Double((z ^ (z >> 31)) >> 11) / Double(1 << 53)
        }
        mutating func gaussian() -> Double { (-2 * log(max(next(), 1e-300))).squareRoot() * Foundation.cos(2 * .pi * next()) }
    }

    static func main() {
        let card = SpeakerCard.shipped
        do {   // theShippedCardParses
            check(card.licence == "Apache-2.0" && card.dim == 192 && card.mels == 80 && card.meanNorm && !card.int16Scale, "card fields")
            check(abs(card.tLow - 0.50) < 1e-6 && abs(card.tHigh - 0.70) < 1e-6 && abs(card.margin - 0.05) < 1e-6 && abs(card.outlier - 0.30) < 1e-6, "card thresholds")
            check(card.files[card.graph] != nil && card.totalBytes == 28_800_672, "card files")
            check(card.minCleanSec == 10 && card.capSec == 60 && card.revision.hasPrefix("https://"), "card speech contract")
        }
        do {   // levelsFollowTheCard
            check(VoiceMath.level(0.80, 0.40, card) == 3, "likely")
            check(VoiceMath.level(0.80, 0.78, card) == 2, "no margin over the next person: only possible")
            check(VoiceMath.level(0.55, 0.10, card) == 2, "possible")
            check(VoiceMath.level(0.45, 0.10, card) == 0, "nothing")
        }
        do {   // suggestionsAssignEachPersonToOneSpeaker
            let people = ["mark": v(1, 0, 0), "ada": v(0, 1, 0)]
            let speakers = [0: v(1, 0.1, 0), 1: v(0.9, 0.2, 0), 2: v(0.1, 1, 0)]
            let s = VoiceMath.suggest(speakers, people, card)
            check(s[0]?.first?.person == "mark" && s[0]?.first?.level == 3, "speaker 0 is Mark, likely")
            check(!(s[1] ?? []).contains { $0.person == "mark" }, "Mark went to speaker 0, the better match")
            check(s[2]?.first?.person == "ada", "speaker 2 is Ada")
            let ex = VoiceMath.suggest(speakers, people, card, excluded: [0: ["mark"], 1: ["mark"], 2: ["mark"]])
            check(!ex.values.joined().contains { $0.person == "mark" }, "excluded people are never offered")
            check(VoiceMath.suggest([0: v(0, 0, 1)], people, card)[0]?.isEmpty == true, "nobody close enough")
        }
        do {   // centroidIsSecondsWeighted
            let c = VoiceMath.centroid([(v(1, 0), 30), (v(0, 1), 10)])!
            check(c[0] > c[1] && abs(c[0] * c[0] + c[1] * c[1] - 1) < 1e-5, "centroid weighted and unit")
            check(VoiceMath.centroid([]) == nil, "empty centroid")
        }
        do {   // sidecarsRoundTripAndRejectCorruption
            let m = VoiceFiles.MeetingVoices(modelId: card.id, fingerprint: "abc", speakers: [
                .init(speaker: 0, seconds: 42, vector: v(1, 2, 3)), .init(speaker: 3, seconds: 12.5, vector: v(3, 2, 1))])
            var b = VoiceFiles.encodeMeeting(m)
            let d = VoiceFiles.decodeMeeting(b)
            check(d == m && d?.of(3)?.vector == m.speakers[1].vector, "sidecar round trip")
            b[b.count / 2] &+= 1
            check(VoiceFiles.decodeMeeting(b) == nil, "corrupt sidecar rejected")
            let es = [Exemplar(person: "p", modelId: card.id, meeting: "m1", speaker: 0, seconds: 20, confirmed: true, vector: v(1, 0)),
                      Exemplar(person: "p", modelId: card.id, meeting: "m2", speaker: 1, seconds: 15, confirmed: false, vector: v(0, 1))]
            check(VoiceFiles.decodePeople(VoiceFiles.encodePeople(es)) == es, "people file round trip")
            check(VoiceFiles.decodePeople(VoiceFiles.encodeMeeting(m)) == nil, "wrong magic")
            check(VoiceFiles.crc32(Data("123456789".utf8)) == 0xCBF43926, "crc32 check value")
        }
        do {   // exemplarsFollowTheDirectory
            let a = Exemplar(person: "mark", modelId: card.id, meeting: "m1", speaker: 0, seconds: 30, confirmed: true, vector: v(1, 0))
            let b = Exemplar(person: "mark", modelId: card.id, meeting: "m2", speaker: 0, seconds: 30, confirmed: false, vector: v(0, 1))
            let c = Exemplar(person: "ada", modelId: card.id, meeting: "m2", speaker: 1, seconds: 30, confirmed: true, vector: v(0, 1))
            var l = [a, b, c]
            let mark = VoiceFiles.Exemplars.voices(l, card.id)["mark"]
            check(mark.map { abs($0[0] - 1) < 1e-6 && abs($0[1]) < 1e-6 } == true, "only confirmed exemplars make a voice")
            check(VoiceFiles.Exemplars.voices(l, "other-model")["mark"] == nil, "other model")
            check(!VoiceFiles.Exemplars.removeMeeting(l, "m2").contains { $0.person == "ada" }, "a deleted meeting deletes its samples")
            l = VoiceFiles.Exemplars.move(l, "ada", "mark")
            check(l.allSatisfy { $0.person == "mark" }, "merge moves")
            l = VoiceFiles.Exemplars.moveMeeting(l, "m1", "mark", "marcus")
            check(l.first { $0.meeting == "m1" }?.person == "marcus", "split moves one meeting")
            var many: [Exemplar] = []
            for i in 0..<VoiceFiles.Exemplars.cap + 5 {
                many = VoiceFiles.Exemplars.put(many, Exemplar(person: "p", modelId: card.id, meeting: "m\(i)", speaker: 0, seconds: 10, confirmed: true, vector: v(1, 0))) { Int64($0.dropFirst())! }
            }
            check(many.count == VoiceFiles.Exemplars.cap && !many.contains { $0.meeting == "m0" }, "newest CAP kept")
        }
        do {   // fingerprintChangesWithTheSpans
            let a = [0: [CleanSpeech.Span(start: 1, end: 5)]], b = [0: [CleanSpeech.Span(start: 1, end: 5.5)]]
            check(VoiceFiles.fingerprint(a) == VoiceFiles.fingerprint(a) && VoiceFiles.fingerprint(a) != VoiceFiles.fingerprint(b), "fingerprint")
            check(VoiceFiles.fingerprint(a).count == 24, "fingerprint length")
        }
        do {   // theGateKeepsVoiceAndDropsPauses
            let sr = 16_000
            var r = Rng(s: 1)
            // 1 s of room noise (-60 dB), 2 s of "voice" (-12 dB), 1 s of room noise.
            let pcm = (0..<4 * sr).map { i in Float(r.gaussian() * ((sr..<3 * sr).contains(i) ? 0.25 : 0.001)) }
            let runs = VoiceGate.runs(pcm, sr: sr)
            check(runs.count == 1 && abs(runs[0].lowerBound - sr) <= 320 && abs(runs[0].upperBound - 3 * sr) <= 320, "gate run \(runs)")
            check(VoiceGate.runs([Float](repeating: 0, count: 2 * sr), sr: sr).isEmpty, "silence")
        }
        do {   // linesAreReadRoundRobinOverTheThirds
            let t = [Turn(start: 0, end: 5, speaker: 0), Turn(start: 5, end: 30, speaker: 0), Turn(start: 40, end: 44, speaker: 0),
                     Turn(start: 70, end: 73, speaker: 0), Turn(start: 73, end: 90, speaker: 1)]
            let o = CleanSpeech.ordered(t, speaker: 0).map { ($0.start * 10).rounded() / 10 }
            check(o == [5.1, 40.1, 70.1, 0.1], "round robin \(o)")
            check(CleanSpeech.spans(t, speaker: 0, cap: 10).map(\.start).map { ($0 * 10).rounded() / 10 } == [5.1], "capped spans")
        }
        do {   // aSparseLineGivesNoVoice
            let words = "we want it to be original something people have not thought of"
            let utts = [Utterance(speaker: 0, start: 0, end: 5, text: words), Utterance(speaker: 0, start: 5, end: 65, text: "Okay."),
                        Utterance(speaker: 1, start: 65, end: 70, text: words), Utterance(speaker: 0, start: 70, end: 75, text: words)]
            let t = CleanSpeech.voiceTurns(utts)
            check(t.map(\.speaker) == [0, -9, 1, 0], "sparse line belongs to nobody")
            check(CleanSpeech.ordered(t, speaker: 0).map { ($0.start * 10).rounded() / 10 }.sorted() == [0.1, 70.1], "sparse line not read")
        }
        print(failures == 0 ? "VoiceTests PASS" : "VoiceTests: \(failures) FAILED")
        exit(failures == 0 ? 0 : 1)
    }
}
