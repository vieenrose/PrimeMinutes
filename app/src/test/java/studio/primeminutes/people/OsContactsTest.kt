package studio.primeminutes.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.people.ContactLink
import studio.primeminutes.core.people.ContactLinks
import studio.primeminutes.core.people.ContactMigration
import studio.primeminutes.core.people.LinkState
import studio.primeminutes.core.people.OsContact
import studio.primeminutes.core.people.PeopleDoc
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.people.PersonEmail

/** People == OS contacts: link resolution, sync, and the "Add N people to Contacts" matching. */
class OsContactsTest {
    private val ann = OsContact(10, "k-ann", "Ann Lee", listOf("ann@acme.com"))
    private val bob = OsContact(11, "k-bob", "Bob", emptyList())

    @Test fun resolvesByKeyFirstThenId() {
        val moved = ann.copy(id = 99)
        val byKey = { k: String -> if (k == "k-ann") moved else null }
        val byId = { i: Long -> if (i == 11L) bob else null }
        assertEquals(LinkState.Linked(moved), ContactLinks.resolve(ContactLink("android", "k-ann", 10), byKey, byId))
        // Key rewritten by a re-aggregation, id still valid.
        assertEquals(LinkState.Linked(bob), ContactLinks.resolve(ContactLink("android", "stale", 11), byKey, byId))
        assertEquals(LinkState.Missing, ContactLinks.resolve(ContactLink("android", "gone", 5), byKey, byId))
        assertEquals(LinkState.None, ContactLinks.resolve(null, byKey, byId))
        assertEquals(LinkState.None, ContactLinks.resolve(ContactLink("ios", "x"), byKey, byId))
    }

    @Test fun linkedFollowsContactNameAndAddresses() {
        val p = Person("p1", "Ann", emails = listOf(PersonEmail("ann@acme.com"), PersonEmail("old@x.org")),
            contact = ContactLink("android", "k-ann", 10, linked = 5))
        val c = ann.copy(id = 12, key = "k-ann2", emails = listOf("work@acme.com", "ann@ACME.com"))
        val d = ContactLinks.apply(PeopleDoc(listOf(p)), "p1", LinkState.Linked(c), 100).people[0]
        assertEquals("Ann Lee", d.name)
        assertEquals(12L, d.contact!!.id); assertEquals("k-ann2", d.contact!!.key); assertEquals(5L, d.contact!!.linked)
        // Previous default kept first; addresses are the contact's only.
        assertEquals(listOf("ann@ACME.com", "work@acme.com"), d.emails.map { it.address })
        assertTrue(d.emails.all { it.from == PersonEmail.FROM_CONTACT })
        assertTrue(ContactLinks.isLinked(d))
    }

    @Test fun deletedContactUnlinksGracefully() {
        val p = Person("p1", "Ann", emails = listOf(PersonEmail("a@b.co", PersonEmail.FROM_CONTACT)), contact = ContactLink("android", "k", 1))
        val d = ContactLinks.apply(PeopleDoc(listOf(p)), "p1", LinkState.Missing, 100).people[0]
        assertEquals("Ann", d.name)
        assertTrue(d.contact!!.missing)
        assertTrue(d.emails.isEmpty())
        assertFalse(ContactLinks.isLinked(d))
        // Not offered again by the migration card.
        assertTrue(ContactMigration.candidates(PeopleDoc(listOf(d))).isEmpty())
        // The contact comes back (undo in Contacts): relinked.
        assertTrue(ContactLinks.isLinked(ContactLinks.apply(PeopleDoc(listOf(d)), "p1", LinkState.Linked(OsContact(1, "k", "Ann")), 200).people[0]))
    }

    @Test fun migrationCandidates() {
        val doc = PeopleDoc(listOf(
            Person("a", "A"), Person("me", "Me", me = true),
            Person("l", "L", contact = ContactLink("android", "k")), Person("i", "I", contact = ContactLink("ios", "k")),
        ))
        assertEquals(listOf("a", "i"), ContactMigration.candidates(doc).map { it.id })
    }

    @Test fun migrationMatchesByEmailThenExactName() {
        val people = listOf(
            Person("1", "Annie", emails = listOf(PersonEmail("ann@ACME.com"))),   // email match despite another name
            Person("2", "bob"),            // exact name, case-folded by PersonNames
            Person("3", "Carol", emails = listOf(PersonEmail("c@x.org"))),
            Person("4", "Bob Smith"),      // no prefix matching
        )
        val plan = ContactMigration.plan(people, listOf(ann, bob))
        val m1 = plan[0] as ContactMigration.Step.Match
        assertEquals(ann, m1.contact); assertTrue(m1.missingEmails.isEmpty())
        val m2 = plan[1] as ContactMigration.Step.Match
        assertEquals(bob, m2.contact)
        assertTrue(plan[2] is ContactMigration.Step.Create)
        assertTrue(plan[3] is ContactMigration.Step.Create)
    }

    @Test fun migrationNameMatchCarriesMissingAddresses() {
        val p = Person("1", "Bob", emails = listOf(PersonEmail("bob@x.org")))
        val m = ContactMigration.plan(listOf(p), listOf(bob))[0] as ContactMigration.Step.Match
        assertEquals(listOf("bob@x.org"), m.missingEmails)
    }

    @Test fun ambiguousNameCreatesInsteadOfGuessing() {
        val plan = ContactMigration.plan(listOf(Person("1", "Bob")), listOf(bob, bob.copy(id = 12, key = "k-bob2")))
        assertTrue(plan[0] is ContactMigration.Step.Create)
    }

    @Test fun hanScriptFoldMatchesNames() {
        val fold: (String) -> String = { it.replace("陳", "陈") }
        val plan = ContactMigration.plan(listOf(Person("1", "陳大文")), listOf(OsContact(3, "k3", "陈大文")), fold)
        assertTrue(plan[0] is ContactMigration.Step.Match)
    }
}
