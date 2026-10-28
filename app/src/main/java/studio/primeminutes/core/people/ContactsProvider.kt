package studio.primeminutes.core.people

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The address-book side of [OsContact] (READ/WRITE_CONTACTS). New people are inserted directly with
 * a [ContentProviderOperation] batch rather than an `ACTION_INSERT` intent: naming a speaker stays
 * one tap inside the app (no round trip through the Contacts editor, which some vendors' apps do not
 * return from cleanly), and the "Add N people to Contacts" card can create many at once — the intent
 * would open one editor per person. The raw contact goes to the device's default (local) account;
 * the user edits it, moves it to an account or adds addresses in the Contacts app ([editIntent]).
 */
object ContactsProvider {
    private const val TAG = "primeminutes-contacts"

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    val PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

    /** Creates a contact named [name] with [emails]; null when the provider refused. */
    fun insert(context: Context, name: String, emails: List<String> = emptyList()): OsContact? = runCatching {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, name).build()
        for (e in emails) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            .withValue(Email.ADDRESS, e).withValue(Email.TYPE, Email.TYPE_WORK).build()
        val res = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = res[0].uri?.lastPathSegment?.toLongOrNull() ?: return@runCatching null
        val contactId = context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID}=?", arrayOf(rawId.toString()), null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            ?: return@runCatching null
        byId(context, contactId)
    }.onFailure { Log.w(TAG, "insert failed", it) }.getOrNull()

    /** Adds [addresses] to the first raw contact of [contactId] (the "add an address" path, the migration). */
    fun addEmails(context: Context, contactId: Long, addresses: List<String>): Boolean = runCatching {
        if (addresses.isEmpty()) return true
        val raw = context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(contactId.toString()), null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            ?: return false
        val ops = ArrayList(addresses.map { e ->
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw)
                .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, e).withValue(Email.TYPE, Email.TYPE_WORK).build()
        })
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    }.onFailure { Log.w(TAG, "addEmails failed", it) }.getOrDefault(false)

    private val CONTACT_PROJ = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

    private fun one(context: Context, uri: Uri, sel: String? = null, args: Array<String>? = null): OsContact? =
        context.contentResolver.query(uri, CONTACT_PROJ, sel, args, null)?.use { c ->
            if (!c.moveToFirst()) null else OsContact(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
        }?.let { it.copy(emails = emailsOf(context, it.id)) }

    fun byId(context: Context, id: Long): OsContact? = runCatching {
        one(context, ContactsContract.Contacts.CONTENT_URI, "${ContactsContract.Contacts._ID}=?", arrayOf(id.toString()))
    }.getOrNull()

    /** The contact behind a picker result ([ContactsContract.Contacts] uri, read under its grant). */
    fun fromUri(context: Context, uri: Uri): OsContact? = runCatching {
        context.contentResolver.query(uri, CONTACT_PROJ, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else OsContact(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
        }?.let { c -> c.copy(emails = runCatching { emailsOf(context, c.id) }.getOrDefault(emptyList())) }
    }.onFailure { Log.w(TAG, "picked contact unreadable", it) }.getOrNull()

    /** By lookup key alone (the provider follows a key across re-aggregations). */
    fun byKey(context: Context, key: String): OsContact? = runCatching {
        one(context, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(key)))
    }.getOrNull()

    private fun emailsOf(context: Context, contactId: Long): List<String> =
        context.contentResolver.query(Email.CONTENT_URI, arrayOf(Email.ADDRESS), "${Email.CONTACT_ID}=?", arrayOf(contactId.toString()),
            "${Email.IS_SUPER_PRIMARY} DESC, ${Email.IS_PRIMARY} DESC")
            ?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let { add(it) } } }.orEmpty()

    /**
     * The device owner's profile card ("Me" in the Contacts app), or null when there is none or the
     * platform hides it (Samsung One UI on the Note10+ returns no profile rows to third-party apps
     * even with READ_PROFILE granted): then Me stays app-only.
     */
    fun profile(context: Context): OsContact? = runCatching {
        val cr = context.contentResolver
        val c = cr.query(ContactsContract.Profile.CONTENT_URI, CONTACT_PROJ, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else OsContact(c.getLong(0), PROFILE_PREFIX + (c.getString(1) ?: ""), c.getString(2)?.trim().orEmpty())
        } ?: return@runCatching null
        if (c.name.isEmpty()) return@runCatching null
        val emails = cr.query(Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY),
            arrayOf(Email.ADDRESS), "${ContactsContract.Data.MIMETYPE}=?", arrayOf(Email.CONTENT_ITEM_TYPE), null)
            ?.use { e -> buildList { while (e.moveToNext()) e.getString(0)?.let { add(it) } } }.orEmpty()
        c.copy(emails = emails)
    }.onFailure { Log.w(TAG, "profile unreadable", it) }.getOrNull().also { Log.i(TAG, "profile card: ${if (it == null) "none" else "found"}") }

    /** Lookup keys of the profile card carry this prefix, so they never collide with a contact's. */
    const val PROFILE_PREFIX = "profile:"

    fun resolve(context: Context, link: ContactLink?): LinkState =
        ContactLinks.resolve(link, { byKey(context, it) }, { byId(context, it) })

    /** Every contact with a name (the picker's list, the migration's matching), addresses included. */
    fun all(context: Context): List<OsContact> = runCatching {
        val emails = HashMap<Long, MutableList<String>>()
        context.contentResolver.query(Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.ADDRESS), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(1)?.let { emails.getOrPut(c.getLong(0)) { ArrayList() } += it }
        }
        context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, CONTACT_PROJ, null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(2)?.trim().orEmpty()
                    if (name.isNotEmpty()) add(OsContact(c.getLong(0), c.getString(1) ?: "", name, emails[c.getLong(0)].orEmpty()))
                }
            }
        }.orEmpty()
    }.onFailure { Log.w(TAG, "listing contacts failed", it) }.getOrDefault(emptyList())

    /** "Edit in Contacts": the system editor on this contact (null when it cannot be resolved). */
    fun editIntent(link: ContactLink): Intent? {
        val id = link.id ?: return null
        val uri = if (link.key.startsWith(PROFILE_PREFIX)) ContactsContract.Profile.CONTENT_URI
            else ContactsContract.Contacts.getLookupUri(id, link.key)
        return Intent(Intent.ACTION_EDIT).setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra("finishActivityOnSaveCompleted", true)
    }

    /** Deletes a contact (tests' clean-up only; the app never deletes contacts). */
    fun delete(context: Context, link: ContactLink): Boolean = runCatching {
        val uri = ContactsContract.Contacts.getLookupUri(link.id ?: return false, link.key)
        context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
}
