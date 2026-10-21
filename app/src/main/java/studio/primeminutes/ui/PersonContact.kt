package studio.primeminutes.ui

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
import studio.primeminutes.R
import studio.primeminutes.core.people.EmailRules
import studio.primeminutes.core.people.PeopleOps
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.people.PersonEmail
import studio.primeminutes.core.people.PickedContact
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.primeMinutesTextFieldColors

/**
 * A person's contact side on the person page (docs/design/speaker-contacts.md §2, mockup 01): the
 * address-book link (picked through the system picker, no permission) and the e-mail addresses, the
 * first one (★) used when sending. The person's name is never changed by a contact.
 */
@Composable
internal fun PersonContactCards(p: Person, onMergeInto: (Person) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf(false) }
    var replaceAsk by remember { mutableStateOf<Pair<PickedContact, String>?>(null) }
    val doc by PeopleStore.doc.collectAsState()
    val pick = rememberContactPicker { c, chosen ->
        val old = p.contact
        if (old != null && old.platform == c.platform && old.key != c.key) replaceAsk = c to chosen
        else scope.launch(Dispatchers.IO) { PeopleStore.linkContact(context, p.id, c, chosen) }
    }
    val dateFmt = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, studio.primeminutes.core.config.AppLanguage.uiLocale(context)) }

    // --- the contact link ---
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ContactPage, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.contact_card_title), color = pal.Slate200, fontWeight = FontWeight.Bold)
                val c = p.contact
                Text(
                    when {
                        c == null -> stringResource(R.string.contact_not_linked)
                        c.platform != "android" -> stringResource(R.string.contact_other_platform)
                        else -> stringResource(R.string.contact_linked_to,
                            listOfNotNull(c.name, c.org?.let { "($it)" }).joinToString(" ").ifBlank { "—" },
                            dateFmt.format(java.util.Date(c.synced * 1000)))
                    },
                    color = pal.Slate400, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = pick, modifier = Modifier.testTag("contact-pick")) {
                Text(stringResource(if (p.contact == null) R.string.contact_choose else R.string.contact_update))
            }
            if (p.contact != null) TextButton(onClick = { unlinking = true }) { Text(stringResource(R.string.contact_unlink), color = pal.Slate400) }
        }
        PeopleOps.sameContact(doc, p).firstOrNull()?.let { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.contact_also_linked, o.name), color = pal.WarningText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onMergeInto(o) }) { Text(stringResource(R.string.contact_merge)) }
            }
        }
    }
    Spacer(Modifier.padding(top = 8.dp))
    // --- the addresses ---
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Email, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.email_card_title), color = pal.Slate200, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        }
        if (p.me) Text(stringResource(R.string.email_me_hint), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        p.emails.forEachIndexed { i, e -> EmailRow(p, e, isDefault = i == 0, showDefault = p.emails.size > 1) }
        Row(Modifier.fillMaxWidth().clickable { adding = true }.heightIn(min = 48.dp).padding(vertical = 8.dp).testTag("email-add"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = pal.Sky)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.email_add), color = pal.Sky, fontWeight = FontWeight.SemiBold)
        }
    }

    if (adding) AddressDialog(onDone = { a ->
        adding = false
        if (a != null) scope.launch(Dispatchers.IO) { PeopleStore.addEmail(context, p.id, a) }
    })
    if (unlinking) AlertDialog(
        onDismissRequest = { unlinking = false },
        title = { Text(stringResource(R.string.contact_unlink_title, p.name)) },
        text = { Text(stringResource(R.string.contact_unlink_text)) },
        confirmButton = {
            TextButton(onClick = { unlinking = false; scope.launch(Dispatchers.IO) { PeopleStore.unlinkContact(context, p.id, false) } }) {
                Text(stringResource(R.string.contact_unlink))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { unlinking = false }) { Text(stringResource(R.string.cancel)) }
                if (p.emails.any { it.from == PersonEmail.FROM_CONTACT }) TextButton(onClick = {
                    unlinking = false; scope.launch(Dispatchers.IO) { PeopleStore.unlinkContact(context, p.id, true) }
                }) { Text(stringResource(R.string.contact_unlink_remove), color = pal.WarningText) }
            }
        },
    )
    replaceAsk?.let { (c, chosen) ->
        AlertDialog(
            onDismissRequest = { replaceAsk = null },
            text = { Text(stringResource(R.string.contact_replace, p.contact?.name ?: "—")) },
            confirmButton = {
                TextButton(onClick = { replaceAsk = null; scope.launch(Dispatchers.IO) { PeopleStore.linkContact(context, p.id, c, chosen) } }) {
                    Text(stringResource(R.string.contact_replace_yes))
                }
            },
            dismissButton = { TextButton(onClick = { replaceAsk = null }) { Text(stringResource(R.string.cancel)) } },
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
private fun EmailRow(p: Person, e: PersonEmail, isDefault: Boolean, showDefault: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    HorizontalDivider(color = pal.Hairline, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.address, color = pal.Slate200, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                stringResource(if (e.from == PersonEmail.FROM_CONTACT) R.string.email_from_contact else R.string.email_from_user),
                if (isDefault && showDefault) stringResource(R.string.email_default) else null,
            ).joinToString(" · ")
            Text(sub, color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
        }
        // The line above already reads 寄信時預設使用; a label here made TalkBack say it twice.
        if (isDefault && showDefault) Icon(Icons.Filled.Star, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(20.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options), tint = pal.Slate400) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!isDefault) DropdownMenuItem(text = { Text(stringResource(R.string.email_make_default)) }, onClick = {
                    menu = false; scope.launch(Dispatchers.IO) { PeopleStore.makeDefaultEmail(context, p.id, e.address) }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.email_remove), color = pal.WarningText) }, onClick = {
                    menu = false; scope.launch(Dispatchers.IO) { PeopleStore.removeEmail(context, p.id, e.address) }
                })
            }
        }
    }
}

/** One address field, validated as typed (full-width characters accepted); [onDone] gets it, or null. */
@Composable
internal fun AddressDialog(title: String = stringResource(R.string.email_add), onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ok = EmailRules.isValid(text)
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, colors = primeMinutesTextFieldColors(),
                placeholder = { Text("name@example.com") },
                isError = text.isNotBlank() && !ok,
                supportingText = if (text.isNotBlank() && !ok) ({ Text(stringResource(R.string.email_invalid)) }) else null,
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
