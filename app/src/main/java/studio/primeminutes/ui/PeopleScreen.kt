package studio.primeminutes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.people.PersonNames
import studio.primeminutes.core.people.SpeakerLink
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.primeMinutesTextFieldColors
import studio.primeminutes.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading

/** One meeting a person is linked in. */
private data class PersonMeeting(val entry: SessionLibrary.Entry, val speakers: Set<Int>)

/**
 * The People screen (docs/design/speaker-tags.md §2.2): every person of the directory with their
 * meetings; a person page renames everywhere, merges, splits one meeting off, deletes, and lists the
 * meetings (a tap opens the meeting at the person's first line). [voiceHeader] / [voiceOfPerson]:
 * the Phase 2 voice controls (opt-in switch, "forget voice").
 */
@Composable
fun PeopleScreen(
    onClose: () -> Unit,
    onOpenMeeting: (SessionLibrary.Entry, Double) -> Unit,
    voiceHeader: @Composable () -> Unit = {},
    voiceOfPerson: @Composable (Person) -> Unit = {},
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PeopleContent(onClose, onOpenMeeting, voiceHeader, voiceOfPerson)
    }
}

@Composable
private fun PeopleContent(
    onClose: () -> Unit,
    onOpenMeeting: (SessionLibrary.Entry, Double) -> Unit,
    voiceHeader: @Composable () -> Unit,
    voiceOfPerson: @Composable (Person) -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by PeopleStore.doc.collectAsState()
    val version by PeopleStore.version.collectAsState()
    // Person → meetings (newest first), re-read whenever a link or the directory changes.
    val meetings by produceState(emptyMap<String, List<PersonMeeting>>(), version) {
        value = withContext(Dispatchers.IO) {
            val entries = SessionLibrary.list(context).associateBy { it.id }
            val out = HashMap<String, MutableList<PersonMeeting>>()
            for ((id, links) in PeopleStore.allLinks(context)) {
                val e = entries[id] ?: continue
                links.entries.groupBy { it.value.person }.forEach { (p, ls) ->
                    if (p != null) out.getOrPut(p) { ArrayList() } += PersonMeeting(e, ls.map { it.key }.toSet())
                }
            }
            out.mapValues { (_, v) -> v.sortedByDescending { it.entry.createdAt } }
        }
    }
    var openId by remember { mutableStateOf<String?>(null) }
    val open = doc.byId(openId)
    val access = rememberContactsAccess()
    // People == OS contacts: re-read names/addresses on open; the app-only people left from before
    // are offered once ("Add N people to Contacts?").
    val cardCount by produceState(0, version, access.granted) {
        value = withContext(Dispatchers.IO) { runCatching { PeopleStore.syncContacts(context) }; PeopleStore.contactsCardCount(context) }
    }
    var migrating by remember { mutableStateOf(false) }
    BackHandler { if (open != null) openId = null else onClose() }

    Column(Modifier.fillMaxSize().background(pal.Slate900).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (open != null) openId = null else onClose() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_people_back), tint = pal.Slate200)
            }
            Text(open?.name ?: stringResource(R.string.people_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = pal.Slate200, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        }
        if (open == null) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg).testTag("people-list")) {
                if (doc.migrationFound > 0) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm).background(pal.PanelSurface, MaterialTheme.shapes.medium).padding(Spacing.md)) {
                        Text(pluralStringResource(R.plurals.people_migration_card, doc.migrationFound, doc.migrationFound), color = pal.Slate200, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { PeopleStore.dismissMigrationCard(context) } }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.people_migration_ok))
                        }
                    }
                }
                if (!access.granted) item { ContactsBlockedCard(access) }
                if (cardCount > 0) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm).background(pal.PanelSurface, MaterialTheme.shapes.medium).padding(Spacing.md).testTag("contacts-card")) {
                        Text(pluralStringResource(R.plurals.contacts_card_title, cardCount, cardCount), color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.contacts_card_text), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.align(Alignment.End)) {
                            TextButton(onClick = { scope.launch(Dispatchers.IO) { PeopleStore.dismissContactsCard(context) } }, modifier = Modifier.testTag("contacts-card-dismiss")) {
                                Text(stringResource(R.string.contacts_card_no))
                            }
                            TextButton(enabled = !migrating, onClick = {
                                if (!access.granted) access.request()
                                else { migrating = true; scope.launch(Dispatchers.IO) { runCatching { PeopleStore.migrateToContacts(context) }; migrating = false } }
                            }, modifier = Modifier.testTag("contacts-card-add")) { Text(stringResource(R.string.contacts_card_yes)) }
                        }
                    }
                }
                item { voiceHeader() }
                if (doc.people.isEmpty()) item {
                    Text(stringResource(R.string.people_none), color = pal.Slate400, modifier = Modifier.padding(vertical = Spacing.xxl))
                }
                val homonyms = doc.people.groupBy { PersonNames.normalize(it.name) }.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()
                val sorted = doc.people.sortedWith(compareByDescending<Person> { meetings[it.id]?.size ?: 0 }.thenBy { PersonNames.normalize(it.name) })
                items(sorted, key = { it.id }) { p ->
                    val ms = meetings[p.id].orEmpty()
                    val sub = buildList {
                        if (p.note != null) add(p.note) else if (p.id in homonyms) ms.firstOrNull()?.entry?.let { add(entryTitle(context, it)) }
                        add(meetingCount(ms.size))
                    }.joinToString(" · ")
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { openId = p.id }.heightIn(min = 56.dp).padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = pal.Slate400, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                            Text(p.name, color = pal.Slate200, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(sub, color = pal.Slate400, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = pal.Hairline)
                }
            }
        } else PersonPage(open, meetings[open.id].orEmpty(), doc.people, onOpenMeeting, onGone = { openId = null }, voiceOfPerson, canCreate = access.granted)
    }
}

@Composable
private fun meetingCount(n: Int): String =
    if (n == 0) stringResource(R.string.people_no_meetings) else pluralStringResource(R.plurals.people_meetings, n, n)

private fun entryTitle(context: android.content.Context, e: SessionLibrary.Entry) = e.title ?: SessionLibrary.defaultTitle(context, e.createdAt)

@Composable
private fun PersonPage(
    p: Person, meetings: List<PersonMeeting>, people: List<Person>,
    onOpenMeeting: (SessionLibrary.Entry, Double) -> Unit, onGone: () -> Unit,
    voiceOfPerson: @Composable (Person) -> Unit,
    /** Contacts granted: splitting a meeting off creates a person, so a contact. */
    canCreate: Boolean,
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A linked person's name is the contact's: renamed in the Contacts app ("Edit in Contacts").
    val linked = studio.primeminutes.core.people.ContactLinks.isLinked(p)
    var renaming by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<Person?>(null) }   // both merge paths confirm this one first
    var deleting by remember { mutableStateOf(false) }
    var splitting by remember { mutableStateOf<PersonMeeting?>(null) }
    // Dates follow the interface language, which can differ from the device's.
    val uiLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val dateFmt = remember(uiLocale) { DateFormat.getDateInstance(DateFormat.MEDIUM, uiLocale) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg).testTag("person-page")) {
        p.note?.let { item { Text(it, color = pal.Slate400, modifier = Modifier.padding(bottom = Spacing.sm)) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(vertical = Spacing.sm)) {
                if (!linked) OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.people_rename)) }
                OutlinedButton(onClick = { noting = true }) { Text(stringResource(R.string.people_note_short)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(bottom = Spacing.sm)) {
                if (people.size > 1) OutlinedButton(onClick = { merging = true }) { Text(stringResource(R.string.people_merge_into)) }
                OutlinedButton(onClick = { deleting = true }) { Text(stringResource(R.string.people_delete), color = pal.WarningText) }
            }
        }
        item {
            // The merge is confirmed below (mergeTarget): a person record cannot be un-merged.
            PersonContactCards(p, onMergeInto = { o -> mergeTarget = o })
            Spacer(Modifier.padding(top = Spacing.sm))
        }
        item { voiceOfPerson(p) }
        item { SectionLabel(stringResource(R.string.people_meetings_of, p.name)) }
        if (meetings.isEmpty()) item { Text(stringResource(R.string.people_no_meetings), color = pal.Slate400, modifier = Modifier.padding(vertical = Spacing.sm)) }
        items(meetings, key = { it.entry.id }) { m ->
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    scope.launch {
                        val t = withContext(Dispatchers.IO) {
                            studio.primeminutes.core.session.PrimeMinutesSession.peek(m.entry.sessionFile)
                                ?.utterances?.firstOrNull { it.speaker in m.speakers }?.startSec ?: 0.0
                        }
                        onOpenMeeting(m.entry, t)
                    }
                }.heightIn(min = 56.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entryTitle(context, m.entry), color = pal.Slate200, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(dateFmt.format(Date(m.entry.createdAt)), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                }
                if (canCreate) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options), tint = pal.Slate400) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.people_split, p.name)) }, onClick = { menu = false; splitting = m })
                    }
                }
            }
            HorizontalDivider(color = pal.Hairline)
        }
        item { Spacer(Modifier.padding(bottom = Spacing.xxl)) }
    }

    if (renaming) NameDialog(stringResource(R.string.people_rename), p.name, onDone = { n ->
        renaming = false
        if (n != null && n.isNotBlank() && n != p.name) scope.launch(Dispatchers.IO) { PeopleStore.renameEverywhere(context, p.id, n) }
    })
    if (noting) NameDialog(stringResource(R.string.people_note), p.note ?: "", allowBlank = true, onDone = { n ->
        noting = false
        if (n != null) scope.launch(Dispatchers.IO) { PeopleStore.setNote(context, p.id, n) }
    })
    splitting?.let { m ->
        NameDialog(stringResource(R.string.people_split_title), "", onDone = { n ->
            splitting = null
            if (!n.isNullOrBlank()) scope.launch(Dispatchers.IO) { PeopleStore.split(context, m.entry.id, p.id, n, withContact = true) }
        })
    }
    if (merging) AlertDialog(
        onDismissRequest = { merging = false },
        title = { Text(stringResource(R.string.people_merge_pick, p.name)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(people.filter { it.id != p.id }.sortedBy { PersonNames.normalize(it.name) }, key = { it.id }) { o ->
                    Text(o.name + (o.note?.let { " · $it" } ?: ""), color = pal.Slate200,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                            merging = false
                            mergeTarget = o     // the pick only chooses; the merge is confirmed next
                        }.padding(vertical = Spacing.md))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { merging = false }) { Text(stringResource(R.string.cancel)) } },
    )
    // A merge is one-way: the person disappears with its note, its contact link and its voice
    // samples, so both merge paths stop here for the ok.
    mergeTarget?.let { o ->
        AlertDialog(
            onDismissRequest = { mergeTarget = null },
            title = { Text(stringResource(R.string.people_merge_pick, p.name)) },
            text = { Text(stringResource(R.string.people_merge_text, p.name, o.name)) },
            confirmButton = {
                TextButton(onClick = {
                    mergeTarget = null
                    scope.launch(Dispatchers.IO) { PeopleStore.merge(context, p.id, o.id) }
                    onGone()
                }) { Text(stringResource(R.string.contact_merge), color = pal.WarningText) }
            },
            dismissButton = { TextButton(onClick = { mergeTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting) {
        // Destructive confirm, felt: iOS fires `Haptics.destructive()` on the same tap.
        val delHaptics = LocalHapticFeedback.current
        var clear by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.people_delete_title, p.name)) },
            text = {
                Column {
                    Text(stringResource(R.string.people_delete_text))
                    if (meetings.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = clear, role = Role.Checkbox, onValueChange = { clear = it })) {
                        Checkbox(checked = clear, onCheckedChange = null)
                        Text(stringResource(R.string.people_delete_clear, meetings.size))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    delHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val c = clear
                    scope.launch(Dispatchers.IO) { PeopleStore.delete(context, p.id, c) }
                    onGone()
                }) { Text(stringResource(R.string.people_delete), color = pal.WarningText) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A one-field dialog; [onDone] gets the text, or null when cancelled. */
@Composable
internal fun NameDialog(title: String, initial: String, allowBlank: Boolean = false, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        // Done on the keyboard = the save button: the field is the dialog's whole content and the
        // return key did nothing with it (singleLine, no action wired).
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, colors = primeMinutesTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (allowBlank || text.isNotBlank()) onDone(text.trim()) }),
                modifier = Modifier.testTag("name-dialog-field"),
            )
        },
        confirmButton = { TextButton(enabled = allowBlank || text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text(stringResource(R.string.people_save)) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}
