package studio.primeminutes.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.people.EmailRules
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.share.MeetingMail
import studio.primeminutes.core.share.Recipients
import studio.primeminutes.core.share.ShareHistory
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.primeMinutesTextFieldColors
import java.io.File

/** What the participants sheet hands to the caller, which opens the mail app (or the share sheet). */
data class MailRequest(
    val to: List<String>,
    /** Who [to] are, for the share history (person id or null for a one-off address). */
    val recipients: List<ShareHistory.Recipient>,
    val options: MeetingMail.Options,
    val lang: String,
    /** "pdf" | "txt" | "m4a", or null. */
    val attach: String?,
)

/** The content defaults the sheet remembers (no personal data in them). */
private class MailDefaults(context: Context) {
    private val p = context.getSharedPreferences("mail_share", Context.MODE_PRIVATE)
    var summary: Boolean get() = p.getBoolean("summary", true); set(v) { p.edit().putBoolean("summary", v).apply() }
    var minutes: Boolean get() = p.getBoolean("minutes", true); set(v) { p.edit().putBoolean("minutes", v).apply() }
    var only: Boolean get() = p.getBoolean("only", false); set(v) { p.edit().putBoolean("only", v).apply() }
    var attach: String? get() = p.getString("attach", null); set(v) { p.edit().putString("attach", v).apply() }
}

private val ONLY = setOf("DECISION", "ACTION")

/**
 * "Email participants" (docs/design/speaker-contacts.md §4.3, mockups 04/06): the meeting's people
 * pre-checked (Me not), a missing address added inline (saved to the person), the content, the mail
 * language, a preview, then the user's own mail app opens with everything filled in. The app never
 * sends mail and never talks to a server.
 *
 * [people]: speaker → person of this meeting. [speakers]: every speaker id of the transcript,
 * [labels] the names they show. [input]: the meeting as the mail renders it. [sessionBytes]: the
 * size of the `.m4a` (0: not offered). [entryDir]: the library entry (its `shared.json`), or null.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EmailParticipantsSheet(
    title: String,
    dateLabel: String,
    people: Map<Int, Person>,
    speakers: List<Int>,
    labels: Map<Int, String>,
    meetingLang: String,
    input: () -> MeetingMail.Input,
    sessionBytes: Long,
    entryDir: File?,
    onSend: (MailRequest) -> Unit,
    onShareChat: (MailRequest) -> Unit,
    onNameSpeaker: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by PeopleStore.doc.collectAsState()
    val defaults = remember { MailDefaults(context) }
    var history by remember { mutableStateOf<List<ShareHistory.Share>>(emptyList()) }
    LaunchedEffect(entryDir) {
        history = withContext(Dispatchers.IO) {
            entryDir?.let { ShareHistory.decode(runCatching { File(it, ShareHistory.FILE).takeIf(File::isFile)?.readText() }.getOrNull()) }.orEmpty()
        }
    }
    val last = history.lastOrNull { it.channel == "email" }
    // The people of the meeting, with the directory's current addresses.
    val live = people.mapValues { (_, p) -> doc.byId(p.id) ?: p }
    val anyMe = doc.people.any { it.me }
    val initial = remember(last, people.keys) { Recipients.rows(live, anyMe, last).associate { it.person.id to it.checked } }
    val checked = remember(initial) { mutableStateMapOf<String, Boolean>().apply { putAll(initial) } }
    val rows = Recipients.rows(live, anyMe, last).map { it.copy(checked = it.address != null && (checked[it.person.id] ?: it.checked)) }
    val extras = remember { mutableStateListOf<Pair<String?, String>>() }   // one-off (name, address)
    var summary by remember { mutableStateOf(defaults.summary) }
    var minutes by remember { mutableStateOf(defaults.minutes) }
    var only by remember { mutableStateOf(defaults.only) }
    var attach by remember { mutableStateOf(defaults.attach?.takeIf { it != "m4a" || sessionBytes > 0 }) }
    val langs = MeetingMail.LANGS
    var lang by remember { mutableStateOf(meetingLang) }
    var langMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MeetingMail.Mail?>(null) }
    var whoMe by remember { mutableStateOf(false) }

    fun request(): MailRequest {
        val to = Recipients.to(rows, extras.map { it.second })
        val rec = rows.filter { it.checked && it.address != null }.map { ShareHistory.Recipient(it.person.id, it.address!!) } +
            extras.map { ShareHistory.Recipient(null, it.second) }
        val opts = MeetingMail.Options(
            summary = summary && !only, minutes = minutes || only, only = if (only) ONLY else null,
        )
        return MailRequest(to, rec.distinctBy { EmailRules.key(it.address) }, opts, lang, attach)
    }
    fun saveDefaults() { defaults.summary = summary; defaults.minutes = minutes; defaults.only = only; defaults.attach = attach }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.Slate800) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding().testTag("mail-sheet")) {
            Text(stringResource(R.string.mail_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = pal.Slate200)
            Text("$title · $dateLabel", color = pal.Slate400, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)

            SectionLabel(stringResource(R.string.mail_recipients))
            if (rows.isEmpty() && extras.isEmpty()) Text(stringResource(R.string.mail_no_people), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
            rows.forEach { r ->
                RecipientRow(r, anyMe, onCheck = { on -> checked[r.person.id] = on },
                    onAddress = { a -> scope.launch(Dispatchers.IO) { PeopleStore.addEmail(context, r.person.id, a) }; checked[r.person.id] = true })
            }
            extras.forEach { (n, a) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(n ?: a)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(n ?: a, color = pal.Slate200, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (n != null) a + " · " + stringResource(R.string.mail_one_off) else stringResource(R.string.mail_one_off), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                    }
                    // A labelled Remove button: the checkbox that stood here deleted the recipient when
                    // it was un-ticked, which reads as a selection and not as a delete.
                    IconButton(onClick = { extras.remove(n to a) }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.email_remove) + " · " + (n ?: a), tint = pal.Slate400)
                    }
                }
            }
            // Speakers who cannot be recipients yet: name them first.
            Recipients.loose(speakers, labels.filterKeys { it !in people }, people).forEach { l ->
                Row(Modifier.fillMaxWidth().clickable { onNameSpeaker(l.speaker) }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (l.label == null) stringResource(R.string.mail_unnamed, stringResource(R.string.speaker_n, l.speaker + 1))
                        else stringResource(R.string.mail_plain_label, l.label),
                        color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(if (l.label == null) R.string.mail_name_action else R.string.mail_link_action), color = pal.Sky, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { adding = true }.heightIn(min = 48.dp).testTag("mail-add-recipient"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = pal.Sky)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mail_add_recipient), color = pal.Sky, fontWeight = FontWeight.SemiBold)
            }
            if (!anyMe && rows.isNotEmpty()) Box {
                TextButton(onClick = { whoMe = true }) { Text(stringResource(R.string.mail_which_is_you), style = MaterialTheme.typography.bodySmall) }
                DropdownMenu(expanded = whoMe, onDismissRequest = { whoMe = false }) {
                    rows.forEach { r ->
                        DropdownMenuItem(text = { Text(r.person.name) }, onClick = {
                            whoMe = false; checked[r.person.id] = false
                            scope.launch(Dispatchers.IO) { PeopleStore.setMe(context, r.person.id) }
                        })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.mail_content)) }
                TextButton(onClick = { preview = MeetingMail.build(input(), lang, request().options.copy(attachments = attachNames(attach, title))) }, modifier = Modifier.testTag("mail-preview")) {
                    Text(stringResource(R.string.mail_preview))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.mail_summary), on = summary && !only) { summary = !summary; only = false }
                Chip(stringResource(R.string.mail_minutes), on = minutes && !only) { minutes = !minutes; only = false }
                Chip(stringResource(R.string.mail_only_decisions), on = only) { only = !only }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, tint = pal.Slate400, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.mail_attach), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Chip(stringResource(R.string.mail_attach_none), on = attach == null) { attach = null }
                Chip("PDF", on = attach == "pdf") { attach = "pdf" }
                Chip("TXT", on = attach == "txt") { attach = "txt" }
                if (sessionBytes > 0) Chip(".m4a · " + android.text.format.Formatter.formatShortFileSize(context, sessionBytes), on = attach == "m4a") { attach = "m4a" }
            }
            if (attach == "m4a" && sessionBytes > 20L * 1024 * 1024) Text(stringResource(R.string.mail_attach_large), color = pal.WarningText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mail_language), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                Box {
                    OutlinedButton(onClick = { langMenu = true }) {
                        Text(langName(lang) + if (lang == meetingLang) " " + stringResource(R.string.mail_language_meeting) else "")
                    }
                    DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                        Text(stringResource(R.string.mail_language_note), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        langs.forEach { l -> DropdownMenuItem(text = { Text(langName(l)) }, onClick = { lang = l; langMenu = false }) }
                    }
                }
            }
            last?.let { s ->
                val who = s.recipients.mapNotNull { r -> r.person?.let { doc.byId(it)?.name } ?: r.address }.joinToString(", ")
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = pal.Slate400, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.mail_last_shared, studio.primeminutes.core.library.SessionLibrary.shortDate(context, s.at * 1000, withTime = true), who),
                        color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val n = request().to.size
            Button(
                onClick = { saveDefaults(); onSend(request()) },
                colors = ButtonDefaults.buttonColors(containerColor = pal.Sky, contentColor = pal.OnSky),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 52.dp).testTag("mail-open"),
            ) {
                Icon(Icons.Filled.Email, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (n > 0) stringResource(R.string.mail_open_n, n) else stringResource(R.string.mail_open))
            }
            OutlinedButton(onClick = { saveDefaults(); onShareChat(request()) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mail_share_other))
            }
            Text(stringResource(R.string.mail_footnote), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp))
        }
    }

    if (adding) AddRecipientDialog(
        candidates = doc.people.filter { p -> p.emails.isNotEmpty() && rows.none { it.person.id == p.id } },
        onDone = { pick ->
            adding = false
            if (pick != null && extras.none { EmailRules.key(it.second) == EmailRules.key(pick.second) }) extras += pick
        },
    )
    preview?.let { m ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(m.subject, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    val to = request().to
                    if (to.isNotEmpty()) Text(stringResource(R.string.mail_preview_to, to.joinToString(", ")), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                    Text(m.plain, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Default, modifier = Modifier.padding(top = 8.dp).testTag("mail-preview-body"))
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.mail_preview_close)) } },
        )
    }
}

/** The file names the mail's "Attached:" line lists. */
internal fun attachNames(attach: String?, title: String): List<String> {
    val base = title.take(48).replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().ifEmpty { "meeting" }
    return when (attach) { null -> emptyList(); "m4a" -> listOf("$base.m4a"); else -> listOf("$base.$attach") }
}

@Composable
private fun langName(l: String) = stringResource(when (l) { "zh-Hant" -> R.string.mail_lang_hant; "zh-Hans" -> R.string.mail_lang_hans; else -> R.string.mail_lang_en })

@Composable
private fun Avatar(name: String) {
    val pal = LocalPrimeMinutesPalette.current
    Box(Modifier.size(36.dp).clip(CircleShape).background(pal.Sky.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = pal.Sky, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.clip(shape).background(if (on) pal.Sky.copy(alpha = 0.15f) else Color.Transparent)
            .border(1.dp, if (on) pal.Sky.copy(alpha = 0.4f) else pal.Hairline, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text((if (on) "✓ " else "") + label, color = if (on) pal.Sky else pal.Slate400, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecipientRow(r: Recipients.Row, anyMe: Boolean, onCheck: (Boolean) -> Unit, onAddress: (String) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val pick = rememberContactPicker { _, chosen -> onAddress(chosen); saved = true; typing = false }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = r.checked, enabled = r.address != null, onCheckedChange = onCheck)
            Avatar(r.person.name)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.person.name, color = pal.Slate200, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (r.person.me && anyMe) Text(stringResource(R.string.mail_me), color = pal.Slate400, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(pal.Hairline).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                when {
                    r.address == null -> Text(stringResource(R.string.mail_no_address), color = pal.WarningText, style = MaterialTheme.typography.bodySmall)
                    else -> Text(listOfNotNull(r.address, if (r.person.me && anyMe) stringResource(R.string.mail_not_to_self) else null,
                        if (saved) stringResource(R.string.mail_saved_to, r.person.name) else null).joinToString(" · "),
                        color = pal.Slate400, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (r.address == null && !typing) OutlinedButton(onClick = { typing = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)); Text(stringResource(R.string.mail_add_address))
            }
        }
        if (typing && r.address == null) {
            val ok = EmailRules.isValid(text)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, colors = primeMinutesTextFieldColors(),
                    placeholder = { Text("name@example.com") }, isError = text.isNotBlank() && !ok,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) { onAddress(EmailRules.normalize(text)); saved = true; typing = false } }),
                    trailingIcon = { IconButton(enabled = ok, onClick = { onAddress(EmailRules.normalize(text)); saved = true; typing = false }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.people_save)) } },
                    modifier = Modifier.weight(1f).testTag("mail-inline-address"))
                IconButton(onClick = pick) { Icon(Icons.Filled.ContactPage, contentDescription = stringResource(R.string.contact_choose), tint = pal.Sky) }
            }
            Text(stringResource(R.string.mail_will_save, r.person.name), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp, bottom = 4.dp))
        }
        HorizontalDivider(color = pal.Hairline, modifier = Modifier.padding(start = 48.dp))
    }
}

/** "Add another recipient": a person of the directory, a picked contact, or a typed address (one-off, not saved). */
@Composable
private fun AddRecipientDialog(candidates: List<Person>, onDone: (Pair<String?, String>?) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    var q by remember { mutableStateOf("") }
    val pick = rememberContactPicker { c, chosen -> onDone(c.name to chosen) }
    val hits = candidates.filter { p -> q.isBlank() || p.name.contains(q.trim(), ignoreCase = true) || p.emails.any { it.address.contains(q.trim(), ignoreCase = true) } }
        .sortedByDescending { it.lastUsed }.take(8)
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.mail_add_recipient)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, colors = primeMinutesTextFieldColors(),
                    placeholder = { Text(stringResource(R.string.mail_add_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("mail-add-field"))
                if (EmailRules.isValid(q)) Text(stringResource(R.string.mail_add_typed, EmailRules.normalize(q)), color = pal.Sky, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { onDone(null to EmailRules.normalize(q)) }.padding(vertical = 12.dp))
                hits.forEach { p ->
                    Column(Modifier.fillMaxWidth().clickable { onDone(p.name to p.emails.first().address) }.padding(vertical = 8.dp)) {
                        Text(p.name, color = pal.Slate200)
                        Text(p.emails.first().address, color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { pick() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ContactPage, contentDescription = null, tint = pal.Sky)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.contact_choose), color = pal.Sky)
                }
                Text(stringResource(R.string.mail_one_off_note), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}
