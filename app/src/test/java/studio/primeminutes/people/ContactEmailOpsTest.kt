package studio.primeminutes.people

import android.provider.ContactsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.people.ContactEmailOps
import studio.primeminutes.core.people.ContactEmailOps.Check

/** The e-mail writes on an OS contact: add after naming / from the person page, remove from it. */
@RunWith(RobolectricTestRunner::class)
class ContactEmailOpsTest {
    @Test fun checkValidatesAndNormalizes() {
        assertEquals(Check.Ok("ann@acme.com"), ContactEmailOps.check(emptyList(), "  ann＠acme.com "))
        assertEquals(Check.Invalid, ContactEmailOps.check(emptyList(), "ann@acme"))
        assertEquals(Check.Invalid, ContactEmailOps.check(emptyList(), "ann acme.com"))
        assertEquals(Check.Invalid, ContactEmailOps.check(emptyList(), ""))
    }

    @Test fun checkRefusesAnAddressTheContactHas() {
        assertEquals(Check.Duplicate, ContactEmailOps.check(listOf("ann@ACME.com"), "ann@acme.com"))
        // The local part is case-sensitive: a different address.
        assertEquals(Check.Ok("Ann@acme.com"), ContactEmailOps.check(listOf("ann@acme.com"), "Ann@acme.com"))
    }

    @Test fun insertOpsAddOneEmailRowPerAddress() {
        val ops = ContactEmailOps.insertOps(42, listOf("a@x.org", "b@y.org"))
        assertEquals(2, ops.size)
        ops.forEach { assertTrue(it.isInsert); assertEquals(ContactsContract.Data.CONTENT_URI, it.uri) }
    }

    @Test fun removeTargetsOnlyMatchingRows() {
        val rows = listOf(1L to "ann@acme.com", 2L to "ann@ACME.COM", 3L to "other@acme.com")
        assertEquals(listOf(1L, 2L), ContactEmailOps.rowsToDelete(rows, "ann@acme.com"))
        assertEquals(emptyList<Long>(), ContactEmailOps.rowsToDelete(rows, "nobody@acme.com"))
        val ops = ContactEmailOps.deleteOps(listOf(1L, 2L))
        assertEquals(2, ops.size)
        assertTrue(ops.all { it.isDelete })
        assertEquals("1", ops[0].uri.lastPathSegment)
    }
}
