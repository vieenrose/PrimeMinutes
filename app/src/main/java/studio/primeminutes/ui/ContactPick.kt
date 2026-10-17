package studio.primeminutes.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.people.EmailRules
import studio.primeminutes.core.people.PickedContact

/**
 * The system contact picker on e-mail rows (docs/design/speaker-contacts.md §3.1): NO contacts
 * permission. `ACTION_PICK` on `Email.CONTENT_TYPE` lists only contacts with an address, and the
 * returned row comes with a temporary read grant for that row alone; Android 17 routes it to its new
 * contact picker. Only what the user tapped is read, at once, and nothing else of the address book.
 */
private class PickEmailRow : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Email.CONTENT_TYPE)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private const val TAG = "primeminutes-contacts"

/** The picked row: its name, the tapped address, the contact's stable key (+ the contact's other
 *  addresses and organisation when the grant covers them; never asked for otherwise). */
internal fun readPickedEmail(context: Context, uri: Uri): Pair<PickedContact, String>? {
    val cr = context.contentResolver
    val e = ContactsContract.CommonDataKinds.Email.ADDRESS
    val proj = arrayOf(e, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.LOOKUP_KEY, ContactsContract.Data.CONTACT_ID)
    val row = runCatching {
        cr.query(uri, proj, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else listOf(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3).toString())
        }
    }.onFailure { Log.w(TAG, "picked row unreadable", it) }.getOrNull() ?: return null
    val address = row[0]?.let(EmailRules::normalize)?.takeIf(EmailRules::isValid) ?: return null
    val contactId = row[3]?.toLongOrNull()
    // Best effort: these rows may lie outside the one-row grant (then: just the tapped address).
    val others = contactId?.let { id ->
        runCatching {
            cr.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(e), "${ContactsContract.Data.CONTACT_ID}=?", arrayOf(id.toString()), null)
                ?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let { add(EmailRules.normalize(it)) } } }
        }.getOrNull()
    }.orEmpty().filter(EmailRules::isValid)
    val org = contactId?.let { id ->
        runCatching {
            cr.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(id.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE), null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }?.trim()?.ifBlank { null }
    Log.i(TAG, "picked: ${1 + others.size} address(es) visible, org ${if (org != null) "yes" else "no"}")
    return PickedContact(row[1]?.trim()?.ifBlank { null }, (listOf(address) + others).distinctBy(EmailRules::key), row[2], contactId, org) to address
}

/**
 * Remembers a launcher for the picker; [onPicked] gets the contact and the address the user tapped
 * (on the main thread), nothing when they backed out. A device without a contacts app says so.
 */
@Composable
fun rememberContactPicker(onPicked: (PickedContact, String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(PickEmailRow()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { readPickedEmail(context, uri) }
            if (r == null) Toast.makeText(context, R.string.contacts_pick_failed, Toast.LENGTH_LONG).show()
            else onPicked(r.first, r.second)
        }
    }
    return {
        try { launcher.launch(Unit) } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.contacts_no_app, Toast.LENGTH_LONG).show()
        }
    }
}
