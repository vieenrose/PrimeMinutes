import Foundation

/// The people directory's pure rules (docs/design/speaker-tags.md): the same cases as Android PeopleTest.kt.
/// ios/tests/run.sh
struct Utterance { var speaker: Int, start: Double, end: Double, text: String }   // the app's (Engine.swift) without the bridge

@main struct PeopleTests {
    nonisolated(unsafe) static var failures = 0
    static func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL \(what)") } }
    /// A toy 繁→简 fold for the cases below (the app uses OpenCC).
    static let fold: PersonNames.Fold = { $0.replacingOccurrences(of: "陳", with: "陈").replacingOccurrences(of: "經", with: "经") }
    static func person(_ id: String, _ name: String, _ last: Int64 = 0, aliases: [String] = [], note: String? = nil) -> Person {
        Person(id: id, name: name, note: note, aliases: aliases, lastUsed: last)
    }
    typealias Turn = SpeakerCarryOver.Turn
    static func T(_ a: Double, _ b: Double, _ s: Int) -> Turn { Turn(start: a, end: b, speaker: s) }

    static func main() {
        do {   // codecRoundTripsAndToleratesJunk
            let d = PeopleDoc(people: [person("a", "Mark", 5, aliases: ["Mark Chen"], note: "Sales"), Person(id: "b", name: "我", voiceEnabled: false, me: true)], migrated: true, migrationFound: 3, voice: true)
            check(PeopleCodec.decode(PeopleCodec.encode(d)) == d, "codec round trip")
            check(PeopleCodec.decode("not json") == PeopleDoc(), "junk file")
            check(PeopleCodec.decode(#"{"people":[{"id":"x","name":"A"},{"name":"no id"},{"id":"y","name":"  "}],"future":1}"#).people.count == 1, "junk entries")
        }
        do {   // cross_platform_json_shape: Android decodes exactly this text (PeopleTest.kt has the same literal)
            let text = #"{"version":1,"people":[{"id":"6f1c","name":"Mark","note":"Sales","aliases":["Mark Chen"],"created":1,"lastUsed":2,"voiceEnabled":true}],"migrated":true,"migrationFound":0}"#
            let d = PeopleCodec.decode(text)
            check(d.people.first?.name == "Mark" && d.people[0].aliases == ["Mark Chen"] && d.people[0].lastUsed == 2 && d.people[0].note == "Sales" && d.migrated, "cross-platform decode")
            check(PeopleCodec.decode(PeopleCodec.encode(d)) == d, "cross-platform round trip")
            let enc = String(decoding: PeopleCodec.encode(d), as: UTF8.self)
            check(enc.contains(#""lastUsed":2"#) && enc.contains(#""voiceEnabled":true"#) && !enc.contains(#""me""#), "encoded keys \(enc)")
        }
        do {   // unknownPersonFieldsSurvive (a later app's keys, e.g. a contact link)
            let text = #"{"version":1,"people":[{"id":"a","name":"Mark","contact":"lk-1","emails":["m@x.org"],"created":1,"lastUsed":2},{"id":"b","name":"Lin"}]}"#
            let d = PeopleOps.rename(PeopleCodec.decode(text), "a", "Marc")
            let o = (try? JSONSerialization.jsonObject(with: PeopleCodec.encode(d))) as? [String: Any]
            let p = ((o?["people"] as? [[String: Any]]) ?? []).first { $0["id"] as? String == "a" }
            check(p?["name"] as? String == "Marc" && p?["contact"] as? String == "lk-1" && (p?["emails"] as? [[String: Any]])?.first?["address"] as? String == "m@x.org", "extra kept \(String(describing: p))")
            check(d.byId("b")?.extra == nil, "no extra")
            check(PeopleCodec.decode(PeopleCodec.encode(d)) == d, "extra round trip")
        }
        do {   // normalizationAndDefaultLabels
            check(PersonNames.normalize("  ＭＡＲＫ   Chen ") == "mark chen", "normalize")
            check(PersonNames.same("陳經理", "陈经理", fold), "fold")
            check(PersonNames.isDefaultLabel("Speaker 2") && PersonNames.isDefaultLabel("語者 12") && PersonNames.isDefaultLabel("说话人3"), "default labels")
            check(PersonNames.isDefaultLabel("说话者 4") && PersonNames.isDefaultLabel("說話者 1"), "zh-Hans/zh-Hant default labels")
            check(!PersonNames.isDefaultLabel("Speaker Mark") && !PersonNames.isPersonName("  "), "not default")
        }
        do {   // migrationMergesIdenticalNamesAndSkipsDefaults
            var n = 0
            let r = PeopleMigration.run(PeopleDoc(), [
                .init(id: "m1", date: 100, names: [0: SpeakerLink(name: "Mark"), 1: SpeakerLink(name: "陳經理"), 2: SpeakerLink(name: "Speaker 3")]),
                .init(id: "m2", date: 200, names: [1: SpeakerLink(name: "mark "), 0: SpeakerLink(name: "陈经理")]),
            ], fold: fold, now: 300, newId: { defer { n += 1 }; return "p\(n)" })
            check(r.created == 2 && r.doc.migrated && r.doc.migrationFound == 2, "migration counts \(r.created)")
            let mark = r.doc.people.first { PersonNames.same($0.name, "mark") }!
            check(mark.name == "mark", "newest spelling \(mark.name)")
            check(mark.lastUsed == 200, "lastUsed \(mark.lastUsed)")
            check(r.links["m1"]?[0]?.person == mark.id && r.links["m2"]?[1]?.person == mark.id, "links")
            check(r.links["m1"]?[2] == nil, "default label skipped")
            check(r.links["m1"]?[1]?.person == r.links["m2"]?[0]?.person, "繁/简 one person")
            let again = PeopleMigration.run(r.doc, [.init(id: "m1", date: 100, names: r.links["m1"]!)], fold: fold, now: 400)
            check(again.created == 0 && again.doc.people.count == r.doc.people.count, "rerun")
        }
        do {   // mergeRenameDeleteSplit
            var d = PeopleDoc(people: [person("a", "Mark", 1), person("b", "Mark Chen", 9)])
            d = PeopleOps.merge(d, from: "b", into: "a")
            check(d.people.count == 1 && d.people[0].aliases == ["Mark Chen"] && d.people[0].lastUsed == 9, "merge")
            d = PeopleOps.rename(d, "a", "Mark Chen")
            check(d.people[0].name == "Mark Chen" && d.people[0].aliases.isEmpty, "rename drops alias")
            let links: [Int: SpeakerLink] = [0: SpeakerLink(name: "Mark", person: "a"), 1: SpeakerLink(name: "Lin", person: "c"), 2: SpeakerLink(name: "X")]
            check(LinkOps.rename(links, "a", "Mark Chen")[0]?.name == "Mark Chen", "link rename")
            check(LinkOps.delete(links, "a", clearNames: false)[0] == SpeakerLink(name: "Mark"), "delete keeps name")
            check(LinkOps.delete(links, "a", clearNames: true)[0] == nil, "delete clears")
            check(LinkOps.split(links, from: "a", to: person("n", "Marcus"))[0] == SpeakerLink(name: "Marcus", person: "n"), "split")
            check(LinkOps.merge(links, from: "b", into: person("a", "Mark"))[1] == SpeakerLink(name: "Lin", person: "c"), "merge others")
            check(LinkOps.resolve(links, d)[1] == SpeakerLink(name: "Lin"), "resolve gone")
            check(LinkOps.resolve(links, d)[0] == SpeakerLink(name: "Mark Chen", person: "a"), "resolve")
        }
        do {   // pickerOrder
            let d = PeopleDoc(people: [person("a", "Alice", 10), person("b", "Bob", 50), person("c", "Carol", 0), person("d", "Dan", 40),
                                       person("e", "Eve", 30), person("f", "Fay", 20), person("g", "Gus", 5), person("h", "Hal", 0)])
            let usage: [String: PeoplePicker.Usage] = ["c": .init(meetings: 9), "h": .init(meetings: 1), "g": .init(meetings: 2)]
            let s = PeoplePicker.sections(d, usage: usage, inMeeting: ["e": 2], query: "")
            check(s.inMeeting.map(\.person.id) == ["e"] && s.inMeeting[0].inMeetingAs == 2, "in meeting")
            check(s.recent.map(\.person.id) == ["b", "d", "f", "a", "g"], "recent \(s.recent.map(\.person.id))")
            check(s.all.map(\.person.id) == ["c", "h"], "all \(s.all.map(\.person.id))")
            let q = PeoplePicker.sections(d, usage: usage, inMeeting: [:], query: "a")
            check(q.all.first?.person.id == "a" && q.canAdd, "prefix first")
            check(!PeoplePicker.sections(d, usage: usage, inMeeting: [:], query: "Speaker 4").canAdd, "no add default")
            check(PeoplePicker.sections(d, usage: usage, inMeeting: [:], query: "ALICE").sameName.map(\.id) == ["a"], "same name")
            check(PeoplePicker.sections(PeopleDoc(people: [person("b", "Robert", aliases: ["Bob"])]), usage: usage, inMeeting: [:], query: "bob").all.map(\.person.id) == ["b"], "alias")
            check(PeoplePicker.homonyms(PeopleDoc(people: [person("x", "Mark"), person("y", "mark"), person("z", "Lin")])) == ["x", "y"], "homonyms")
        }
        do {   // askRoutingMatchesWholeNames
            let d = PeopleDoc(people: [person("a", "Mark"), person("b", "陳經理"), person("c", "Lin", aliases: ["Lin Wei"]), person("d", "王")])
            check(PeopleRouting.mentioned("What did Mark say about the budget?", d, fold: fold) == ["a"], "Mark")
            check(PeopleRouting.mentioned("market prices", d, fold: fold).isEmpty, "market")
            check(PeopleRouting.mentioned("陈经理说了什么", d, fold: fold) == ["b"], "陈经理")
            check(PeopleRouting.mentioned("what did lin wei decide", d, fold: fold) == ["c"], "alias lin wei")
            check(PeopleRouting.mentioned("王子", d, fold: fold).isEmpty, "one Han char")
            let links: [String: [Int: SpeakerLink]] = ["m1": [0: SpeakerLink(name: "Mark", person: "a"), 1: SpeakerLink(name: "Lin", person: "c")], "m2": [2: SpeakerLink(name: "x")]]
            check(PeopleRouting.meetingsOf(links, ["a"]) == ["m1": [0]], "meetingsOf")
        }
        do {   // carryOverByTimeOverlap
            let old = [T(0, 10, 0), T(10, 20, 1), T(20, 30, 0), T(30, 32, 2)]
            let new = [T(0, 10.2, 1), T(10.2, 20, 0), T(20, 30, 1), T(30, 42, 5)]
            let m = SpeakerCarryOver.mapping(old, new)
            check(m[0] == 1 && m[1] == 0 && m[2] == nil, "mapping \(m)")
            check(SpeakerCarryOver.carry(old, [0: "Mark", 1: "Lin", 2: "Eve"], new) == [1: "Mark", 0: "Lin"], "carry")
            check(SpeakerCarryOver.carry(old, [Int: String](), new).isEmpty, "carry empty")
        }
        do {   // cleanSpeechSelection
            let t = [T(0, 20, 0), T(19, 25, 1), T(30, 31, 0), T(40, 60, 0), T(110, 150, 0), T(210, 240, 0), T(280, 300, 1)]
            let s = CleanSpeech.spans(t, speaker: 0)
            check(!s.contains { $0.start < 20 }, "overlapped skipped")
            check(!s.contains { (29...32).contains($0.start) }, "short skipped")
            check(abs(s.reduce(0) { $0 + $1.sec } - CleanSpeech.cap) < 1e-6, "cap")
            check(s.contains { $0.start > 200 } && s.contains { (100...160).contains($0.start) }, "thirds")
            check(abs((s.first?.start ?? 0) - 40.1) < 1e-6, "trim")
            let u = t.map { Utterance(speaker: $0.speaker!, start: $0.start, end: $0.end, text: "") }
            check(abs((CleanSpeech.sampleStart(u, speaker: 0) ?? 0) - 110.1) < 1e-6, "sample start = longest clean line")
        }
        print(failures == 0 ? "PeopleTests PASS" : "PeopleTests FAIL (\(failures))")
        exit(failures == 0 ? 0 : 1)
    }
}
