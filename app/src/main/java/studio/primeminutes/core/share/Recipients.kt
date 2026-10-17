package studio.primeminutes.core.share

import studio.primeminutes.core.people.EmailRules
import studio.primeminutes.core.people.Person

/**
 * Who the participants e-mail goes to (docs/design/speaker-contacts.md §4.2), pure so both platforms
 * agree: one row per person (two speakers linked to the same person give one row), in speaker order;
 * the person's default address; pre-checked = every person with an address except Me (everyone when
 * no one is Me yet) — or, when this meeting was shared before, the people it went to last time.
 * Never "all contacts", no select-all: only the meeting's own people (App Store 5.1.2(v)).
 */
object Recipients {
    data class Row(val person: Person, val speakers: List<Int>, val address: String?, val checked: Boolean)

    /** A speaker that cannot be a recipient yet: unnamed, or a plain label not linked to a person. */
    data class Loose(val speaker: Int, val label: String?)

    fun rows(people: Map<Int, Person>, anyMe: Boolean, last: ShareHistory.Share? = null): List<Row> {
        val bySpeaker = people.toSortedMap()
        val seen = LinkedHashMap<String, Pair<Person, MutableList<Int>>>()
        for ((sid, p) in bySpeaker) seen.getOrPut(p.id) { p to ArrayList() }.second += sid
        val lastPeople = last?.recipients?.mapNotNull { it.person }?.toSet()
        val lastAddr = last?.recipients?.map { EmailRules.key(it.address) }?.toSet()
        return seen.values.map { (p, sids) ->
            val a = p.emails.firstOrNull()?.address
            val checked = when {
                a == null -> false
                last != null -> p.id in lastPeople.orEmpty() || EmailRules.key(a) in lastAddr.orEmpty()
                else -> !(anyMe && p.me)
            }
            Row(p, sids, a, checked)
        }
    }

    /** Speakers of the transcript with no person: unnamed ([Loose.label] null) or a plain label. */
    fun loose(speakers: Collection<Int>, names: Map<Int, String>, people: Map<Int, Person>): List<Loose> =
        speakers.distinct().sorted().filter { it !in people }.map { Loose(it, names[it]?.takeIf { n -> n.isNotBlank() }) }

    /** The To line: the checked addresses plus one-off ones, deduplicated by address (a shared mailbox is listed once). */
    fun to(rows: List<Row>, extra: List<String>): List<String> =
        (rows.filter { it.checked }.mapNotNull { it.address } + extra).distinctBy(EmailRules::key)
}
