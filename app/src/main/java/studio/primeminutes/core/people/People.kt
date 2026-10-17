package studio.primeminutes.core.people

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * The people directory (docs/design/speaker-tags.md): the people the user has named, shared by every
 * meeting. A meeting's speaker is linked to a person by id; the name a meeting shows is the person's
 * name, so renaming a person renames them everywhere. Everything here is pure (no Android): the store
 * is [PeopleStore], the iOS port is `People.swift`, and both read and write the same JSON.
 */
data class Person(
    /** UUID, never reused. */
    val id: String,
    /** Display name, in the user's script. */
    val name: String,
    /** Optional disambiguator ("Sales"), shown next to a homonym. */
    val note: String? = null,
    /** Names merged into this person: Ask routing and the picker's search match them too. */
    val aliases: List<String> = emptyList(),
    val created: Long = 0L,
    /** Last link made or confirmed (epoch seconds): the picker's "recent" order. */
    val lastUsed: Long = 0L,
    /** False: never learn this voice (a role such as "Host"). Phase 2. */
    val voiceEnabled: Boolean = true,
    /** The phone's owner ("Me"). Phase 2. */
    val me: Boolean = false,
    /** E-mail addresses (docs/design/speaker-contacts.md §2.1), the first is the default (★). */
    val emails: List<PersonEmail> = emptyList(),
    /** The address-book entry this person was linked to through the system picker, or null. */
    val contact: ContactLink? = null,
    /**
     * Fields this version does not know (a JSON object, or null), written back unchanged: a later
     * feature (an address-book `contact` link, `emails`, …) can add keys to a person without an older
     * app dropping them.
     */
    val extra: String? = null,
)

/** One address of a person. [from]: "contact" (came from the address book) or "user" (typed). */
data class PersonEmail(val address: String, val from: String = FROM_USER) {
    companion object {
        const val FROM_USER = "user"
        const val FROM_CONTACT = "contact"
    }
}

/**
 * A link to an address-book entry (§2.2): the platform's stable key and a snapshot of what the
 * picker returned. [platform] "android" | "ios": a link made on the other platform is kept but not
 * used. [raw]: keys this version does not know, written back unchanged.
 */
data class ContactLink(
    val platform: String,
    val key: String,
    /** Android CONTACT_ID (a hint, re-resolved through the lookup key); null on iOS. */
    val id: Long? = null,
    val name: String? = null,
    val org: String? = null,
    val linked: Long = 0L,
    val synced: Long = 0L,
    val missing: Boolean = false,
    val raw: String? = null,
)

/** What the system contact picker handed over for one person (one tap, no permission). */
data class PickedContact(val name: String?, val emails: List<String>, val key: String?, val id: Long?, val org: String? = null, val platform: String = "android")

/**
 * E-mail address rules (§7.3), the same on both platforms: NFKC (an IME types `＠` and full-width
 * letters), one `@`, a 1–64 character local part, a dotted domain with a 2+ character TLD, no spaces,
 * 254 at most. Deliberately not RFC 5322: the mail app does the final check.
 */
object EmailRules {
    private val TLD = Regex("^[\\p{L}\\p{N}-]{2,}$")

    fun normalize(s: String): String = Normalizer.normalize(s.trim(), Normalizer.Form.NFKC).trim()

    fun isValid(s: String): Boolean {
        val a = normalize(s)
        if (a.length > 254 || a.any { it.isWhitespace() }) return false
        val at = a.indexOf('@')
        if (at < 0 || at != a.lastIndexOf('@')) return false
        val local = a.substring(0, at)
        val domain = a.substring(at + 1)
        if (local.isEmpty() || local.length > 64) return false
        val labels = domain.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return false
        return TLD.matches(labels.last())
    }

    /** Dedup key: the local part as typed, the domain lowercased. */
    fun key(s: String): String {
        val a = normalize(s)
        val at = a.lastIndexOf('@')
        return if (at < 0) a else a.substring(0, at) + "@" + a.substring(at + 1).lowercase(Locale.ROOT)
    }
}

data class PeopleDoc(
    val people: List<Person> = emptyList(),
    /** The names saved before the directory existed were gathered into it. */
    val migrated: Boolean = false,
    /** How many people that migration created: the People screen's one-time card (0 once seen). */
    val migrationFound: Int = 0,
    /** "Recognise voices (on this phone)": off until the user turns it on (Phase 2). */
    val voice: Boolean = false,
) {
    fun byId(id: String?): Person? = id?.let { i -> people.firstOrNull { it.id == i } }
}

/** One named speaker of a meeting: its name there (kept with the meeting) and the person it is linked to. */
data class SpeakerLink(val name: String, val person: String? = null)

/** The JSON of `people/people.json` and of a meeting's `speakers.json` (same on Android and iOS). */
object PeopleCodec {
    const val VERSION = 1
    private val KNOWN = setOf("id", "name", "note", "aliases", "created", "lastUsed", "voiceEnabled", "me", "emails", "contact")
    private val CONTACT_KNOWN = setOf("platform", "key", "id", "name", "org", "linked", "synced", "missing")

    private fun encodeContact(c: ContactLink): JSONObject = JSONObject().apply {
        c.raw?.let { r -> runCatching { JSONObject(r) }.getOrNull()?.let { x -> for (k in x.keys()) if (k !in CONTACT_KNOWN) put(k, x.get(k)) } }
        put("platform", c.platform); put("key", c.key)
        c.id?.let { put("id", it) }
        c.name?.let { put("name", it) }
        c.org?.let { put("org", it) }
        put("linked", c.linked); put("synced", c.synced)
        if (c.missing) put("missing", true)
    }

    private fun decodeContact(o: JSONObject?): ContactLink? {
        o ?: return null
        val key = o.optString("key").ifBlank { return null }
        return ContactLink(
            platform = o.optString("platform").ifBlank { "android" }, key = key,
            id = if (o.has("id")) o.optLong("id") else null,
            name = o.optString("name").trim().ifBlank { null }, org = o.optString("org").trim().ifBlank { null },
            linked = o.optLong("linked"), synced = o.optLong("synced"), missing = o.optBoolean("missing", false),
            raw = JSONObject().also { x -> for (k in o.keys()) if (k !in CONTACT_KNOWN) x.put(k, o.get(k)) }.takeIf { it.length() > 0 }?.toString(),
        )
    }

    private fun decodeEmails(a: JSONArray?): List<PersonEmail> {
        a ?: return emptyList()
        val out = ArrayList<PersonEmail>()
        for (i in 0 until a.length()) {
            // A plain string is accepted too (a hand-written or older file).
            val e = a.optJSONObject(i)?.let { PersonEmail(it.optString("address").trim(), it.optString("from").ifBlank { PersonEmail.FROM_USER }) }
                ?: a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { PersonEmail(it) } ?: continue
            if (e.address.isNotEmpty() && out.none { EmailRules.key(it.address) == EmailRules.key(e.address) }) out += e
        }
        return out
    }

    fun encode(doc: PeopleDoc): String {
        val arr = JSONArray()
        for (p in doc.people) arr.put(JSONObject().apply {
            // An unknown-shaped `contact` (not an object) rides in [Person.extra] and is written back as it was.
            p.extra?.let { e -> runCatching { JSONObject(e) }.getOrNull()?.let { x -> for (k in x.keys()) if (k !in KNOWN || (k == "contact" && p.contact == null)) put(k, x.get(k)) } }
            put("id", p.id); put("name", p.name)
            p.note?.let { put("note", it) }
            if (p.aliases.isNotEmpty()) put("aliases", JSONArray(p.aliases))
            put("created", p.created); put("lastUsed", p.lastUsed)
            put("voiceEnabled", p.voiceEnabled)
            if (p.me) put("me", true)
            if (p.emails.isNotEmpty()) put("emails", JSONArray().apply { p.emails.forEach { e -> put(JSONObject().put("address", e.address).put("from", e.from)) } })
            p.contact?.let { put("contact", encodeContact(it)) }
        })
        return JSONObject().put("version", VERSION).put("people", arr)
            .put("migrated", doc.migrated).put("migrationFound", doc.migrationFound).put("voice", doc.voice).toString()
    }

    /** Tolerant: unknown keys are ignored, a broken entry is skipped, a broken file is an empty directory. */
    fun decode(text: String?): PeopleDoc {
        if (text.isNullOrBlank()) return PeopleDoc()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return PeopleDoc()
        val arr = o.optJSONArray("people") ?: JSONArray()
        val people = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = p.optString("id").ifBlank { return@mapNotNull null }
            val name = p.optString("name").trim().ifBlank { return@mapNotNull null }
            Person(
                id = id, name = name,
                note = p.optString("note").trim().ifBlank { null },
                aliases = p.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
                created = p.optLong("created"), lastUsed = p.optLong("lastUsed"),
                voiceEnabled = p.optBoolean("voiceEnabled", true), me = p.optBoolean("me", false),
                emails = decodeEmails(p.optJSONArray("emails")), contact = decodeContact(p.optJSONObject("contact")),
                extra = JSONObject().also { x -> for (k in p.keys()) if (k !in KNOWN || (k == "contact" && p.optJSONObject(k) == null)) x.put(k, p.get(k)) }.takeIf { it.length() > 0 }?.toString(),
            )
        }
        return PeopleDoc(people.distinctBy { it.id }, o.optBoolean("migrated", false), o.optInt("migrationFound", 0), o.optBoolean("voice", false))
    }

    /** A meeting's `speakers.json`: `{"version":1,"speakers":{"0":{"name":"Mark","person":"<uuid>"}}}`. */
    fun encodeLinks(links: Map<Int, SpeakerLink>): String {
        val sp = JSONObject()
        for ((k, v) in links.toSortedMap()) sp.put(k.toString(), JSONObject().apply { put("name", v.name); v.person?.let { put("person", it) } })
        return JSONObject().put("version", VERSION).put("speakers", sp).toString()
    }

    fun decodeLinks(text: String?): Map<Int, SpeakerLink>? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val sp = o.optJSONObject("speakers") ?: return emptyMap()
        val out = HashMap<Int, SpeakerLink>()
        for (k in sp.keys()) {
            val id = k.toIntOrNull() ?: continue
            val v = sp.optJSONObject(k) ?: continue
            val name = v.optString("name").trim()
            if (name.isEmpty()) continue
            out[id] = SpeakerLink(name, v.optString("person").ifBlank { null })
        }
        return out
    }
}

/** Name matching: the same rule on both platforms. [fold] maps Han text to one script (OpenCC 繁→简). */
object PersonNames {
    private val SPACES = Regex("\\s+")
    /** The default labels in the three UI languages: never a person's name. */
    private val DEFAULT = Regex("^(speaker|語者|语者|說話人|说话人|說話者|说话者)\\s*\\d+$")

    fun normalize(s: String, fold: (String) -> String = { it }): String =
        fold(Normalizer.normalize(s, Normalizer.Form.NFKC)).lowercase(Locale.ROOT).trim().replace(SPACES, " ")

    fun isDefaultLabel(s: String): Boolean = DEFAULT.matches(normalize(s))

    /** A user-given name worth a person: not blank, not a default label. */
    fun isPersonName(s: String): Boolean = s.isNotBlank() && !isDefaultLabel(s)

    fun same(a: String, b: String, fold: (String) -> String = { it }): Boolean = normalize(a, fold) == normalize(b, fold)

    /** [query] matches [p] by a substring of its name or one of its aliases (case-, width- and script-insensitive). */
    fun matches(query: String, p: Person, fold: (String) -> String = { it }): Boolean {
        val q = normalize(query, fold)
        if (q.isEmpty()) return true
        return (listOf(p.name) + p.aliases).any { normalize(it, fold).contains(q) }
    }

    fun prefix(query: String, p: Person, fold: (String) -> String = { it }): Boolean {
        val q = normalize(query, fold)
        return (listOf(p.name) + p.aliases).any { normalize(it, fold).startsWith(q) }
    }
}

/** Directory edits, as pure functions of the document (the store persists the result). */
object PeopleOps {
    fun newId(): String = UUID.randomUUID().toString()

    fun create(doc: PeopleDoc, name: String, now: Long, id: String = newId(), me: Boolean = false): Pair<PeopleDoc, Person> {
        val p = Person(id = id, name = name.trim(), created = now, lastUsed = now, me = me)
        return doc.copy(people = doc.people + p) to p
    }

    private fun PeopleDoc.edit(id: String, f: (Person) -> Person) = copy(people = people.map { if (it.id == id) f(it) else it })

    fun rename(doc: PeopleDoc, id: String, name: String): PeopleDoc {
        val n = name.trim().ifBlank { return doc }
        return doc.edit(id) { p -> p.copy(name = n, aliases = p.aliases.filterNot { it == n }) }
    }

    fun setNote(doc: PeopleDoc, id: String, note: String?): PeopleDoc = doc.edit(id) { it.copy(note = note?.trim()?.ifBlank { null }) }

    fun touch(doc: PeopleDoc, id: String, now: Long): PeopleDoc = doc.edit(id) { it.copy(lastUsed = maxOf(it.lastUsed, now)) }

    fun setVoiceEnabled(doc: PeopleDoc, id: String, on: Boolean): PeopleDoc = doc.edit(id) { it.copy(voiceEnabled = on) }

    /** [from] is the same human as [into]: [from] goes, its name and aliases become [into]'s aliases. */
    fun merge(doc: PeopleDoc, from: String, into: String): PeopleDoc {
        if (from == into) return doc
        val a = doc.byId(from) ?: return doc
        val b = doc.byId(into) ?: return doc
        val aliases = (b.aliases + a.name + a.aliases).distinct().filter { it != b.name }
        val merged = b.copy(aliases = aliases, lastUsed = maxOf(a.lastUsed, b.lastUsed), created = minOf(a.created, b.created),
            note = b.note ?: a.note, me = a.me || b.me,
            emails = (b.emails + a.emails).distinctBy { EmailRules.key(it.address) }, contact = b.contact ?: a.contact)
        return doc.copy(people = doc.people.filter { it.id != from }.map { if (it.id == into) merged else it })
    }

    fun delete(doc: PeopleDoc, id: String): PeopleDoc = doc.copy(people = doc.people.filter { it.id != id })

    // --- contact side (docs/design/speaker-contacts.md §2) -----------------------------------------

    /** Adds [address] (normalised) unless the person has it already; [default] puts it first. */
    fun addEmail(doc: PeopleDoc, id: String, address: String, from: String = PersonEmail.FROM_USER, default: Boolean = false): PeopleDoc {
        if (!EmailRules.isValid(address)) return doc
        val a = EmailRules.normalize(address)
        return doc.edit(id) { p ->
            val rest = p.emails.filterNot { EmailRules.key(it.address) == EmailRules.key(a) }
            val had = p.emails.firstOrNull { EmailRules.key(it.address) == EmailRules.key(a) }
            val e = had ?: PersonEmail(a, from)
            p.copy(emails = if (default || had == null && p.emails.isEmpty()) listOf(e) + rest else if (had != null) p.emails else p.emails + e)
        }
    }

    fun removeEmail(doc: PeopleDoc, id: String, address: String): PeopleDoc =
        doc.edit(id) { p -> p.copy(emails = p.emails.filterNot { EmailRules.key(it.address) == EmailRules.key(address) }) }

    fun makeDefault(doc: PeopleDoc, id: String, address: String): PeopleDoc = doc.edit(id) { p ->
        val (hit, rest) = p.emails.partition { EmailRules.key(it.address) == EmailRules.key(address) }
        p.copy(emails = hit + rest)
    }

    /**
     * Links [id] to the contact the picker returned: its addresses are added (deduplicated, after
     * the person's own), the link and its snapshot are set. The person's name is never changed;
     * the organisation fills an empty note.
     */
    fun linkContact(doc: PeopleDoc, id: String, c: PickedContact, now: Long, chosen: String? = null): PeopleDoc {
        var d = doc
        // The address tapped in the picker first: it becomes the default when the person had none.
        for (e in listOfNotNull(chosen) + c.emails) d = addEmail(d, id, e, PersonEmail.FROM_CONTACT)
        return d.edit(id) { p ->
            p.copy(
                contact = c.key?.let { ContactLink(c.platform, it, c.id, c.name?.trim()?.ifBlank { null }, c.org?.trim()?.ifBlank { null }, now, now) } ?: p.contact,
                note = p.note ?: c.org?.trim()?.ifBlank { null },
            )
        }
    }

    /** "Unlink from contact": the link goes; its addresses stay as the user's, or go too ([removeEmails]). */
    fun unlinkContact(doc: PeopleDoc, id: String, removeEmails: Boolean): PeopleDoc = doc.edit(id) { p ->
        p.copy(contact = null, emails = if (removeEmails) p.emails.filter { it.from != PersonEmail.FROM_CONTACT }
            else p.emails.map { it.copy(from = PersonEmail.FROM_USER) })
    }

    /** Other people linked to the same address-book entry as [p] (usually the same human named twice). */
    fun sameContact(doc: PeopleDoc, p: Person): List<Person> {
        val c = p.contact ?: return emptyList()
        return doc.people.filter { it.id != p.id && it.contact?.platform == c.platform && it.contact.key == c.key }
    }
}

/** How a directory edit rewrites one meeting's links (pure; the store applies it to every meeting). */
object LinkOps {
    /** The person was renamed: the meeting's copy of the name follows. */
    fun rename(links: Map<Int, SpeakerLink>, id: String, name: String): Map<Int, SpeakerLink> =
        links.mapValues { (_, l) -> if (l.person == id) l.copy(name = name) else l }

    fun merge(links: Map<Int, SpeakerLink>, from: String, into: Person): Map<Int, SpeakerLink> =
        links.mapValues { (_, l) -> if (l.person == from) SpeakerLink(into.name, into.id) else l }

    /** The person was deleted: the speakers keep the name as a plain label, or lose it ([clearNames]). */
    fun delete(links: Map<Int, SpeakerLink>, id: String, clearNames: Boolean): Map<Int, SpeakerLink> =
        if (clearNames) links.filterValues { it.person != id }
        else links.mapValues { (_, l) -> if (l.person == id) l.copy(person = null) else l }

    /** "This meeting's Mark is someone else": its speakers linked to [from] go to [to]. */
    fun split(links: Map<Int, SpeakerLink>, from: String, to: Person): Map<Int, SpeakerLink> =
        links.mapValues { (_, l) -> if (l.person == from) SpeakerLink(to.name, to.id) else l }

    /** The display name of each linked speaker: the directory's, else the meeting's own (a deleted person). */
    fun resolve(links: Map<Int, SpeakerLink>, doc: PeopleDoc): Map<Int, SpeakerLink> =
        links.mapValues { (_, l) -> doc.byId(l.person)?.let { p -> SpeakerLink(p.name, p.id) } ?: l.copy(person = null) }
}

/**
 * The first launch with the directory: every user-given speaker name of every meeting becomes a
 * person, identical names (after [PersonNames.normalize]) one person — the user approved the
 * automatic merge, Split undoes a wrong one. Links are only added: a rerun is harmless.
 */
object PeopleMigration {
    /** A meeting's user-given names. [date]: epoch seconds. */
    data class Meeting(val id: String, val date: Long, val names: Map<Int, SpeakerLink>)

    data class Result(val doc: PeopleDoc, val links: Map<String, Map<Int, SpeakerLink>>, val created: Int)

    fun run(doc: PeopleDoc, meetings: List<Meeting>, fold: (String) -> String, now: Long, newId: () -> String = PeopleOps::newId): Result {
        var d = doc
        var created = 0
        val byKey = HashMap<String, Person>()
        for (p in d.people) byKey.putIfAbsent(PersonNames.normalize(p.name, fold), p)
        val out = HashMap<String, Map<Int, SpeakerLink>>()
        // Newest meeting first: its spelling of a name becomes the person's.
        for (m in meetings.sortedByDescending { it.date }) {
            val links = HashMap<Int, SpeakerLink>()
            for ((sid, l) in m.names) {
                if (l.person != null && d.byId(l.person) != null) { links[sid] = l; continue }
                if (!PersonNames.isPersonName(l.name)) continue
                val key = PersonNames.normalize(l.name, fold)
                val p = byKey[key] ?: run {
                    val (nd, np) = PeopleOps.create(d, l.name, m.date, newId())
                    d = nd; created++
                    byKey[key] = np; np
                }
                d = d.copy(people = d.people.map { if (it.id == p.id) it.copy(lastUsed = maxOf(it.lastUsed, m.date)) else it })
                links[sid] = SpeakerLink(l.name.trim(), p.id)
            }
            if (links.isNotEmpty()) out[m.id] = links
        }
        return Result(d.copy(migrated = true, migrationFound = d.migrationFound + created), out, created)
    }
}

/** What the rename sheet lists (docs/design/speaker-tags.md §2.1), pure so both platforms rank alike. */
object PeoplePicker {
    /** Per person: in how many meetings it is linked, and the newest of them (for the homonym hint). */
    data class Usage(val meetings: Int = 0, val lastDate: Long = 0L, val lastTitle: String? = null)

    data class Row(val person: Person, val meetings: Int, val inMeetingAs: Int? = null)

    data class Sections(
        /** People already linked to another speaker of this meeting (picking one asks "merge?"). */
        val inMeeting: List<Row>,
        val recent: List<Row>,
        val all: List<Row>,
        /** The typed text is a new name to offer ("Add …"). */
        val canAdd: Boolean,
        /** A person whose name equals the typed text (the "same person or homonym?" question). */
        val sameName: List<Person>,
    )

    const val RECENT = 5

    /**
     * [inMeeting]: person → the OTHER speaker of this meeting linked to it. [exclude]: people never
     * listed (the one the speaker is already linked to). Blank [query]: in-meeting, then the [RECENT]
     * most recently used, then the rest by meetings then name. A query: one filtered list (name or
     * alias substring; prefix matches first, then recency).
     */
    fun sections(
        doc: PeopleDoc, usage: Map<String, Usage>, inMeeting: Map<String, Int>, query: String,
        fold: (String) -> String = { it }, exclude: Set<String> = emptySet(),
    ): Sections {
        val people = doc.people.filter { it.id !in exclude }
        fun row(p: Person) = Row(p, usage[p.id]?.meetings ?: 0, inMeeting[p.id])
        val q = query.trim()
        val sameName = if (q.isEmpty()) emptyList() else doc.people.filter { PersonNames.same(it.name, q, fold) }
        if (q.isNotEmpty()) {
            val hits = people.filter { PersonNames.matches(q, it, fold) }
                .sortedWith(compareByDescending<Person> { PersonNames.prefix(q, it, fold) }.thenByDescending { it.lastUsed }.thenBy { it.name })
            val (inM, rest) = hits.partition { it.id in inMeeting }
            return Sections(inM.map(::row), emptyList(), rest.map(::row), canAdd = PersonNames.isPersonName(q), sameName = sameName)
        }
        val (inM, rest) = people.partition { it.id in inMeeting }
        val recent = rest.filter { it.lastUsed > 0 }.sortedByDescending { it.lastUsed }.take(RECENT)
        val others = rest.filter { r -> recent.none { it.id == r.id } }
            .sortedWith(compareByDescending<Person> { usage[it.id]?.meetings ?: 0 }.thenBy { PersonNames.normalize(it.name, fold) })
        return Sections(inM.map(::row), recent.map(::row), others.map(::row), canAdd = false, sameName = emptyList())
    }

    /** People sharing a normalized name with someone else: these rows need a disambiguator. */
    fun homonyms(doc: PeopleDoc, fold: (String) -> String = { it }): Set<String> =
        doc.people.groupBy { PersonNames.normalize(it.name, fold) }.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()
}

/** Ask: the people a question names ("what did Mark say"), by name or alias. Retrieval only, no prompt change. */
object PeopleRouting {
    private val LATIN = Regex("[\\p{IsLatin}\\p{Nd}]")

    fun mentioned(question: String, doc: PeopleDoc, fold: (String) -> String = { it }): Set<String> {
        val q = PersonNames.normalize(question, fold)
        if (q.isEmpty()) return emptySet()
        return doc.people.filter { p ->
            (listOf(p.name) + p.aliases).any { n ->
                val k = PersonNames.normalize(n, fold)
                when {
                    k.isEmpty() -> false
                    // Latin names: a whole word ("Mark" must not match "market").
                    LATIN.containsMatchIn(k) -> Regex("(?<![\\p{L}\\p{Nd}])" + Regex.escape(k) + "(?![\\p{L}\\p{Nd}])").containsMatchIn(q)
                    // Han names: two characters at least (one character is a word, not a name).
                    else -> k.length >= 2 && q.contains(k)
                }
            }
        }.map { it.id }.toSet()
    }

    /** Meeting → its speakers linked to one of [people] (the meetings Ask routes first). */
    fun meetingsOf(links: Map<String, Map<Int, SpeakerLink>>, people: Set<String>): Map<String, Set<Int>> =
        if (people.isEmpty()) emptyMap()
        else links.mapNotNull { (id, m) -> m.filterValues { it.person in people }.keys.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
}

/** Phase 2: a person the voice of a speaker resembles. [level] 3 = likely (●●●), 2 = possible (●●○). */
data class VoiceSuggestion(val person: String, val level: Int, val score: Float)
