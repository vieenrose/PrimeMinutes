package studio.primeminutes.ui

import androidx.compose.runtime.Composable
import java.io.File
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.people.VoiceSuggestion
import studio.primeminutes.data.SpeakerName

/** Voice suggestions for [speaker] of the meeting in [entryDir], and the note shown when there are none. */
@Composable
fun rememberVoiceSuggestions(
    entryDir: File?, speaker: Int, names: Map<Int, SpeakerName>, utterances: List<TranscriptEvent.Utterance>,
): Pair<List<VoiceSuggestion>, String?> = emptyList<VoiceSuggestion>() to null

/** Phase 2: the "Recognise voices" switch and its explanation, at the top of the People screen. */
@Composable
fun VoiceSettingsCard() {}

/** Phase 2: what the app knows of [p]'s voice, and "Forget voice". */
@Composable
fun PersonVoiceRow(p: studio.primeminutes.core.people.Person) {}
