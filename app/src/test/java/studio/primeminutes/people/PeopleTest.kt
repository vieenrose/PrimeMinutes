package studio.primeminutes.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.people.CleanSpeech
import studio.primeminutes.core.people.LinkOps
import studio.primeminutes.core.people.PeopleCodec
import studio.primeminutes.core.people.PeopleDoc
import studio.primeminutes.core.people.PeopleMigration
import studio.primeminutes.core.people.PeopleOps
import studio.primeminutes.core.people.PeoplePicker
import studio.primeminutes.core.people.PeopleRouting
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.people.PersonNames
import studio.primeminutes.core.people.SpeakerCarryOver
import studio.primeminutes.core.people.SpeakerCarryOver.Turn
import studio.primeminutes.core.people.SpeakerLink

/** The people directory's pure rules (docs/design/speaker-tags.md); iOS runs the same cases (PeopleTests.swift). */
class PeopleTest {
    /** A toy 繁→简 fold for the cases below (the app uses OpenCC). */
    private val fold: (String) -> String = { it.replace('陳', '陈').replace('經', '经').replace('理', '理') }

    private fun person(id: String, name: String, last: Long = 0, aliases: List<String> = emptyList(), note: String? = null) =
        Person(id, name, note = note, aliases = aliases, lastUsed = last)

    @Test fun codecRoundTripsAndToleratesJunk() {
        val d = PeopleDoc(listOf(person("a", "Mark", 5, listOf("Mark Chen"), "Sales"), Person("b", "我", me = true, voiceEnabled = false)), migrated = true, migrationFound = 3)
        assertEquals(d, PeopleCodec.decode(PeopleCodec.encode(d)))
        assertEquals(PeopleDoc(), PeopleCodec.decode("not json"))
        assertEquals(1, PeopleCodec.decode("""{"people":[{"id":"x","name":"A"},{"name":"no id"},{"id":"y","name":"  "}],"future":1}""").people.size)
        val links = mapOf(0 to SpeakerLink("Mark", "a"), 3 to SpeakerLink("王經理"))
        assertEquals(links, PeopleCodec.decodeLinks(PeopleCodec.encodeLinks(links)))
    }

    @Test fun cross_platform_json_shape() {
        // iOS decodes exactly this text (PeopleTests.swift has the same literal).
        val text = """{"version":1,"people":[{"id":"6f1c","name":"Mark","note":"Sales","aliases":["Mark Chen"],"created":1,"lastUsed":2,"voiceEnabled":true}],"migrated":true,"migrationFound":0}"""
        val d = PeopleCodec.decode(text)
        assertEquals("Mark", d.people[0].name); assertEquals(listOf("Mark Chen"), d.people[0].aliases); assertEquals(2L, d.people[0].lastUsed)
        assertEquals(d, PeopleCodec.decode(PeopleCodec.encode(d)))
    }

    @Test fun normalizationAndDefaultLabels() {
        assertEquals("mark chen", PersonNames.normalize("  ＭＡＲＫ   Chen "))
        assertTrue(PersonNames.same("陳經理", "陈经理", fold))
        assertTrue(PersonNames.isDefaultLabel("Speaker 2")); assertTrue(PersonNames.isDefaultLabel("語者 12"))
        assertTrue(PersonNames.isDefaultLabel("说话人3")); assertTrue(PersonNames.isDefaultLabel("说话者 4")); assertTrue(PersonNames.isDefaultLabel("說話者 1")); assertFalse(PersonNames.isDefaultLabel("Speaker Mark"))
        assertFalse(PersonNames.isPersonName("  "))
    }

    @Test fun migrationMergesIdenticalNamesAndSkipsDefaults() {
        var n = 0
        val r = PeopleMigration.run(PeopleDoc(), listOf(
            PeopleMigration.Meeting("m1", 100, mapOf(0 to SpeakerLink("Mark"), 1 to SpeakerLink("陳經理"), 2 to SpeakerLink("Speaker 3"))),
            PeopleMigration.Meeting("m2", 200, mapOf(1 to SpeakerLink("mark "), 0 to SpeakerLink("陈经理"))),
        ), fold, now = 300, newId = { "p${n++}" })
        assertEquals(2, r.created); assertTrue(r.doc.migrated); assertEquals(2, r.doc.migrationFound)
        val mark = r.doc.people.first { PersonNames.same(it.name, "mark") }
        assertEquals("mark", mark.name)                      // newest meeting's spelling
        assertEquals(200L, mark.lastUsed)
        assertEquals(mark.id, r.links["m1"]!![0]!!.person); assertEquals(mark.id, r.links["m2"]!![1]!!.person)
        assertNull(r.links["m1"]!![2])                       // a default label is not a person
        assertEquals(r.links["m1"]!![1]!!.person, r.links["m2"]!![0]!!.person)   // 繁/简 are one person
        // A second run adds nothing.
        val again = PeopleMigration.run(r.doc, listOf(PeopleMigration.Meeting("m1", 100, r.links["m1"]!!)), fold, 400)
        assertEquals(0, again.created); assertEquals(r.doc.people.size, again.doc.people.size)
    }

    @Test fun mergeRenameDeleteSplit() {
        var d = PeopleDoc(listOf(person("a", "Mark", 1), person("b", "Mark Chen", 9)))
        d = PeopleOps.merge(d, from = "b", into = "a")
        assertEquals(1, d.people.size); assertEquals(listOf("Mark Chen"), d.people[0].aliases); assertEquals(9L, d.people[0].lastUsed)
        d = PeopleOps.rename(d, "a", "Mark Chen")
        assertEquals("Mark Chen", d.people[0].name); assertEquals(emptyList<String>(), d.people[0].aliases)
        val links = mapOf(0 to SpeakerLink("Mark", "a"), 1 to SpeakerLink("Lin", "c"), 2 to SpeakerLink("X"))
        assertEquals("Mark Chen", LinkOps.rename(links, "a", "Mark Chen")[0]!!.name)
        assertEquals(SpeakerLink("Mark", null), LinkOps.delete(links, "a", clearNames = false)[0])
        assertNull(LinkOps.delete(links, "a", clearNames = true)[0])
        assertEquals(SpeakerLink("Marcus", "n"), LinkOps.split(links, "a", person("n", "Marcus"))[0])
        assertEquals(SpeakerLink("Lin", "c"), LinkOps.merge(links, "b", person("a", "Mark"))[1])
        // A link to a person that is gone reads as a plain name.
        assertEquals(SpeakerLink("Lin", null), LinkOps.resolve(links, d)[1])
        assertEquals(SpeakerLink("Mark Chen", "a"), LinkOps.resolve(links, d)[0])
    }

    @Test fun pickerOrder() {
        val d = PeopleDoc(listOf(
            person("a", "Alice", 10), person("b", "Bob", 50), person("c", "Carol", 0), person("d", "Dan", 40),
            person("e", "Eve", 30), person("f", "Fay", 20), person("g", "Gus", 5), person("h", "Hal", 0),
        ))
        val usage = mapOf("c" to PeoplePicker.Usage(9), "h" to PeoplePicker.Usage(1), "g" to PeoplePicker.Usage(2))
        val s = PeoplePicker.sections(d, usage, inMeeting = mapOf("e" to 2), query = "")
        assertEquals(listOf("e"), s.inMeeting.map { it.person.id }); assertEquals(2, s.inMeeting[0].inMeetingAs)
        assertEquals(listOf("b", "d", "f", "a", "g"), s.recent.map { it.person.id })      // 5 most recent, in-meeting left out
        assertEquals(listOf("c", "h"), s.all.map { it.person.id })                         // then by meetings
        val q = PeoplePicker.sections(d, usage, emptyMap(), query = "a")
        assertEquals("a", q.all.first().person.id)                                        // prefix first
        assertTrue(q.canAdd)
        assertFalse(PeoplePicker.sections(d, usage, emptyMap(), "Speaker 4").canAdd)
        assertEquals(listOf("a"), PeoplePicker.sections(d, usage, emptyMap(), "ALICE").sameName.map { it.id })
        assertEquals(listOf("b"), PeoplePicker.sections(PeopleDoc(listOf(person("b", "Robert", aliases = listOf("Bob")))), usage, emptyMap(), "bob").all.map { it.person.id })
        assertEquals(setOf("x", "y"), PeoplePicker.homonyms(PeopleDoc(listOf(person("x", "Mark"), person("y", "mark"), person("z", "Lin")))))
    }

    @Test fun askRoutingMatchesWholeNames() {
        val d = PeopleDoc(listOf(person("a", "Mark"), person("b", "陳經理"), person("c", "Lin", aliases = listOf("Lin Wei")), person("d", "王")))
        assertEquals(setOf("a"), PeopleRouting.mentioned("What did Mark say about the budget?", d, fold))
        assertEquals(emptySet<String>(), PeopleRouting.mentioned("market prices", d, fold))
        assertEquals(setOf("b"), PeopleRouting.mentioned("陈经理说了什么", d, fold))
        assertEquals(setOf("c"), PeopleRouting.mentioned("what did lin wei decide", d, fold))
        assertEquals(emptySet<String>(), PeopleRouting.mentioned("王子", d, fold))                  // one Han character is not a name
        val links = mapOf("m1" to mapOf(0 to SpeakerLink("Mark", "a"), 1 to SpeakerLink("Lin", "c")), "m2" to mapOf(2 to SpeakerLink("x")))
        assertEquals(mapOf("m1" to setOf(0)), PeopleRouting.meetingsOf(links, setOf("a")))
    }

    @Test fun carryOverByTimeOverlap() {
        val old = listOf(Turn(0.0, 10.0, 0), Turn(10.0, 20.0, 1), Turn(20.0, 30.0, 0), Turn(30.0, 32.0, 2))
        // The re-run swaps ids 0/1 and splits nothing; speaker 2's 2 s are now part of speaker 5's 12 s.
        val new = listOf(Turn(0.0, 10.2, 1), Turn(10.2, 20.0, 0), Turn(20.0, 30.0, 1), Turn(30.0, 42.0, 5))
        val m = SpeakerCarryOver.mapping(old, new)
        assertEquals(1, m[0]); assertEquals(0, m[1]); assertNull(m[2])
        val names = SpeakerCarryOver.carry(old, mapOf(0 to "Mark", 1 to "Lin", 2 to "Eve"), new)
        assertEquals(mapOf(1 to "Mark", 0 to "Lin"), names)
        assertEquals(emptyMap<Int, String>(), SpeakerCarryOver.carry(old, emptyMap<Int, String>(), new))
    }

    @Test fun cleanSpeechSelection() {
        // Speaker 0: lines spread over 300 s; one overlaps speaker 1 (left out); one is too short.
        val t = listOf(
            Turn(0.0, 20.0, 0), Turn(19.0, 25.0, 1), Turn(30.0, 31.0, 0), Turn(40.0, 60.0, 0),
            Turn(110.0, 150.0, 0), Turn(210.0, 240.0, 0), Turn(280.0, 300.0, 1),
        )
        val s = CleanSpeech.spans(t, 0)
        assertTrue(s.none { it.start < 20.0 })                      // overlapped line skipped
        assertTrue(s.none { it.start in 29.0..32.0 })               // 1 s line too short
        assertEquals(CleanSpeech.CAP, s.sumOf { it.sec }, 1e-6)      // capped at 60 s
        assertTrue(s.any { it.start > 200 } && s.any { it.start in 100.0..160.0 })   // every third sampled
        assertEquals(40.1, s.first().start, 1e-6)                    // trimmed 0.1 s
    }

    @Test fun unknownPersonFieldsSurviveARoundTrip() {
        val json = """{"version":1,"people":[{"id":"a","name":"Mark","contact":"lk-1","emails":["m@x.org"],"created":1,"lastUsed":2}]}"""
        val doc = PeopleCodec.decode(json)
        val again = PeopleCodec.decode(PeopleCodec.encode(PeopleOps.rename(doc, "a", "Marc")))
        val o = org.json.JSONObject(PeopleCodec.encode(again)).getJSONArray("people").getJSONObject(0)
        assertEquals("Marc", o.getString("name"))
        assertEquals("lk-1", o.getString("contact"))
        assertEquals("m@x.org", o.getJSONArray("emails").getString(0))
        assertNull(PeopleCodec.decode("""{"people":[{"id":"b","name":"Ada"}]}""").people[0].extra)
    }
}
