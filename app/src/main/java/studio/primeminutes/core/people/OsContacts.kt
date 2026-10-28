package studio.primeminutes.core.people

/**
 * People are OS contacts (docs/design/os-contacts.md): every person the user names is an entry of
 * the phone's address book; the app keeps only the link (lookup key + contact id) and its own data
 * (voice, meetings). Names and addresses are read back from the contact. This file is the pure part
 * (no Android): the provider side is [ContactsProvider].
 */
data class OsContact(
    /** CONTACT_ID: changes when the provider re-aggregates; the lookup key survives that. */
    val id: Long,
    val key: String,
    val name: String,
    val emails: List<String> = emptyList(),
)

/** How a person's stored link stands against the address book. */
sealed interface LinkState {
    /** Found (possibly under a new id or key): [contact] is what the address book has now. */
    data class Linked(val contact: OsContact) : LinkState
    /** The contact was deleted: the person keeps its name, without addresses. */
    data object Missing : LinkState
    /** No Android link at all (an app-only person, or a link made on iOS). */
    data object None : LinkState
}

object ContactLinks {
    /** True when [p] is backed by a live Android contact (the only "people" the pickers list). */
    fun isLinked(p: Person): Boolean = p.contact?.let { it.platform == "android" && !it.missing } == true

    /**
     * Resolves [link] the way `Contacts.lookupContact` does: the lookup key first (it survives an id
     * change after a sync or a join), then the id as a fallback when the key is unknown (a key can be
     * rewritten by a re-aggregation that the id survived). [byKey] / [byId] query the address book.
     */
    fun resolve(link: ContactLink?, byKey: (String) -> OsContact?, byId: (Long) -> OsContact?): LinkState {
        if (link == null || link.platform != "android") return LinkState.None
        val c = byKey(link.key) ?: link.id?.let(byId)
        return if (c == null) LinkState.Missing else LinkState.Linked(c)
    }

    /**
     * Applies a resolution to person [id]: on [LinkState.Linked] the link is refreshed (id, key, name),
     * the name follows the contact's, and the addresses become the contact's (the previous default
     * stays first when the contact still has it); on [LinkState.Missing] the link is kept but marked
     * missing and the contact's addresses go — the person, its name and its meetings stay.
     */
    fun apply(doc: PeopleDoc, id: String, state: LinkState, now: Long): PeopleDoc = when (state) {
        LinkState.None -> doc
        LinkState.Missing -> doc.copy(people = doc.people.map { p ->
            if (p.id != id || p.contact == null || p.contact.missing) p
            else p.copy(contact = p.contact.copy(missing = true, synced = now), emails = p.emails.filter { it.from != PersonEmail.FROM_CONTACT })
        })
        is LinkState.Linked -> doc.copy(people = doc.people.map { p ->
            if (p.id != id) p else {
                val c = state.contact
                val valid = c.emails.map(EmailRules::normalize).filter(EmailRules::isValid).distinctBy(EmailRules::key)
                val def = p.emails.firstOrNull()?.let { EmailRules.key(it.address) }
                val ordered = valid.sortedByDescending { EmailRules.key(it) == def }
                val name = c.name.trim().ifBlank { p.name }
                val link = (p.contact ?: ContactLink("android", c.key)).copy(
                    platform = "android", key = c.key, id = c.id, name = name, missing = false, synced = now,
                    linked = p.contact?.linked?.takeIf { it > 0 } ?: now,
                )
                p.copy(name = name, contact = link, emails = ordered.map { PersonEmail(it, PersonEmail.FROM_CONTACT) })
            }
        })
    }
}

/**
 * "Add N people to Contacts?" (the one-time card): the app-only people of the old directory become
 * contacts. A person whose address or exact name is already in the address book is linked to that
 * contact instead of creating a duplicate.
 */
object ContactMigration {
    sealed interface Step {
        val person: Person
        /** Link to an existing contact; [missingEmails] are the person's addresses the contact lacks. */
        data class Match(override val person: Person, val contact: OsContact, val missingEmails: List<String>) : Step
        /** Create a contact named after the person, with its addresses. */
        data class Create(override val person: Person) : Step
    }

    /** People to offer: no Android link (never "Me", the phone's owner, nor a deleted contact's person). */
    fun candidates(doc: PeopleDoc): List<Person> = doc.people.filter { p -> !p.me && (p.contact == null || p.contact.platform != "android") }

    /**
     * The person other than [personId] already linked to [contact] (same lookup key or id), or null.
     * During the migration that person IS the one being migrated (same human named twice): the
     * migrated person is merged into it instead of becoming a second person with the same name.
     */
    fun mergeTarget(doc: PeopleDoc, personId: String, contact: OsContact): Person? =
        doc.people.firstOrNull { it.id != personId && ContactLinks.isLinked(it) && (it.contact!!.key == contact.key || it.contact.id == contact.id) }

    fun plan(people: List<Person>, contacts: List<OsContact>, fold: (String) -> String = { it }): List<Step> {
        val byEmail = HashMap<String, OsContact>()
        for (c in contacts) for (e in c.emails) byEmail.putIfAbsent(EmailRules.key(e), c)
        return people.map { p ->
            val hit = p.emails.firstNotNullOfOrNull { byEmail[EmailRules.key(it.address)] }
                ?: contacts.filter { PersonNames.normalize(it.name, fold).isNotEmpty() && PersonNames.same(it.name, p.name, fold) }
                    // Several contacts with that exact name: ambiguous, so no guess (a new contact).
                    .singleOrNull()
            if (hit == null) Step.Create(p) else {
                val have = hit.emails.map(EmailRules::key).toSet()
                Step.Match(p, hit, p.emails.map { it.address }.filter { EmailRules.key(it) !in have })
            }
        }
    }
}
