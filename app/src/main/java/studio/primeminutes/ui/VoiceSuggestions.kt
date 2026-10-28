package studio.primeminutes.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.people.CleanSpeech
import studio.primeminutes.core.people.PeopleOps
import studio.primeminutes.core.people.PeopleStore
import studio.primeminutes.core.people.Person
import studio.primeminutes.core.people.VoiceFiles
import studio.primeminutes.core.people.VoiceMath
import studio.primeminutes.core.people.VoiceStore
import studio.primeminutes.core.people.VoiceSuggestion
import studio.primeminutes.data.SpeakerName
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import java.io.File

/**
 * Voice suggestions for [speaker] of the meeting in [entryDir], and the note shown when there are none
 * (docs/design/speaker-tags.md §2.6): the meeting's speakers against every person's confirmed voice,
 * assigned jointly (one person to one speaker), people already named in this meeting left out. Nothing
 * when recognition is off. A missing or stale sidecar is (re)computed in the background.
 */
@Composable
fun rememberVoiceSuggestions(
    entryDir: File?, speaker: Int, names: Map<Int, SpeakerName>, utterances: List<TranscriptEvent.Utterance>,
): Pair<List<VoiceSuggestion>, String?> {
    val context = LocalContext.current
    val doc by PeopleStore.doc.collectAsState()
    val version by VoiceStore.version.collectAsState()
    val working by VoiceStore.working.collectAsState()
    if (!doc.voice || entryDir == null) return emptyList<VoiceSuggestion>() to null
    val id = entryDir.name
    val card = VoiceStore.card
    val r by produceState<Pair<List<VoiceSuggestion>, Int>>(emptyList<VoiceSuggestion>() to 0, entryDir, speaker, names, version) {
        value = withContext(Dispatchers.IO) {
            val turns = CleanSpeech.voiceTurns(utterances)
            val clean = CleanSpeech.spans(turns, speaker, card.capSec).sumOf { it.sec }
            val mv = VoiceStore.readMeeting(entryDir)
            val spans = utterances.mapNotNull { it.speaker }.distinct().associateWith { CleanSpeech.ordered(turns, it) }
            if (mv == null || mv.fingerprint != VoiceFiles.fingerprint(spans)) VoiceStore.request(context, id)
            when {
                clean < card.minCleanSec -> emptyList<VoiceSuggestion>() to 1
                mv == null -> emptyList<VoiceSuggestion>() to 2
                mv.of(speaker) == null -> emptyList<VoiceSuggestion>() to 1   // its lines hold too little voice
                else -> {
                    val voices = VoiceStore.voices(context, exceptMeeting = id)
                    val linked = names.mapNotNull { (sid, n) -> n.person?.let { sid to it } }
                    val excluded = mv.speakers.associate { v -> v.speaker to linked.map { it.second }.toSet() }
                    val all = VoiceMath.suggest(mv.speakers.associate { it.speaker to it.vector }, voices, card, excluded)
                    (all[speaker].orEmpty()) to (if (voices.isEmpty()) 3 else 0)
                }
            }
        }
    }
    val note = when {
        r.second == 1 -> stringResource(R.string.voice_note_short)
        r.second == 2 || id in working -> stringResource(R.string.voice_note_working)
        r.second == 3 -> stringResource(R.string.voice_note_none_yet)
        r.first.isEmpty() -> stringResource(R.string.voice_note_no_match)
        else -> null
    }
    return r.first to note
}

/** The "Recognise voices" switch, its explanation and the "Me" enrolment, at the top of the People screen. */
@Composable
fun VoiceSettingsCard() {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by PeopleStore.doc.collectAsState()
    val download by VoiceStore.download.collectAsState()
    val working by VoiceStore.working.collectAsState()
    val version by VoiceStore.version.collectAsState()
    var explain by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var enrol by remember { mutableStateOf(false) }
    val known by produceState(emptyMap<String, Pair<Int, Float>>(), version, doc) {
        value = withContext(Dispatchers.IO) { VoiceStore.known(context) }
    }
    Card(Modifier.fillMaxWidth().padding(vertical = Spacing.sm).testTag("voice-card"),
        colors = CardDefaults.cardColors(containerColor = pal.Slate800)) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val voiceTitle = stringResource(R.string.voice_title)
                Column(Modifier.weight(1f)) {
                    Text(voiceTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = pal.Slate200,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(if (doc.voice) R.string.voice_on_desc else R.string.voice_off_desc),
                        style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
                }
                Switch(checked = doc.voice, enabled = download == null,
                    modifier = Modifier.testTag("voice-switch").semantics { contentDescription = voiceTitle },
                    onCheckedChange = { on -> if (on) explain = true else VoiceStore.disable(context, deleteData = false) })
            }
            download?.let {
                Spacer(Modifier.height(Spacing.sm))
                Text(stringResource(R.string.voice_downloading), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }
            if (failed) Text(stringResource(R.string.voice_download_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (doc.voice) {
                Spacer(Modifier.height(Spacing.sm))
                Text(pluralStringResource(R.plurals.voice_known, known.size, known.size), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
                if (working.isNotEmpty()) Text(stringResource(R.string.voice_note_working), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { enrol = true }, modifier = Modifier.testTag("voice-me")) {
                        Text(stringResource(if (doc.people.any { it.me && it.id in known }) R.string.voice_me_again else R.string.voice_me))
                    }
                    TextButton(onClick = { deleteAsk = true }) { Text(stringResource(R.string.voice_delete_all)) }
                }
                MeContactActions(doc.people.firstOrNull { it.me })
            }
        }
    }
    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text(stringResource(R.string.voice_explain_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.voice_explain_text, VoiceStore.card.totalBytes / 1_000_000)) } },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("voice-enable"), onClick = {
                explain = false; failed = false
                scope.launch { failed = !VoiceStore.enable(context) }
            }) { Text(stringResource(R.string.voice_enable)) }
        },
        dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (deleteAsk) AlertDialog(
        onDismissRequest = { deleteAsk = false },
        title = { Text(stringResource(R.string.voice_delete_all)) },
        text = { Text(stringResource(R.string.voice_delete_all_text)) },
        confirmButton = { TextButton(onClick = { deleteAsk = false; scope.launch(Dispatchers.IO) { VoiceStore.deleteAll(context) } }) { Text(stringResource(R.string.people_delete), color = LocalPrimeMinutesPalette.current.WarningText) } },
        dismissButton = { TextButton(onClick = { deleteAsk = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (enrol) MeEnrolDialog(onDone = { enrol = false })
}

/** "This is me": ~20 s of the owner reading aloud, kept only as a voice vector (the audio is not stored). */
@SuppressLint("MissingPermission")
@Composable
private fun MeEnrolDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(0) }       // 0 ready, 1 recording, 2 computing, 3 done, -1 failed
    var progress by remember { mutableStateOf(0f) }
    val meName = stringResource(R.string.voice_me_name)
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    AlertDialog(
        onDismissRequest = { if (phase != 1 && phase != 2) onDone() },
        title = { Text(stringResource(R.string.voice_me)) },
        text = {
            Column {
                Text(stringResource(when (phase) {
                    0 -> if (granted) R.string.voice_me_text else R.string.voice_me_permission
                    1 -> R.string.voice_me_recording
                    2 -> R.string.voice_me_computing
                    3 -> R.string.voice_me_done
                    else -> R.string.voice_me_failed
                }))
                if (phase == 1) { Spacer(Modifier.height(Spacing.sm)); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) }
                if (phase == 0 && granted) { Spacer(Modifier.height(Spacing.sm)); Text(stringResource(R.string.voice_me_script), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            when (phase) {
                0 -> if (granted) TextButton(modifier = Modifier.testTag("voice-me-start"), onClick = {
                    phase = 1
                    scope.launch {
                        val pcm = withContext(Dispatchers.IO) { recordSeconds(ME_SECONDS) { progress = it } }
                        phase = 2
                        val me = pcm?.let { withContext(Dispatchers.IO) { runCatching { VoiceStore.enrolMe(context, it, meName) }.getOrNull() } }
                        phase = if (me != null) 3 else -1
                    }
                }) { Text(stringResource(R.string.voice_me_start)) } else TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
                1, 2 -> {}
                else -> TextButton(onClick = onDone) { Text(stringResource(R.string.people_save)) }
            }
        },
        dismissButton = { if (phase == 0) TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val ME_SECONDS = 20

@SuppressLint("MissingPermission")
private suspend fun recordSeconds(seconds: Int, onProgress: (Float) -> Unit): FloatArray? = withContext(Dispatchers.IO) {
    val rate = 16_000
    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) }.getOrNull()
        ?: return@withContext null
    try {
        if (rec.state != AudioRecord.STATE_INITIALIZED) return@withContext null
        val out = FloatArray(rate * seconds)
        val buf = ShortArray(rate / 10)
        var n = 0
        rec.startRecording()
        while (n < out.size && isActive) {
            val r = rec.read(buf, 0, minOf(buf.size, out.size - n))
            if (r <= 0) return@withContext null
            for (i in 0 until r) out[n + i] = buf[i] / 32768f
            n += r
            withContext(Dispatchers.Main) { onProgress(n.toFloat() / out.size) }
        }
        rec.stop()
        out
    } finally { rec.release() }
}

/** What the app knows of [p]'s voice: "Use for suggestions" and "Forget voice". */
@Composable
fun PersonVoiceRow(p: Person) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by PeopleStore.doc.collectAsState()
    val version by VoiceStore.version.collectAsState()
    if (!doc.voice) return
    val info by produceState<Pair<Int, Float>?>(null, version, p.id) { value = withContext(Dispatchers.IO) { VoiceStore.known(context)[p.id] } }
    val pending by produceState(0, version, p.id) { value = withContext(Dispatchers.IO) { VoiceStore.pendingOutliers(context, p.id).size } }
    var forgetAsk by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm).testTag("person-voice")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val learnTitle = stringResource(R.string.voice_person_title)
            Column(Modifier.weight(1f)) {
                Text(learnTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = pal.Slate200,
                    modifier = Modifier.semantics { heading() })
                Text(info?.let { (m, s) -> pluralStringResource(R.plurals.voice_person_learned, m, m, s.toInt()) } ?: stringResource(R.string.voice_person_none),
                    style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
                if (pending > 0) Text(pluralStringResource(R.plurals.voice_person_pending, pending, pending), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
            }
            Switch(checked = p.voiceEnabled, modifier = Modifier.semantics { contentDescription = learnTitle }, onCheckedChange = { on ->
                scope.launch(Dispatchers.IO) { VoiceStore.setLearning(context, p.id, on) }
            })
        }
        if (info != null) TextButton(onClick = { forgetAsk = true }, modifier = Modifier.testTag("voice-forget")) { Text(stringResource(R.string.voice_forget)) }
    }
    if (forgetAsk) AlertDialog(
        onDismissRequest = { forgetAsk = false },
        title = { Text(stringResource(R.string.voice_forget)) },
        text = { Text(stringResource(R.string.voice_forget_text, p.name)) },
        confirmButton = { TextButton(onClick = { forgetAsk = false; scope.launch(Dispatchers.IO) { VoiceStore.forget(context, p.id) } }) { Text(stringResource(R.string.voice_forget_yes)) } },
        dismissButton = { TextButton(onClick = { forgetAsk = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "This doesn't sound like Mark's other meetings": learn anyway, or keep the name only. */
@Composable
fun VoiceOutlierPrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    LaunchedEffect(Unit) { VoiceStore.outliers.collect { ask = it } }
    val a = ask ?: return
    val name = PeopleStore.doc.collectAsState().value.byId(a.third)?.name ?: return
    AlertDialog(
        onDismissRequest = { ask = null },
        title = { Text(stringResource(R.string.voice_outlier_title, name)) },
        text = { Text(stringResource(R.string.voice_outlier_text, name)) },
        confirmButton = { TextButton(onClick = { ask = null; scope.launch(Dispatchers.IO) { VoiceStore.learnAnyway(context, a.third, a.first) } }) { Text(stringResource(R.string.voice_outlier_learn)) } },
        dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.voice_outlier_keep)) } },
    )
}
