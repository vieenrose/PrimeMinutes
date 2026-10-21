import Foundation

// Live speaker names (Android SessionState.speakerNames while a run is going): the labels the live rows read, and how
// a mid-run rename / clear / merge survives the run that saves the meeting. ios/App/SpeakerRefs.swift LiveNames.
@main struct LiveRenameTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }
    static let numbered = { (i: Int) in "語者 \(i + 1)" }
    static func u(_ t: String, _ s: Double, _ e: Double, _ spk: Int) -> Utterance { Utterance(speaker: spk, start: s, end: e, text: t) }

    static func main() {
        // A row's label (Android speakerLabel): the confirmed name, else the numbered default.
        var names = LiveNames.setting([:], 0, SpeakerLink(name: "陳經理", person: "p1"))
        check(LiveNames.label(names, 0, numbered: numbered) == "陳經理", "named row shows the name")
        check(LiveNames.label(names, 1, numbered: numbered) == "語者 2", "unnamed row keeps its number")
        // Naming mid-run relabels every line of that speaker at once (the rows read the same map): both speakers named.
        names = LiveNames.setting(names, 1, SpeakerLink(name: "Alice", person: nil))
        let lines = [u("開場", 0, 2, 0), u("報告", 2, 5, 1), u("補充", 5, 7, 0)]
        check(lines.map { LiveNames.label(names, $0.speaker, numbered: numbered) } == ["陳經理", "Alice", "陳經理"], "all lines of a speaker relabel together")
        // Trimmed: a name typed with spaces is stored plain (Android commitSpeakerName trims).
        check(LiveNames.label(LiveNames.setting([:], 2, SpeakerLink(name: " 小林 ")), 2, numbered: numbered) == "小林", "name trimmed")
        check(LiveNames.label(LiveNames.setting([:], 2, SpeakerLink(name: "   ")), 2, numbered: numbered) == "語者 3", "blank is no name")

        // Clear (Android clearSpeakerName): "Speaker n" comes back in the same frame, the person stays in the directory.
        let cleared = LiveNames.cleared(names, 0)
        check(LiveNames.label(cleared, 0, numbered: numbered) == "語者 1", "cleared row is numbered again")
        check(cleared[0]?.person == nil, "a clear drops the person link")
        check(LiveNames.stored(cleared) == [1: SpeakerLink(name: "Alice")], "a cleared speaker is not stored: \(LiveNames.stored(cleared))")

        // A clear is a tombstone: the names a re-run carries must not refill it (Android removes the key).
        let carried = [0: SpeakerLink(name: "陳經理", person: "p1"), 1: SpeakerLink(name: "Bob")]
        check(LiveNames.overlaid(carried, cleared) == [1: SpeakerLink(name: "Alice")], "a live clear stays cleared over carried names")
        // The live screen's choice outranks what the run carried; a speaker it never touched keeps its carried name.
        check(LiveNames.overlaid(carried, [2: SpeakerLink(name: "小林")]) == [0: SpeakerLink(name: "陳經理", person: "p1"), 1: SpeakerLink(name: "Bob"), 2: SpeakerLink(name: "小林")], "carried names stay where the run said nothing")
        check(LiveNames.overlaid(carried, [:]) == carried, "a run with no live names keeps what it carried")

        // Merge (Android SpeakerEdits.merge: `names - from`): the merged id loses its name, the target keeps its own.
        check(LiveNames.merging(names, from: 0, into: 1) == [1: SpeakerLink(name: "Alice")], "the merged speaker loses its name")
        let merged = LiveNames.merging(names, from: 1, into: 2)
        check(merged == [0: SpeakerLink(name: "陳經理", person: "p1")], "the name of the merged id is gone, the others stay")
        check(LiveNames.label(merged, 2, numbered: numbered) == "語者 3", "the target it merged into keeps its own label")
        check(LiveNames.merging(names, from: 1, into: 1) == names, "merging a speaker into itself changes nothing")

        // A speaker left without lines loses its name (Android dropName): the re-run renumbered them away.
        let gone = LiveNames.kept(names, [u("開場", 0, 2, 0), u("報告", 2, 5, 1)])
        check(gone == [0: SpeakerLink(name: "陳經理", person: "p1"), 1: SpeakerLink(name: "Alice")], "lines present keep their names")
        check(LiveNames.kept(names, [u("開場", 0, 2, 0)]) == [0: SpeakerLink(name: "陳經理", person: "p1")], "a speaker with no line loses its name")

        print("live-rename tests: OK")
    }
}
