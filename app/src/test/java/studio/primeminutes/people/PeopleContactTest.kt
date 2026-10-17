package studio.primeminutes.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.people.ContactLink
import studio.primeminutes.core.people.EmailRules
import studio.primeminutes.core.people.PeopleCodec
import studio.primeminutes.core.people.PeopleDoc
import studio.primeminutes.core.people.PeopleOps
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.people.PersonEmail
import studio.primeminutes.core.people.PickedContact
import studio.primeminutes.core.share.ShareHistory

/** A person's contact side (docs/design/speaker-contacts.md §2, §7): addresses, the contact link, the rules. */
class PeopleContactTest {
    @Test fun addressRules() {
        assertTrue(EmailRules.isValid("yating.lin@example.com"))
        // A Chinese IME's full-width ＠ and letters are NFKC-folded.
        assertEquals("ab@example.com", EmailRules.normalize(" ａｂ＠example.com "))
        assertTrue(EmailRules.isValid("ａｂ＠example.com"))
        for (bad in listOf("", "a", "a@b", "a@b.c", "a b@c.com", "a@@c.com", "@c.com", "a@.com", "a@c..com", "a@c.com.", "x".repeat(65) + "@c.com"))
            assertFalse(bad, EmailRules.isValid(bad))
        assertEquals("Mark@acme.com", EmailRules.key("Mark@ACME.com"))
    }

    @Test fun codecKeepsEmailsContactAndUnknownKeys() {
        val text = """{"version":1,"people":[{"id":"a","name":"林雅婷","emails":[{"address":"y@x.com","from":"contact"},"home@x.org","y@X.COM"],
            "contact":{"platform":"ios","key":"K1","name":"林雅婷","org":"總務部","linked":5,"synced":6,"future":1},"later":{"k":2}}]}"""
        val d = PeopleCodec.decode(text)
        val p = d.people.single()
        assertEquals(listOf(PersonEmail("y@x.com", "contact"), PersonEmail("home@x.org", "user")), p.emails)   // duplicate by key dropped
        assertEquals("ios", p.contact!!.platform); assertEquals("總務部", p.contact!!.org); assertNull(p.contact!!.id)
        val again = PeopleCodec.decode(PeopleCodec.encode(d))
        assertEquals(d, again)
        val json = PeopleCodec.encode(d)
        assertTrue(json.contains("\"future\":1")); assertTrue(json.contains("\"later\""))
    }

    private fun doc(vararg p: Person) = PeopleDoc(p.toList())

    @Test fun addressEdits() {
        var d = doc(Person("a", "Mark"))
        d = PeopleOps.addEmail(d, "a", "mark@acme.com")
        d = PeopleOps.addEmail(d, "a", "mark@ACME.com")   // same key: no second copy
        d = PeopleOps.addEmail(d, "a", "m@home.org")
        d = PeopleOps.addEmail(d, "a", "not an address")
        assertEquals(listOf("mark@acme.com", "m@home.org"), d.byId("a")!!.emails.map { it.address })
        d = PeopleOps.makeDefault(d, "a", "m@home.org")
        assertEquals("m@home.org", d.byId("a")!!.emails.first().address)
        d = PeopleOps.removeEmail(d, "a", "m@HOME.org")
        assertEquals(listOf("mark@acme.com"), d.byId("a")!!.emails.map { it.address })
    }

    @Test fun linkUnlinkAndMerge() {
        val c = PickedContact("Mark Chen 陳志明", listOf("mark@acme.com", "mark@home.org"), "LK1", 12L, org = "ACME")
        var d = doc(Person("a", "Mark", emails = listOf(PersonEmail("typed@x.com"))), Person("b", "Marcus"))
        d = PeopleOps.linkContact(d, "a", c, now = 100, chosen = "mark@home.org")
        val a = d.byId("a")!!
        assertEquals("Mark", a.name)   // never renamed by the contact
        assertEquals("ACME", a.note)   // the organisation fills an empty note
        assertEquals(listOf("typed@x.com", "mark@home.org", "mark@acme.com"), a.emails.map { it.address })
        assertEquals(ContactLink("android", "LK1", 12L, "Mark Chen 陳志明", "ACME", 100, 100), a.contact)
        // Same contact on another person: flagged.
        d = PeopleOps.linkContact(d, "b", c, now = 101)
        assertEquals(listOf("a"), PeopleOps.sameContact(d, d.byId("b")!!).map { it.id })
        // Merge b into a: union of addresses (a's first), a keeps its link.
        d = PeopleOps.addEmail(d, "b", "marcus@x.com")
        d = PeopleOps.merge(d, "b", "a")
        assertEquals(listOf("typed@x.com", "mark@home.org", "mark@acme.com", "marcus@x.com"), d.byId("a")!!.emails.map { it.address })
        assertEquals(100L, d.byId("a")!!.contact!!.linked)
        // Unlink keeps the addresses as the user's, or removes those from the contact.
        val kept = PeopleOps.unlinkContact(d, "a", removeEmails = false).byId("a")!!
        assertNull(kept.contact); assertTrue(kept.emails.all { it.from == PersonEmail.FROM_USER })
        val removed = PeopleOps.unlinkContact(d, "a", removeEmails = true).byId("a")!!
        assertEquals(listOf("typed@x.com", "marcus@x.com"), removed.emails.map { it.address })
    }

    @Test fun shareHistoryRoundTripAndScrub() {
        val s = ShareHistory.Share(1792226400, "email", "opened",
            listOf(ShareHistory.Recipient("p1", "yating.lin@example.com"), ShareHistory.Recipient(null, "guest@Example.com")),
            ShareHistory.Content(true, true, null, listOf("pdf")), "zh-Hant")
        val back = ShareHistory.decode(ShareHistory.encode(listOf(s)))
        assertEquals(listOf(s), back)
        val scrubbed = ShareHistory.scrub(back, "p9", setOf(EmailRules.key("guest@example.com")))
        assertEquals(listOf("yating.lin@example.com"), scrubbed.single().recipients.map { it.address })
        var many = emptyList<ShareHistory.Share>()
        repeat(12) { many = ShareHistory.add(many, s.copy(at = it.toLong())) }
        assertEquals(ShareHistory.KEEP, many.size); assertEquals(2L, many.first().at)
    }
}
