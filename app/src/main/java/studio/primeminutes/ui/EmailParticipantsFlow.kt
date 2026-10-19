package studio.primeminutes.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.config.SummaryScript
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.library.writeTextAtomic
import studio.primeminutes.core.models.ModelCard
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.reader.MeetingLanguage
import studio.primeminutes.core.reader.Note
import studio.primeminutes.core.share.MeetingMail
import studio.primeminutes.core.share.ShareHistory
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import studio.primeminutes.session.SessionState
import java.io.File
import java.time.Instant
import java.time.ZoneId

private const val TAG = "primeminutes-mail"

/**
 * The open meeting → [EmailParticipantsSheet] → the user's mail app (docs/design/speaker-contacts.md
 * §4.4): one mail, every recipient in To, the plain body (and the HTML one, for the apps that take
 * it), attachments from the existing exporters. `ACTION_SEND` keeps the attachments, its selector
 * (`SENDTO mailto:`) lets only e-mail apps answer; without one, the plain share sheet and the
 * addresses on the clipboard. The app cannot know whether the mail was sent: "opened" is recorded.
 */
@Composable
fun EmailParticipantsFlow(
    s: SessionState,
    notes: List<Note>,
    speakerLabel: (Int) -> String,
    exportBaseName: () -> String,
    transcriptText: () -> String,
    exportExtras: (Boolean) -> String?,
    refs: (String?) -> String?,
    notify: (String) -> Unit,
    onNameSpeaker: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by PeopleStore.doc.collectAsState()
    val uiLocale = LocalConfiguration.current.locales[0]
    val entry by produceState<SessionLibrary.Entry?>(null, s.libraryDir) {
        value = withContext(Dispatchers.IO) { s.libraryDir?.let { SessionLibrary.byId(context, it.name) } }
    }
    val sessionBytes = entry?.sessionFile?.takeIf { it.isFile }?.length() ?: 0L
    val speakers = remember(s.utterances) { s.utterances.mapNotNull { it.speaker }.distinct().sorted() }
    val people = s.speakerNames.mapNotNull { (sid, n) -> doc.byId(n.person)?.let { sid to it } }.toMap()
    val labels = s.speakerNames.filterValues { it.name.isNotBlank() }.mapValues { it.value.name }
    val meetingLang = remember(s.utterances) { MeetingLanguage.detect(s.utterances.map { it.text }) }
    val script = SummaryScript.scriptFor(s.config.summaryScript, context)
    val mailLang = when {
        meetingLang == "en" -> "en"
        script == ChineseScript.SIMPLIFIED -> "zh-Hans"
        script == ChineseScript.TRADITIONAL -> "zh-Hant"
        else -> MeetingMail.defaultLang(meetingLang, uiLocale)
    }
    val title = refs(s.title)?.takeIf { it.isNotBlank() } ?: entry?.let { SessionLibrary.defaultTitle(context, it.createdAt) } ?: context.getString(R.string.app_name)
    // The capture's start (an older entry: its save time); null for an import whose file has no date.
    val knownStart = entry?.let(SessionLibrary::meetingStart)
    val startMs = knownStart ?: entry?.createdAt ?: System.currentTimeMillis()

    fun input(): MeetingMail.Input {
        val card = ModelCard.DEFAULT.forLanguage(meetingLang).reader
        // The card's headings are Traditional; the notes are already in the user's script.
        val cc = if (meetingLang != "en" && script == ChineseScript.SIMPLIFIED) runCatching { OpenCcConverter.get(context, script) }.getOrNull() else null
        var sections = MeetingMail.sectionsOf(notes.map { Triple(it.tag, it.text, it.ts) }, card.sections) { h -> cc?.convert(h) ?: h }
        if (sections.isEmpty()) {
            // An older meeting with no notes journal: its action items, if any.
            val acts = s.actionItems?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
            val head = card.sections.firstOrNull { it.second == card.actionTag }
            if (acts != null && head != null) sections = MeetingMail.parseMinutes("【${head.first}】\n$acts", card.sections)
                .map { it.copy(heading = cc?.convert(it.heading) ?: it.heading) }
        }
        val named = s.speakerNames.filterValues { it.name.isNotBlank() }
        // The summary as shown; the grouped minutes (a failed prose call) are not repeated as a summary.
        val summary = s.summary?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("【") }
        return MeetingMail.Input(
            title = title, summary = summary, sections = sections,
            names = speakers.associateWith { speakerLabel(it) },
            participants = named.toSortedMap().values.map { it.name }.distinct(),
            unnamed = speakers.count { it !in named },
            start = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()),
            // A known capture runs start → start + the recording's length (the transcript can end before the audio).
            durationSec = maxOf(s.utterances.maxOfOrNull { it.endSec } ?: 0.0, entry?.takeIf { it.captureStart != null }?.durationSec?.toDouble() ?: 0.0),
            timeKnown = entry == null || knownStart != null,
        )
    }

    suspend fun attachment(kind: String?): File? = withContext(Dispatchers.IO) {
        kind ?: return@withContext null
        runCatching {
            val dir = File(context.cacheDir, "share_mail").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "${exportBaseName()}.$kind")
            when (kind) {
                "txt" -> out.writeText(transcriptText())
                "pdf" -> out.outputStream().use { os ->
                    studio.primeminutes.core.export.PdfExport.write(os, s.utterances, speakerLabel, refs(s.title), refs(s.summary),
                        context.getString(R.string.export_heading_summary), context.getString(R.string.export_heading_transcript),
                        exportExtras(false), null, context.resources.configuration.locales[0])
                }
                "m4a" -> entry?.let { SessionLibrary.shareableCopy(context, it, out) } ?: return@runCatching null
            }
            out.takeIf { it.isFile && it.length() > 0 }
        }.onFailure { Log.w(TAG, "attachment $kind failed", it) }.getOrNull()
    }

    fun record(channel: String, req: MailRequest) {
        val dir = s.libraryDir ?: return
        scope.launch(Dispatchers.IO) {
            val f = File(dir, ShareHistory.FILE)
            val h = ShareHistory.decode(runCatching { f.takeIf { it.isFile }?.readText() }.getOrNull())
            val share = ShareHistory.Share(System.currentTimeMillis() / 1000, channel, "opened",
                if (channel == "email") req.recipients else emptyList(),
                ShareHistory.Content(req.options.summary, req.options.minutes, req.options.only?.toList(), listOfNotNull(req.attach)), req.lang)
            runCatching { f.writeTextAtomic(ShareHistory.encode(ShareHistory.add(h, share))) }
        }
    }

    fun uriOf(f: File): Uri? = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f) }
        .onFailure { Log.w(TAG, "no FileProvider uri", it) }.getOrNull()

    EmailParticipantsSheet(
        title = title,
        dateLabel = SessionLibrary.shortDate(context, startMs),
        people = people, speakers = speakers, labels = labels, meetingLang = mailLang,
        input = ::input, sessionBytes = sessionBytes, entryDir = s.libraryDir,
        onSend = { req ->
            scope.launch {
                val file = attachment(req.attach)
                // The "Attached:" line names the file as the mail app shows it.
                val mail = MeetingMail.build(input(), req.lang, req.options.copy(attachments = listOfNotNull(file?.name), budget = MeetingMail.COMPOSER_BUDGET))
                if (req.attach != null && file == null) notify(context.getString(R.string.export_share_failed))
                val uri = file?.let(::uriOf)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (uri == null) "text/plain" else context.contentResolver.getType(uri) ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_EMAIL, req.to.toTypedArray())
                    putExtra(Intent.EXTRA_SUBJECT, mail.subject)
                    putExtra(Intent.EXTRA_TEXT, mail.plain)
                    putExtra(Intent.EXTRA_HTML_TEXT, mail.html)
                    if (uri != null) {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                // The e-mail apps are those that answer mailto:; of the ACTION_SEND targets, only theirs are offered.
                // (A SENDTO selector on ACTION_SEND is refused by some systems: ActivityNotFound on a Note10+.)
                val pm = context.packageManager
                val mailApps = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0).map { it.activityInfo.packageName }.toSet()
                val targets = pm.queryIntentActivities(send, 0)
                val (mailTargets, others) = targets.partition { it.activityInfo.packageName in mailApps }
                Log.i(TAG, "open mail app: ${req.to.size} recipient(s), ${mail.plain.length} chars, attach=${req.attach}, mailApps=${mailTargets.size}, truncated=${mail.truncated}")
                val ok = runCatching {
                    when {
                        mailTargets.size == 1 -> context.startActivity(Intent(send).setClassName(mailTargets[0].activityInfo.packageName, mailTargets[0].activityInfo.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        mailTargets.size > 1 -> context.startActivity(Intent.createChooser(send, context.getString(R.string.mail_open)).apply {
                            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, others.map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toTypedArray())
                        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else -> {
                            copyAddresses(context, req.to)
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.mail_share_other)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }.onFailure { Log.w(TAG, "no mail app answered", it) }.isSuccess
                if (!ok) { copyAddresses(context, req.to); notify(context.getString(R.string.mail_no_app)); return@launch }
                record("email", req)
                onDismiss()
            }
        },
        onShareChat = { req ->
            scope.launch {
                val mail = MeetingMail.build(input(), req.lang, req.options)
                val file = attachment(req.attach)
                val uri = file?.let(::uriOf)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (uri == null) "text/plain" else context.contentResolver.getType(uri) ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_TEXT, mail.chat)
                    putExtra(Intent.EXTRA_SUBJECT, mail.subject)
                    if (uri != null) { putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri(file.name, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.mail_share_other)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                record("share-sheet", req)
                onDismiss()
            }
        },
        onNameSpeaker = onNameSpeaker,
        onDismiss = onDismiss,
    )
}

private fun copyAddresses(context: Context, to: List<String>) {
    if (to.isEmpty()) return
    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("e-mail", to.joinToString(", ")))
    Toast.makeText(context, R.string.mail_addresses_copied, Toast.LENGTH_LONG).show()
}
