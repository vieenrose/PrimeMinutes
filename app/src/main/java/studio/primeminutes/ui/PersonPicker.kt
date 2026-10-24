package studio.primeminutes.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.people.PeoplePicker
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.people.PersonNames
import studio.primeminutes.core.people.VoiceSuggestion
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.primeMinutesTextFieldColors

/** A speaker of the meeting, as the picker shows it. [person]: the person it is linked to, if any. */
data class PickerSpeaker(val id: Int, val label: String, val person: String?)

/** What the user chose in [PersonPickerSheet]. */
sealed interface PersonPick {
    /** A person of the directory (possibly one already linked to another speaker of this meeting). */
    data class Existing(val person: Person) : PersonPick
    /** A new person named [name] (the "same person or someone else?" question was already asked). */
    data class New(val name: String) : PersonPick
    /** Back to the default "Speaker n" label. */
    data object Clear : PersonPick
    /** A contact from the system picker (docs/design/speaker-contacts.md §2.2), [chosen] the tapped address. */
    data class FromContact(val contact: studio.primeminutes.core.people.PickedContact, val chosen: String) : PersonPick
}

/**
 * The speaker picker (docs/design/speaker-tags.md §2.1), reusable by any screen that names a speaker
 * (the rename sheet here, the boundary editor's "split line here…"). It lists the people of the
 * directory — voice suggestions (Phase 2), people already in this meeting (greyed, "already 語者 3"),
 * recent, then all — with search and "Add ‹text› as a new person". It never edits anything: the
 * choice comes back through [onPick] and the caller applies it (and asks "merge?" when a person
 * already in the meeting was picked, see [SpeakerNameFlow]).
 *
 * [current]: the speaker being named, or null when the caller picks a person for a speaker that does
 * not exist yet. [speakers]: every speaker of the meeting (for "already ‹label›").
 * [onPlaySample]: plays a short clip of [current] (null hides the button). [allowClear]: offer "Clear name".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonPickerSheet(
    title: String,
    current: PickerSpeaker?,
    speakers: List<PickerSpeaker>,
    onPick: (PersonPick) -> Unit,
    onDismiss: () -> Unit,
    suggestions: List<VoiceSuggestion> = emptyList(),
    /** Under the voice section: why there is none ("Not enough speech to compare voices"), or null. */
    voiceNote: String? = null,
    onPlaySample: (() -> Unit)? = null,
    allowClear: Boolean = current?.person != null || (current != null && current.label != defaultLabel(current.id)),
    /** Offer "Choose from contacts…" (the system picker, no permission). */
    allowContacts: Boolean = false,
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val doc by PeopleStore.doc.collectAsState()
    val version by PeopleStore.version.collectAsState()
    val usage by produceState(emptyMap<String, PeoplePicker.Usage>(), version) {
        value = withContext(Dispatchers.IO) { runCatching { PeopleStore.usage(context) }.getOrDefault(emptyMap()) }
    }
    val fold by produceState<(String) -> String>({ it }) { value = withContext(Dispatchers.IO) { PeopleStore.fold(context) } }
    var query by remember { mutableStateOf("") }
    var sameAsk by remember { mutableStateOf<Pair<String, List<Person>>?>(null) }
    val inMeeting = remember(speakers, current) {
        speakers.filter { it.id != current?.id && it.person != null }.associate { it.person!! to it.id }
    }
    val labelOf = remember(speakers) { speakers.associate { it.id to it.label } }
    val exclude = setOfNotNull(current?.person)
    // A person suggested by voice is listed once, in the voice section (while not searching).
    val voiceIds = if (query.isBlank()) suggestions.map { it.person }.filter { it !in inMeeting }.toSet() else emptySet()
    val sec = remember(doc, usage, inMeeting, query, fold, voiceIds) { PeoplePicker.sections(doc, usage, inMeeting, query, fold, exclude + voiceIds) }
    val homonyms = remember(doc, fold) { PeoplePicker.homonyms(doc, fold) }
    fun hint(p: Person): String? = when {
        p.note != null -> p.note
        p.id in homonyms -> usage[p.id]?.lastTitle?.let { context.getString(R.string.people_last, it) }
        else -> null
    }
    fun addNew(name: String) {
        val n = name.trim()
        if (!PersonNames.isPersonName(n)) return
        val same = doc.people.filter { PersonNames.same(it.name, n, fold) && it.id != current?.person }
        if (same.isEmpty()) onPick(PersonPick.New(n)) else sameAsk = n to same
    }
    val pickContact = rememberContactPicker { c, chosen -> onPick(PersonPick.FromContact(c, chosen)) }
    val suggested = suggestions.mapNotNull { s -> doc.byId(s.person)?.let { it to s } }.filter { it.first.id !in exclude && it.first.id !in inMeeting }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.Slate800) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = pal.Slate200,
                    modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (onPlaySample != null) IconButton(onClick = onPlaySample) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.people_play_sample), tint = pal.Sky)
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.people_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                // 取消 (Cancel) described a field-clearing button; it says 清除搜尋 now (iOS label is the
                // same fix, logged as open there).
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.cd_clear_search)) } }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val exact = (sec.inMeeting + sec.recent + sec.all).firstOrNull { PersonNames.same(it.person.name, query, fold) }
                    if (exact != null) onPick(PersonPick.Existing(exact.person)) else addNew(query)
                }),
                colors = primeMinutesTextFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("people-search"),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                if (allowContacts && query.isBlank()) item {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { pickContact() }.heightIn(min = 48.dp).padding(vertical = 6.dp).testTag("people-contacts"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ContactPage, contentDescription = null, tint = pal.Sky)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.contact_choose), color = pal.Sky, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.contact_choose_hint), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (query.isBlank() && (suggested.isNotEmpty() || voiceNote != null)) {
                    item { SectionLabel(stringResource(R.string.people_section_voice)) }
                    items(suggested, key = { "v" + it.first.id }) { (p, s) ->
                        PersonRow(p.name, hint(p) ?: meetingsText(usage[p.id]?.meetings ?: 0), dots = s.level, onClick = { onPick(PersonPick.Existing(p)) })
                    }
                    if (suggested.isEmpty() && voiceNote != null) item { Text(voiceNote, color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
                }
                if (sec.inMeeting.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.people_section_here)) }
                    items(sec.inMeeting, key = { "m" + it.person.id }) { r ->
                        PersonRow(r.person.name, stringResource(R.string.people_already_as, r.inMeetingAs?.let { defaultLabel(it) } ?: ""), greyed = true,
                            onClick = { onPick(PersonPick.Existing(r.person)) })
                    }
                }
                if (sec.recent.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.people_section_recent)) }
                    items(sec.recent, key = { "r" + it.person.id }) { r ->
                        PersonRow(r.person.name, hint(r.person) ?: meetingsText(r.meetings), onClick = { onPick(PersonPick.Existing(r.person)) })
                    }
                }
                if (sec.all.isNotEmpty()) {
                    item { SectionLabel(stringResource(if (query.isBlank()) R.string.people_section_all else R.string.people_section_matches)) }
                    items(sec.all, key = { "a" + it.person.id }) { r ->
                        PersonRow(r.person.name, hint(r.person) ?: meetingsText(r.meetings), onClick = { onPick(PersonPick.Existing(r.person)) })
                    }
                }
                if (doc.people.isEmpty() && query.isBlank()) item {
                    Text(stringResource(R.string.people_picker_empty), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (sec.canAdd) item {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { addNew(query) }.padding(vertical = 12.dp).testTag("people-add"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = pal.Sky)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.people_add_new, query.trim()), color = pal.Sky, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (allowClear && current != null) item {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = pal.Hairline)
                    Text(stringResource(R.string.people_clear_name, defaultLabel(current.id)), color = pal.Slate400,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(PersonPick.Clear) }
                            .heightIn(min = 48.dp).padding(vertical = 12.dp))
                }
                item { Spacer(Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
    sameAsk?.let { (name, same) ->
        val p = same.maxByOrNull { it.lastUsed }!!
        val where = usage[p.id]?.lastTitle ?: p.note ?: ""
        AlertDialog(
            onDismissRequest = { sameAsk = null },
            text = { Text(if (where.isBlank()) stringResource(R.string.people_same_title_bare, p.name) else stringResource(R.string.people_same_title, p.name, where)) },
            confirmButton = { TextButton(onClick = { sameAsk = null; onPick(PersonPick.Existing(p)) }) { Text(stringResource(R.string.people_same_yes)) } },
            dismissButton = { TextButton(onClick = { sameAsk = null; onPick(PersonPick.New(name)) }) { Text(stringResource(R.string.people_same_no, name)) } },
        )
    }
}

@Composable
private fun defaultLabel(id: Int) = stringResource(R.string.speaker_n, id + 1)

@Composable
private fun meetingsText(n: Int): String =
    if (n == 0) stringResource(R.string.people_no_meetings) else pluralStringResource(R.plurals.people_meetings, n, n)

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = LocalPrimeMinutesPalette.current.Slate400, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

/** Three dots for a voice suggestion's confidence: ●●● likely, ●●○ possible (never a percentage). */
@Composable
internal fun ConfidenceDots(level: Int, color: Color) {
    Text("●".repeat(level.coerceIn(0, 3)) + "○".repeat(3 - level.coerceIn(0, 3)), color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun PersonRow(name: String, sub: String?, greyed: Boolean = false, dots: Int = 0, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (dots > 0) ConfidenceDots(dots, pal.Sky)
        Text(name, color = if (greyed) pal.Slate400 else pal.Slate200, style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (sub != null) Text(sub, color = pal.Slate400, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The rename flow of the session screen: [PersonPickerSheet] for [speaker], then, when the person
 * picked is already another speaker of this meeting, "Mark is already 語者 3 here: merge?" (Merge /
 * Both are Mark / Cancel). [onMerge] merges [speaker] into that speaker; [onLink] links it.
 */
@Composable
fun SpeakerNameFlow(
    speaker: PickerSpeaker,
    speakers: List<PickerSpeaker>,
    onLink: (Person) -> Unit,
    onNew: (String) -> Unit,
    onClear: () -> Unit,
    onMerge: (into: Int) -> Unit,
    onDismiss: () -> Unit,
    suggestions: List<VoiceSuggestion> = emptyList(),
    voiceNote: String? = null,
    onPlaySample: (() -> Unit)? = null,
) {
    var mergeAsk by remember { mutableStateOf<Pair<Person, PickerSpeaker>?>(null) }
    // From contacts: confirm the name, then (same name in the directory?) the same person or a new one.
    var contactName by remember { mutableStateOf<PersonPick.FromContact?>(null) }
    var contactSame by remember { mutableStateOf<Triple<PersonPick.FromContact, String, Person>?>(null) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun linkFromContact(pick: PersonPick.FromContact, existing: Person?, name: String) {
        scope.launch {
            val p = withContext(Dispatchers.IO) {
                if (existing != null) { PeopleStore.linkContact(context, existing.id, pick.contact, pick.chosen); PeopleStore.doc.value.byId(existing.id) ?: existing }
                else PeopleStore.createFromContact(context, name, pick.contact, pick.chosen)
            }
            val other = speakers.firstOrNull { it.id != speaker.id && it.person == p.id }
            if (other != null) mergeAsk = p to other else onLink(p)
        }
    }
    if (mergeAsk == null && contactName == null && contactSame == null) PersonPickerSheet(
        title = stringResource(R.string.people_who_is, speaker.label),
        current = speaker, speakers = speakers,
        suggestions = suggestions, voiceNote = voiceNote, onPlaySample = onPlaySample,
        onDismiss = onDismiss, allowContacts = true,
        onPick = { pick ->
            when (pick) {
                is PersonPick.Existing -> {
                    val other = speakers.firstOrNull { it.id != speaker.id && it.person == pick.person.id }
                    if (other != null) mergeAsk = pick.person to other else onLink(pick.person)
                }
                is PersonPick.New -> onNew(pick.name)
                PersonPick.Clear -> onClear()
                is PersonPick.FromContact -> contactName = pick
            }
        },
    )
    contactName?.let { pick ->
        NameDialog(stringResource(R.string.contact_name_title), pick.contact.name ?: pick.chosen.substringBefore('@'), onDone = { n ->
            contactName = null
            if (n.isNullOrBlank()) { onDismiss(); return@NameDialog }
            val fold: (String) -> String = { it }
            val same = PeopleStore.doc.value.people.firstOrNull { PersonNames.same(it.name, n, fold) && it.id != speaker.person }
            if (same != null) contactSame = Triple(pick, n, same) else linkFromContact(pick, null, n)
        })
    }
    contactSame?.let { (pick, name, same) ->
        AlertDialog(
            onDismissRequest = { contactSame = null; onDismiss() },
            text = { Text(stringResource(R.string.people_same_title_bare, same.name)) },
            confirmButton = { TextButton(onClick = { contactSame = null; linkFromContact(pick, same, name) }) { Text(stringResource(R.string.people_same_yes)) } },
            dismissButton = { TextButton(onClick = { contactSame = null; linkFromContact(pick, null, name) }) { Text(stringResource(R.string.people_same_no, name)) } },
        )
    }
    mergeAsk?.let { (p, other) ->
        AlertDialog(
            onDismissRequest = { mergeAsk = null; onDismiss() },
            title = { Text(stringResource(R.string.people_merge_title, p.name, defaultLabel(other.id))) },
            text = { Text(stringResource(R.string.people_merge_text, speaker.label, other.label)) },
            confirmButton = { TextButton(onClick = { mergeAsk = null; onMerge(other.id) }) { Text(stringResource(R.string.speaker_merge)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { mergeAsk = null; onDismiss() }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { mergeAsk = null; onLink(p) }) { Text(stringResource(R.string.people_both, p.name)) }
                }
            },
        )
    }
}
