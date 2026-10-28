package studio.primeminutes.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.people.EmailRules
import studio.primeminutes.core.people.PeopleOps
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.people.PersonEmail
import studio.primeminutes.core.people.PickedContact
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.primeMinutesTextFieldColors
import studio.primeminutes.ui.theme.Spacing

/**
 * A person's contact side on the person page: people are OS contacts, so this shows the linked
 * contact and its addresses (read from it, the first used when sending) and opens the Contacts app to
 * edit them. A person whose contact was deleted stays (name, meetings, voice), unlinked.
 */
@Composable
internal fun PersonContactCards(p: Person, onMergeInto: (Person) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val doc by PeopleStore.doc.collectAsState()
    val c = p.contact
    val linked = studio.primeminutes.core.people.ContactLinks.isLinked(p)
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ContactPage, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(stringResource(R.string.contact_card_title), color = pal.Slate200, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        linked -> stringResource(R.string.contact_in_contacts, c?.name ?: p.name)
                        c != null && c.platform == "android" && c.missing -> stringResource(R.string.contact_deleted)
                        else -> stringResource(R.string.contact_not_in_contacts)
                    },
                    color = pal.Slate400, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (linked && c != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                val i = studio.primeminutes.core.people.ContactsProvider.editIntent(c)
                try { if (i != null) context.startActivity(i) } catch (_: android.content.ActivityNotFoundException) {
                    studio.primeminutes.ui.AppSnackbar.show(context, R.string.contacts_no_app)
                }
            }, modifier = Modifier.testTag("contact-edit")) { Text(stringResource(R.string.contact_edit)) }
        }
        if (p.me) MeContactActions(p)
        PeopleOps.sameContact(doc, p).firstOrNull()?.let { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.contact_also_linked, o.name), color = pal.WarningText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onMergeInto(o) }) { Text(stringResource(R.string.contact_merge)) }
            }
        }
    }
    Spacer(Modifier.padding(top = Spacing.sm))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Email, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.email_card_title), color = pal.Slate200, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = Spacing.md))
        }
        if (p.me) Text(stringResource(R.string.email_me_hint), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        // Linked: the addresses are the contact's, added and removed here in place (the rest of the
        // contact is edited in the Contacts app).
        p.emails.forEachIndexed { i, e ->
            EmailRow(e, isDefault = i == 0, showDefault = p.emails.size > 1, onRemove = if (linked) ({ removing = e.address }) else null)
        }
        if (p.emails.isEmpty()) Text(stringResource(R.string.email_none),
            color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        if (linked) TextButton(onClick = { adding = true }, modifier = Modifier.testTag("email-add")) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.email_add), modifier = Modifier.padding(start = 6.dp))
        }
    }
    if (adding) AddressDialog(existing = p.emails.map { it.address }) { a ->
        adding = false
        if (a != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { PeopleStore.addEmailToContact(context, p.id, a) }
            if (!ok) studio.primeminutes.ui.AppSnackbar.show(context, R.string.email_write_failed)
        }
    }
    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            text = { Text(stringResource(R.string.email_remove_title, a)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { PeopleStore.removeEmailFromContact(context, p.id, a) }
                        if (!ok) studio.primeminutes.ui.AppSnackbar.show(context, R.string.email_write_failed)
                    }
                }, modifier = Modifier.testTag("email-remove-confirm")) { Text(stringResource(R.string.email_remove), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(pal.PanelSurface).border(1.dp, pal.Hairline, shape).padding(14.dp)) { content() }
}

@Composable
private fun EmailRow(e: PersonEmail, isDefault: Boolean, showDefault: Boolean, onRemove: (() -> Unit)? = null) {
    val pal = LocalPrimeMinutesPalette.current
    HorizontalDivider(color = pal.Hairline, modifier = Modifier.padding(top = Spacing.sm))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.address, color = pal.Slate200, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isDefault && showDefault) Text(stringResource(R.string.email_default), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
        }
        // The line above already reads 寄信時預設使用; a label here made TalkBack say it twice.
        if (isDefault && showDefault) Icon(Icons.Filled.Star, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(20.dp))
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.testTag("email-remove")) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.email_remove_cd, e.address), tint = pal.Slate400, modifier = Modifier.size(20.dp))
        }
    }
}

/** One address field, validated as typed (full-width characters accepted); [onDone] gets it, or null. */
@Composable
internal fun AddressDialog(title: String = stringResource(R.string.email_add), existing: List<String> = emptyList(), onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    val check = studio.primeminutes.core.people.ContactEmailOps.check(existing, text)
    val ok = check is studio.primeminutes.core.people.ContactEmailOps.Check.Ok
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, colors = primeMinutesTextFieldColors(),
                placeholder = { Text("name@example.com") },
                isError = text.isNotBlank() && !ok,
                supportingText = if (text.isNotBlank() && !ok) ({
                    Text(stringResource(if (check is studio.primeminutes.core.people.ContactEmailOps.Check.Duplicate) R.string.email_duplicate else R.string.email_invalid))
                }) else null,
                // Done = the save button once the address validates; the keyboard's return key was a
                // dead Enter (singleLine, no action wired).
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onDone(EmailRules.normalize(text)) }),
                modifier = Modifier.testTag("email-field"))
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onDone(EmailRules.normalize(text)) }) { Text(stringResource(R.string.people_save)) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Right after a speaker is named as a new person: an optional address for the new contact. [onDone]
 * gets the address (null: Skip), [onEditInContacts] creates the contact and opens its editor instead.
 */
@Composable
internal fun NewPersonEmailDialog(name: String, onDone: (String?) -> Unit, onEditInContacts: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    var text by remember { mutableStateOf("") }
    val ok = EmailRules.isValid(text)
    AlertDialog(
        // Dismissing keeps the name (it was chosen): the same as Skip.
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.email_new_title, name)) },
        text = {
            Column {
                Text(stringResource(R.string.email_new_hint), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, singleLine = true, colors = primeMinutesTextFieldColors(),
                    placeholder = { Text("name@example.com") },
                    isError = text.isNotBlank() && !ok,
                    supportingText = if (text.isNotBlank() && !ok) ({ Text(stringResource(R.string.email_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onDone(EmailRules.normalize(text)) }),
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).testTag("new-person-email"))
                TextButton(onClick = onEditInContacts, modifier = Modifier.testTag("new-person-edit-contact")) {
                    Text(stringResource(R.string.contact_edit))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onDone(EmailRules.normalize(text)) }, modifier = Modifier.testTag("new-person-email-save")) {
                Text(stringResource(R.string.people_save))
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }, modifier = Modifier.testTag("new-person-email-skip")) { Text(stringResource(R.string.email_skip)) } },
    )
}
