# Design: a shared directory of speaker names across meetings ("speaker tags")

Status: proposal, 2026-10-17. Nothing here is implemented. Android + iOS.

User request: "once a speaker got manually named, for example Mark, in any session, when renaming
a speaker she can choose Mark from the list of already used tags, or of course add a new name. A
more advanced feature is an ordered list of used tags by confidence of resemblance."

## 0. Summary

- **Phase 1: people directory and name picker, no ML.** A local list of the people the user has
  named. Renaming a speaker opens a sheet:
  - known names first (recent, then most used), then "new name";
  - picking a name links that speaker to the person;
  - the directory screen renames a person everywhere, merges two names, deletes a name, and shows
    the meetings where a person appears;
  - the library gets a filter "meetings with Mark";
  - Ask routes "what did Mark say" to Mark's lines.

  Existing renamed speakers are migrated into the directory. **About 2 weeks for both platforms.**
- **Phase 2: voice resemblance, opt-in, suggestions only.** When the user turns on "Recognise
  voices (on this phone)":
  - each speaker gets one voice vector per meeting, from at most 60 s of clean single-speaker
    speech;
  - a small dedicated speaker model computes it: **3D-Speaker CAM++ zh-en, 7 M params, ~14 MB
    fp16, Apache-2.0**, run on the LiteRT CPU runtime that the app already ships;
  - the rename sheet ranks known people by resemblance, with a 3-level confidence indicator;
  - a person's voice is updated **only when the user confirms** a name;
  - nothing is ever applied silently.

  **About 2.5–3 weeks.**
- **The diarizer cannot be reused for this.** Measured on AMI (§3.1):
  - Sortformer's speaker slots are per-session arrival order. Its pooled final-layer features
    identify people across meetings at chance level: within-series EER 37–47 %.
  - The speaker-cache features (pre-encode) and a middle layer reach 15–30 % within-series EER.
  - A dedicated 7 M-param speaker model gets ≈ 0–3 %.
- **Feasibility on AMI meeting series** (the same 4 people in 4 meetings, 16 meetings, 64 people;
  `tools/speaker-id-eval/`):
  - With CAM++ zh-en and the app's own diarizer segmentation on a far-field table mic, enrolment
    from one meeting identifies the person in the 3 later meetings at **top-1 = 100 % (31/31
    probes)**.
  - The pairwise EER is 0.1–0.2 % (within the same room: 1.5–2.8 %).
  - With enrolment on close-talk and probes far-field (channel change): top-1 stays 100 %, but
    same-person scores drop by ≈ 0.05–0.1. So the absolute thresholds must be conservative, and
    the ranking does most of the work.
  - Mandarin same-room impostors (AISHELL-4, far field): CAM++ scores different people up to
    0.67 (p95 0.51), and the same speaker across a session ≥ 0.77.
- **Privacy.** A voice vector is biometric-like data. The proposed defaults:
  - off by default, with a plain-language opt-in;
  - local only, never in exports, share sheets or backups;
  - per-person "forget voice" and a global "delete all voice data";
  - a meeting's voice samples are deleted with the meeting.

## 1. What exists today

| | Android (`app/src/main/java/studio/primeminutes/`) | iOS (`ios/App/`) |
|---|---|---|
| Speaker label | `Utterance.speaker: Int?` (diarizer slot, 0-based; shown "語者 n+1") | `Utterance.speaker: Int` |
| Names | `Map<Int, SpeakerName(name, confidence="user", reason)>` (`data/Session.kt`), per meeting | `Session.speakerNames: [String: String]?` (`Library.swift`) |
| Storage | inside the `session.m4a` blob (`speaker_names` in `PrimeMinutesSession.encodeSession`), plus the autosave; `meta.json` = id/title/date/status only | `sessions/<uuid>.json`; `.m4a` export via `SessionFile.swift` writes the same `speaker_names` manifest |
| Rename UI | tap the speaker label, then an inline chip text field (`SessionComponents.SpeakerTag`); empty name = back to default | `.alert` with a TextField (`SessionView.swift:298`) |
| Fix-ups | long-press menu: move line / merge speaker (`SpeakerEdits`, ids never renumbered, undo snackbar) | same (`reassign` / `merge`, undo) |
| Summary/notes | the reader writes `S1`, `S2`…; `SpeakerRefs.resolve` maps them to names **at display/export time**, so a rename reaches the summary for free | same (`SpeakerRefs` in `Export.swift`) |
| Search / Ask | `SessionIndex` chunk lines are `Name: text`; the fingerprint includes names, so a rename re-embeds only that speaker's chunks; Ask excerpts carry the names | same format (`SessionIndex.swift`, cross-platform `index.bin`) |
| Re-run | a new transcription resets `speakerNames` (names are lost) | same |
| Backups | `backup_rules.xml` excludes the whole `file` domain | Application Support has `isExcludedFromBackup` |

**Diarizer**: nemo-x-asr-diarizer (Nemotron-3 Sortformer, streaming, 8 slots, 31-layer 512-d
transformer, AOSC speaker cache of 128 frames).
- Android ships the ggml path; iOS ships `litert_host`.
- Its only output is per-frame slot probabilities. The speaker cache stores **pre-encode** frames:
  a linear projection of 8 stacked log-mel frames, not identity vectors.
- No embedding is exposed, and slot k in meeting A has no relation to slot k in meeting B.

**Reusable pieces already in the tree**:
- `cpp/nemo/kaldi_fbank.cpp`: Kaldi 80-bin fbank, written for a CAM++ port.
- `cpp/nemo/litert_graph.cc`: a one-graph LiteRT CPU wrapper.
- `EmbedRegistry`: a pinned, sha-checked model download pattern.
- `LibraryIndexer`: an idle-time per-meeting background job with fingerprints.
- `SessionIndex`: a cross-platform binary sidecar format.
- CrispASR also carries `titanet`, `wespeaker` and a `speaker_db`, and its privacy posture matches
  §2.6. These are not needed, but they are a useful reference.

**Open issue that touches this (尾牙 over-count, memory `open-issues-weiya-ask`)**:
- The demo meeting shows 6–8 speakers for 3 TTS voices because the voices drift.
- This design does not change diarization, so it cannot make that worse.
- It gives the user a cheap fix: naming two clusters "Mark" offers to merge them (§2.1).
- The per-speaker vectors of Phase 2 could later power a "these two speakers sound alike — merge?"
  hint. Such a hint must stay a hint, because in the 尾牙 case the drifted voice really does
  differ from itself (ECAPA 0.09–0.21).

## 2. User stories and UX (same behaviour on Android and iOS)

### 2.1 Rename sheet (Phase 1)

Tap a speaker label (transcript, stats legend) → a bottom sheet (Android `ModalBottomSheet`, iOS
`.sheet` with `.medium` detent) replaces the inline field / alert:

```
 Who is 語者 2?                                  [ 0:42 ▶ ]  ← plays a 3-5 s clean clip of this speaker
 ┌──────────────────────────────────────────┐
 │ 🔍 Type a name…                           │  ← filters the list; Enter = "Add ‹text›"
 └──────────────────────────────────────────┘
 Suggested by voice                            (Phase 2 only, when voice is on)
   ● ● ●  Mark                 Weekly sync · Oct 14
   ● ● ○  Lin Wei              3 meetings
 Recent
   Mark · Lin Wei · 王經理 · Me
 All people (A–Z / 注音)
   …
 + Add "‹typed text›" as a new person
 ─────────────
   Clear name (back to 語者 2)
```

- **Order without voice**:
  1. people already in this meeting are shown greyed, with "already 語者 3";
  2. then the 5 most recently used;
  3. then the rest by number of meetings, then alphabetically (Han by pinyin/注音 is a nice to
     have, a code-point sort is fine first).

  Typing filters with a case- and width-insensitive prefix/substring match. On Chinese, the match
  is script-insensitive (OpenCC 繁↔简): "陈" finds "陳".
- **Pick a name** links the speaker to that person. It is undoable, like a merge.
- **Pick a person already linked to another speaker of the same meeting**: a dialog says "Mark is
  already 語者 3 here. Merge 語者 2 into 語者 3?" The choices are Merge (the usual undoable
  merge), "Both are Mark" (two links, allowed), or Cancel.

  This is the low-cost path for over-segmentation (尾牙).
- **Add a new name that equals an existing person's name** (after normalisation): "Is this the
  Mark from ‹last meeting title›?" The choices are "Same person" or "Someone else named Mark". The
  second creates a new person, and both are then shown with a disambiguator (§2.4).
- **Clear name**: unlinks the speaker. The person stays in the directory.

### 2.2 People directory (Phase 1)

Reached from Settings → "People" and from a person's long-press in the sheet. A list of people
with their number of meetings and last meeting. A person page has:
- **Rename** (everywhere): it changes the display in every linked meeting at once (§3.3) and
  re-indexes those meetings' search units.
- **Merge into…**: two entries that are the same human ("Mark", "Mark Chen"). The links move,
  and the voice samples are pooled (Phase 2).
- **Split** (from a meeting): "this meeting's Mark is someone else". This re-links one speaker to a
  new person.
- **Delete**: it removes the person, and the linked speakers keep the name as a plain unlinked
  label, so the transcripts do not change. A second option, "also clear the name in N meetings",
  turns them back to 語者 n.
- **Meetings** with Mark: a tappable list that opens the meeting at Mark's first line.
- **Voice** (Phase 2): "Voice known from N meetings (M min)", "Forget Mark's voice".

### 2.3 Search, filter, Ask (Phase 1)

- **Library**: a "People" filter chip row (people sorted by meetings count) narrows the list to
  meetings where the person is linked. Text search keeps working as today, because chunk lines
  already contain `Mark: …`.
- **Ask "what did Mark say about X"**: the change is in retrieval, not in the prompt, to keep the
  model-agnostic harness.
  - When the question contains a directory name or alias (exact, script-folded match), the meetings
    linked to that person are routed first.
  - Within them, CHUNK passages that contain that person's utterances get the existing note bonus.
  - The excerpts already carry `Mark:` labels. No prompt change.
- **Meeting deleted**: its links disappear with it.
  - The person stays even at 0 meetings, shown as "no meetings" (deletable).
  - The meeting's voice samples are deleted with the meeting (privacy, §2.6).

### 2.4 Same name, different people

People are identified by id, never by name. When two entries share a normalised name, every list
shows a disambiguator: an optional user note ("Mark · Sales"), or else the last meeting title and
date. Ask routing then treats an ambiguous name as both people, and the answer's citations
disambiguate.

### 2.5 Voice suggestions (Phase 2)

- **Placement**: "Suggested by voice" heads the sheet with at most 3 people whose score clears the
  *possible* threshold. People already linked in this meeting are excluded.
- **Assignment**: the scores of all speakers of the meeting are assigned jointly (Hungarian / greedy
  on score), so one person is not the top suggestion for two speakers.
- **Confidence**: 3 dots.
  - ●●● *likely*: score ≥ T_high **and** margin ≥ 0.05 over the next person.
  - ●●○ *possible*: T_low ≤ score < T_high.
  - Under T_low nothing is shown, and the list falls back to recency.
- **No percentages**: cosine scores are not probabilities, and a number invites false precision.
- **Speech floor**: a speaker with less than 10 s of clean speech gets "Not enough speech to compare
  voices".
- **Never applied silently. The user taps the name.** This is justified by the error modes:
  - suggestions are right ~100 % on AMI, but the thresholds move with the channel (§3.6);
  - a wrong silent label propagates into the summary, exports and Ask;
  - a confirmed label feeds the person's voice, so one silent error would also poison future
    matches.
- **Optional "auto-label" (Phase 3)**: at the end of processing, speakers with a *likely* match
  show the name in a provisional style ("Mark?", dashed chip), and one tap confirms. Provisional
  names:
  - are not written to the session file;
  - are not used in exports, Ask or search;
  - never update a voice.

  A banner "3 speakers recognised — review" leads to a review sheet with confirm-all.
- **Re-run of a meeting**: speaker ids change. Links are carried over by time overlap (§3.4), which
  needs no ML. With voice on, the meeting's own previous vectors are matched first. That match is
  very reliable (same audio), so it can be applied with an undo snackbar: "Names kept from the
  previous run · Undo".

### 2.6 Privacy (biometric-like data) — proposed defaults

- **Opt-in**: "Recognise voices" is **off by default**. Turning it on shows one screen:
  - what is stored ("a numeric summary of each named person's voice, not the audio");
  - where (this phone only, never uploaded, not in backups or exports);
  - what it is used for (suggestions when naming speakers);
  - the duty to tell participants where the law requires it (GDPR Art. 9, BIPA-like rules).

  The opt-in also downloads the model (~14 MB).
- **Scope**: matching is done only against people the user named. There is no "who is this
  stranger" search and no clustering of unnamed voices across meetings. The vectors of unnamed
  speakers stay in their meeting's sidecar and are deleted with it.
- **Controls**:
  - per person: "Forget voice";
  - global: "Delete all voice data", which removes `voices.bin` + every per-meeting sidecar and
    turns the switch off;
  - turning the switch off asks "keep or delete the voice data".
- **Exports, share sheet and session files** never carry vectors. Names travel as plain strings, as
  today. Person ids are written in the session file (§3.3) but are meaningless on another device.
- **Backups**: both platforms already exclude the whole data directory. The new files live inside
  it.
- **Names without voice** (Phase 1) are ordinary meeting content and get the same local-only
  treatment.

## 3. Data model and storage

### 3.1 Global people directory

One file per platform, same JSON on both (like `index.bin`'s cross-platform rule): Android
`filesDir/people/people.json`, iOS `Application Support/people/people.json`. Atomic writes, one
process-wide lock (Android: same rule as `SessionLibrary.metaLock`; iOS: an actor).

```json
{
  "version": 1,
  "people": [
    {
      "id": "6f1c…",                 // UUID, never reused
      "name": "Mark",                // display name, in the user's script (OpenCC re-render like speaker names)
      "note": "Sales",               // optional disambiguator
      "aliases": ["Mark Chen"],      // from merges; used by search/Ask routing
      "created": 1792000000,
      "lastUsed": 1792600000,        // last link created/confirmed
      "voiceEnabled": true           // false = "never learn this voice" (e.g. a role like 主持人 / Host)
    }
  ]
}
```

Meeting counts are **derived** (from the per-meeting links), not stored, so they cannot drift.
Android keeps a small in-memory index `personId → meetings` built from `meta.json` (below); iOS
already loads every session.

Voice data lives in a **separate binary file** `people/voices.bin`, so that "delete all voice
data" is one file and Phase 1 never creates it:

```
"PMVP" u32 version | u32 count
count × { 16 B personId | u16 len + modelId | u16 dim | 16 B meetingId | u16 speakerId
          | f32 seconds | u8 confirmed | dim × f32 vector (L2-normalised) }
u32 crc32
```

- One **exemplar** per (person, meeting, model): the speaker's vector in that meeting. A person's
  voice = the seconds-weighted mean of their exemplars for the current model.
- Exemplars (not one running centroid) make these exact and cheap:
  - deleting a meeting removes its contribution;
  - "this wasn't Mark" removes one;
  - a model change marks all old exemplars stale;
  - top-k / max-exemplar scoring stays possible later.
- Size: 192 × 4 B ≈ 0.8 KB per exemplar, so 100 people × 20 meetings ≈ 1.6 MB. A cap of the newest
  30 exemplars per person and model keeps it bounded and tracks voices that change slowly.

### 3.2 Per-meeting link

The link is stored with the speaker name, in the existing structures (both read paths ignore
unknown keys, so older app versions keep working):

- Android `SpeakerName(name, confidence, reason, person: String? = null)`. The `speaker_names`
  object in the session blob gains `"person": "<uuid>"`. `SessionAutosave` does the same.
- iOS: `Session.speakerPeople: [String: String]?` (speaker index → person id) next to
  `speakerNames`. `SessionFile.Manifest.SN` gains `person`, so the `.m4a` round-trip is the same as
  Android's.
- Android `meta.json` gains `"people": ["<uuid>", …]`, so that the library filter and the
  directory counts do not open every `session.m4a`. It is written whenever names are saved, and
  rebuilt by the catch-up worker when it is missing.
- **The name stays denormalised in the meeting.** The meeting file stays self-describing (export,
  share, opened on another device, directory deleted).

### 3.3 Which name is shown, and global rename

- **Display**: `label(meeting, speakerId) = directory[link]?.name ?: speakerNames[id]?.name ?:
  "語者 n"`. It is resolved through one function per platform: Android `speakerLabel` already is
  "the one label resolver"; iOS has `Session.name(_:)`.
- **Global rename** therefore updates the screen instantly. A background pass then refreshes the
  denormalised names:
  - iOS rewrites the session JSON (cheap);
  - Android updates the autosave/blob lazily, the next time the meeting is saved for any reason,
    and at export, where the resolved name is used anyway;
  - the indexer sees a new fingerprint and re-embeds only that person's chunks (existing
    `textHash` reuse).
- **Merge of two persons**: the links of B point to A, B's name becomes an alias of A, and the
  exemplars move. Merge and delete are done in one directory transaction, and the per-meeting links
  pointing to a vanished id are healed lazily (a link whose person is missing reads as an unlinked
  name).

### 3.4 Keeping links right when speakers change inside a meeting

| Event | Link rule |
|---|---|
| Merge speaker A into B | B keeps its link. If A had a different person, the merge confirm already names both, and A's link is dropped. With voice on, A's exemplar is dropped, and B's is recomputed by the voice job. |
| Move one line | No link change. The vector is recomputed by the voice job if its clean-speech set changed (fingerprint of the speaker's utterance spans). |
| Speaker loses all lines | Drop the link (same as the name today). |
| Re-run / re-diarize the meeting | For each new speaker, the old speaker covering ≥ 60 % of its speech time **and** ≥ 50 % of the old speaker's time inherits the old link. Otherwise the name is dropped, and the sheet will offer it first ("was in this meeting"). Pure time overlap, no ML. With voice on, see §2.5. |
| Script change 繁↔简 | Directory names are re-rendered with OpenCC like the speaker names (`scriptConverted`). |

### 3.5 Migration (first launch of Phase 1)

1. Scan every meeting (Android: the library entries' blobs, in the indexer's catch-up lane; iOS:
   `LibraryStore.all()`). Collect the user-set names (`confidence == "user"`).
2. Normalise: trim, Unicode NFKC, case-fold Latin, OpenCC-fold Han to the UI script, collapse
   spaces. Skip names that equal a default label in any of the 3 languages ("Speaker 2",
   "語者 2", "说话人 2") or that are empty.
3. One person per distinct normalised name. All matching speakers are linked to it, and
   `lastUsed` = the newest meeting date.
   - **Why merge same names automatically**: the common case (one Mark in many meetings) is right,
     and the rare wrong case is fixed with Split.
   - **No dialog at migration.** A one-time card in the People screen says "We found 12 names in
     your meetings. Review".
4. Idempotent, with a `people.json` version flag. A crash mid-way is safe, because links are only
   added.

### 3.6 Voice data per meeting and model versioning

- **Per-meeting sidecar `voices.bin`** (Android `library/<id>/voices.bin`, iOS
  `Application Support/voices/<uuid>.bin`). It holds, for every speaker of the meeting, the vector
  and the seconds used, with the fingerprint of the utterance spans it came from. Same header as
  `index.bin` (model id, dim, fingerprint, crc).
  - It is **derived data**, regenerated from the audio when missing or stale, and deleted with the
    meeting.
  - It covers *all* speakers: the rename sheet ranks instantly for an unnamed speaker.
- **Registry entry** (model-agnostic, like `EmbedRegistry` / the model cards). `SpeakerEmbedSpec`
  holds:
  - id, files and sha256, revision, dim;
  - front-end (fbank bins, mean norm), window and min segment;
  - **calibrated thresholds** `tLow` and `tHigh`, plus the margin.

  Features read the thresholds from the spec, never constants.
- **A new model id** makes every exemplar and sidecar with another id stale:
  - stale exemplars stop being scored;
  - the voice worker recomputes the sidecars from the audio in idle time, then re-creates each
    person's exemplars from the meetings where they are linked and were confirmed;
  - no user action is needed, because the links are the ground truth and the vectors are a cache.
  - A meeting whose audio is gone (should not happen: audio is kept) just loses its exemplar.

## 4. Voice resemblance

### 4.1 Where the embedding comes from — measured, not assumed

Test: AMI meeting series ES2004, IS1009, TS3003, EN2002. The same 4 participants meet 4 times
(a–d), so there are 16 meetings and 16 people; EN2002c has 3 people.
- Excerpt: minutes 2:00–12:00, the `tools/nemo-eval` window.
- Profiles: per (meeting, speaker), the mean of L2-normalised segment embeddings over the first
  20 s / all of the speaker's clean single-speaker speech (segments ≥ 1 s, edges trimmed 0.1 s,
  ≤ 8 s pieces).
- Metrics:
  - **EER** over every cross-meeting profile pair;
  - **within-series EER**: same room and microphones, so the impostors are the 3 other people in
    the same meetings, the hard case;
  - **top-1**: enrol everyone from meeting a, probe b/c/d against all 16 enrolled people.

Script: `tools/speaker-id-eval/eval.py` (+ `sortformer_feats.py`, `README.md`). Host CPU only, no
phone used.

**Can the diarizer be reused?** Sortformer frame features were mean-pooled over the same segments
and centred on the global mean. They come from the real checkpoint, with the app's frontend and
pre-encode ported to torch. The ported head detects 74 % of reference speech frames at p > 0.5,
with 1.3 % false alarms on silence, so the port is sound.

| Features (close talk, oracle segments) | EER all, 20 s / all | EER within-series, 20 s / all | top-1 (from meeting a), 20 s / all |
|---|---|---|---|
| `pre` = speaker-cache content (stacked mel → 512) | 8.8 % / 7.3 % | 21.0 % / 21.9 % | 81 % / 88 % |
| layer 16 of 31 | 6.1 % / 7.3 % | 19.2 % / 14.7 % | 97 % / 97 % |
| final layer (512) | 39.7 % / 30.9 % | 44.4 % / 36.7 % | 31 % / 50 % |
| `encoder_proj` (192, feeds the slot head) | 42.6 % / 36.6 % | 46.9 % / 39.4 % | 22 % / 31 % |
| **CAM++ zh-en (3D-Speaker), for reference** | **0.0 % / 0.0 %** | **0.0 % / 0.0 %** | **100 % / 100 %** |

Far field (table mic, oracle segments): `pre` 17.6 % (within-series 29.7 %), layer 16 13–15 %
(20–22 %), final 29–40 %.

Reading:
- The deep layers encode *which slot* (arrival order in this window), not *who*. That is exactly
  what a permutation-resolved Sortformer is trained for.
- The shallow features mostly encode the room and the channel. They separate meetings, not people
  within a meeting.
- Exposing a middle layer would also mean changing both native backends: the ggml graph and the
  LiteRT export, which only output probabilities. **Conclusion: a dedicated speaker-embedding model
  is needed.** Its extra cost is small (§4.3).

### 4.2 Choosing the speaker model

Five off-the-shelf models were run through sherpa-onnx on the host, same protocol (rows = budget
"all" unless noted):

| Model (licence) | Params / fp32 size | Close, oracle: EER (within) | Far, app diarizer: EER (within), top-1 | Close→far channel change: EER (within), top-1 | Host RTF, 1 thread |
|---|---|---|---|---|---|
| **3D-Speaker CAM++ zh-en common-advanced** (Apache-2.0) | 6.9 M / 28 MB | 0.0 % (0.0 %) | 0.2 % (1.5 %), **100 %** | 0.0 % (0.0 %), 100 % | 0.04–0.09 |
| NVIDIA TitaNet-small (CC-BY-4.0, English data) | 10.0 M / 40 MB | 0.0 % (0.0 %) | 0.0 % (0.3 %), **100 %** | 1.5 % (2.7 %), 100 % | 0.03–0.07 |
| WeSpeaker ResNet34-LM VoxCeleb (CC-BY-4.0) | 6.6 M / 27 MB | 1.7 % (6.0 %) | — | — | 0.26 |
| 3D-Speaker ERes2NetV2 zh-cn (Apache-2.0) | 17.8 M / 71 MB | 1.3 % (1.6 %), app diarizer | not finished (too slow) | — | ≈ 0.23 (§4.6: 8× CAM++) |
| WeSpeaker CAM++ VoxCeleb (CC-BY-4.0) | 7.2 M / 29 MB | 17.4 % (27.7 %) ¹ | — | — | 0.09 |

¹ Almost certainly a front-end mismatch in that sherpa-onnx export: the different-speaker mean is
0.58, against ≈ 0.2 for the others. Not investigated, and not a candidate.

RTF is the host compute time per second of embedded speech. The host is an Intel Core Ultra 7
155H, one thread, under heavy load from other jobs, so treat it as an upper bound. The clean
per-segment timing is in §4.3.

**Choice: CAM++ zh-en common-advanced.**
- It is as accurate as TitaNet-small here, and the most robust to the channel change.
- It is trained on ~200 k Mandarin + English speakers, which matters because the app's users are
  mostly zh-TW, and AMI is English only.
- It is the smallest (6.9 M params, 192-d), and Apache-2.0 means no attribution screen.
- The tree already has its front-end (`kaldi_fbank.cpp`, built for a CAM++ port).
- A LiteRT export of a sibling checkpoint already exists (`campplus_cn_common_500f.tflite`, from
  VoxSum desktop).

TitaNet-small is the fallback. Because of the harness rule, swapping it in means a registry entry
plus a re-calibration, with no code change.

### 4.3 When and how vectors are computed

- **When**: in a background "voice" lane of the existing indexer worker (`LibraryIndexer` /
  iOS indexer), after a meeting is saved. It follows the same rules: never beside the speech engine
  or the reader, idle and battery gates, cancellable between speakers. A rename sheet opened before
  it ran computes that one speaker on demand (≤ 60 s of audio, a few seconds).
- **Input**: the meeting's audio, decoded again from `session.m4a` / the iOS audio file to 16 kHz
  mono, with the import gain rule. Audio is always kept, so the catch-up can backfill old meetings
  when the user opts in.
- **Clean speech only**: utterances of that speaker that do not overlap another speaker's turn,
  trimmed 0.1 s at each end, ≥ 1.5 s.
  - Take them **spread over the meeting**: round-robin over the meeting's thirds, longest first,
    which protects against drift (尾牙).
  - Stop at **60 s** per speaker (AMI: 20 s is already enough, and 10 s costs ≈ 1–3 points of
    within-series EER).
  - The floor is 10 s of clean speech, else no vector.
- **Model**: CAM++, 80-bin Kaldi fbank with per-segment mean normalisation, in fixed windows (e.g.
  4 s, to be re-validated with the harness) on LiteRT CPU with 2 threads. The output is the
  seconds-weighted mean of the window embeddings, L2-normalised.
- **Cost**: at most 60 s × speakers. Per-segment latency on the host (1 thread, clean run) is in
  §4.6. An A76 core is assumed ~2× slower, which gives **≈ 5–10 s of CPU for a 6-speaker meeting**
  in idle time. That figure is optimistic: the measured host cost (§4.6, ~28 ms per second of
  speech on one core) gives ≈ 10–25 s on one A76 core. Not measured on a phone yet.
- **Memory**: model ~14 MB fp16, RSS < 50 MB, loaded only in the lane.

### 4.4 Scoring, thresholds, updates

- **Score**: `s(speaker, person) = cos(v_speaker, centroid_person)`, where the centroid is the
  seconds-weighted mean of the person's current-model exemplars, *confirmed* ones only.
  - Then the joint assignment over the meeting's speakers (§2.5).
  - Top-k exemplar max-scoring is kept as a Phase 3 option, if channel spread shows up in real
    libraries. The exemplar store allows it with no format change.
- **Thresholds** (CAM++, from the AMI and AISHELL-4 numbers in §4.6, to be re-checked on the
  user's own data):
  - **T_low = 0.50**: different-speaker scores stay under 0.50 for 99 % of AMI pairs and 93 % of
    Mandarin same-room pairs. They reach 0.68 at worst, so "possible" can be wrong;
  - **T_high = 0.70**: above every different-person score measured (max 0.67 Mandarin, 0.68 AMI
    far field). The cost is that ≈ 5–30 % of true matches only show as "possible" (same-person
    5th percentile 0.63–0.78 depending on the condition);
  - **margin 0.05**.

  These go into the registry entry, not the code.
- **Updates only on user action**:
  - choosing a person in the sheet, while the speaker has a vector, adds/replaces that meeting's
    exemplar (confirmed);
  - accepting a suggestion is the same;
  - an unconfirmed suggestion changes nothing;
  - unlinking or "this isn't Mark" removes the exemplar.
- **Outlier guard**: if the new exemplar's cosine to the person's current centroid is < 0.3 (a
  role name like "Host" covering different humans, or a mis-merged cluster), the link is kept but
  the voice is not learned, and the user sees "This voice doesn't match Mark's other meetings —
  learn it anyway?".

### 4.5 Evaluation plan

- **Offline (before shipping)**:
  - `tools/speaker-id-eval` on AMI close + far, with the app's diarizer segmentation (done, §4.6);
  - AISHELL-4 Mandarin same-room impostors (done, §4.6);
  - to add: a larger AMI set (all scenario series, ~40 people) for the false-suggestion rate as the
    directory grows, the 4 s fixed-window LiteRT graph against the ONNX reference (cosine ≥ 0.99),
    and int8 against fp16.
- **On device**: one Note10+ run and one iPhone run for cost (lane time, RSS) on a 1 h meeting.
  Plus a parity check: the same audio must give the same vector on both platforms, cos ≥ 0.999.
- **In the field (DEV builds only)**: a local log of (score, rank, accepted?) per rename, to
  re-calibrate T_low/T_high on real phone recordings, mostly Mandarin. Nothing leaves the device.

### 4.6 Experiment numbers (host, 2026-10-17)

Setup and script: §4.1, `tools/speaker-id-eval/`. Far field = AMI `Array1-01` (one table
microphone), same 2:00–12:00 window, with the app's import gain rule.

| Condition (CAM++ zh-en) | Profiles | EER all | EER within-series | Same-person score, mean / min | Different-person score, mean / p99 / max | top-1 from meeting a | progressive top-1 ² |
|---|---|---|---|---|---|---|---|
| Close talk, oracle segments, 20 s | 53 | 0.0 % | 0.0 % | 0.84 / 0.66 | 0.15 / 0.45 / 0.59 | 100 % | 100 % |
| Close talk, app diarizer (59 clusters, purity 0.991), 20 s | 52 | 0.1 % | 1.3 % | 0.83 / 0.59 | 0.15 / 0.49 / 0.61 | 100 % | 100 % |
| Far field, oracle, 20 s | 53 | 0.0 % | 0.0 % | 0.81 / 0.62 | 0.20 / 0.47 / 0.59 | 100 % | 100 % |
| **Far field, app diarizer (63 clusters, purity 0.986), 10 s** | 53 | 0.2 % | 2.8 % | 0.77 / 0.55 | 0.19 / 0.48 / 0.60 | **100 %** | 100 % |
| **Far field, app diarizer, 20 s** | 53 | 0.2 % | 1.5 % | 0.81 / 0.57 | 0.20 / 0.49 / 0.64 | **100 %** | 100 % |
| Far field, app diarizer, all speech | 53 | 0.2 % | 1.5 % | 0.88 / 0.60 | 0.21 / 0.51 / 0.68 | 100 % | 100 % |
| Enrol close, probe far (channel change), 20 s | 53 | 0.0 % | 0.3 % | 0.78 / 0.57 | 0.17 / 0.46 / 0.57 | 100 % | 100 % |

² Progressive: enrolled from every earlier meeting of the series (a → b, a+b → c, a+b+c → d), as
the confirmed-exemplar design does.

AISHELL-4 (Mandarin, far field, channel 0 of the table array, 6 sessions, oracle segments). There
is no cross-session identity, so the proxy is: "same" = a speaker's first half against the second
half of the session; "different" = two speakers of the same session (same room, mic, language).

| Model (20 s profiles) | same: mean / min / p5 | different (same room): mean / p95 / max | EER | different ≥ 0.5 / ≥ 0.6 |
|---|---|---|---|---|
| **CAM++ zh-en** | 0.86 / 0.77 / 0.78 (25 pairs) | 0.28 / 0.51 / 0.67 (122 pairs) | 0.0 % | 7 % / 1 % |
| TitaNet-small | 0.85 / 0.74 / 0.77 | 0.35 / 0.56 / 0.76 | 0.4 % | 14 % / 5 % |

So Mandarin speakers in the same room score higher against each other than the AMI impostors do.
For CAM++, the different-person p95 is 0.51, against a p99 of ≈ 0.48 on AMI. The English-trained
TitaNet-small is clearly worse on Mandarin, which confirms the choice of CAM++.


Timing (host, 1 thread, idle run, median per segment): 
| Segment | CAM++ zh-en | TitaNet-small | ResNet34-LM | ERes2NetV2 |
|---|---|---|---|---|
| 2 s | 56 ms | 139 ms | 176 ms | 450 ms |
| 4 s | 111 ms | 91 ms | 374 ms | 933 ms |
| 8 s | 249 ms | 237 ms | 752 ms | 2092 ms |

CAM++ costs ≈ 28 ms of host CPU per second of speech, so 60 s × 6 speakers ≈ 10 s on one host
core. That makes the "≈ 5–10 s" phone estimate of §4.3 an optimistic **lower bound**: count
10–25 s on one A76 core, or about half of that on 2 threads. This is still idle-time
background work, but it must be measured on the Note10+ before Phase 2 is committed.


**What these numbers do and do not say.**
- They are **favourable conditions**: 16 people, a 10-minute window per meeting, recorded over a
  few days in one fixed room per series.
- Real libraries have more people and other rooms and phones. Some speakers say a few words, and
  some voices sound alike (family, the same TTS voice).
- **They cover English only for identity** (AMI). The Mandarin rows are a same-session proxy.
- The robust findings are three:
  1. the diarizer route does not work;
  2. a 7 M-param model is clearly good enough to *rank* known people;
  3. absolute scores move by ≈ 0.05–0.1 with the channel.

  That supports "suggest and rank, never auto-apply" and thresholds kept in the registry.

### 4.7 Failure modes

| Failure | Effect | Mitigation |
|---|---|---|
| Diarizer merges two people into one cluster | The vector is a blend, and the suggestion is weak or wrong | Clean-speech selection; the user confirms; outlier guard keeps a blend out of a person's voice |
| Diarizer splits one person (尾牙) | Two speakers both suggest Mark | Joint assignment; "Mark is already 語者 3 — merge?" |
| Little speech (< 10 s) | No vector | "Not enough speech to compare" |
| Channel change (phone in a bag, speakerphone, another room) | Scores drop ~0.1 | Conservative T_high; several exemplars per person; ranking still right |
| Similar voices (relatives, a TTS voice reused) | Wrong top-1 at "possible" level | Margin rule; never silent |
| Voice changes (illness, years) | Slow drift | Newest-30 exemplar cap; confirmations keep refreshing |
| Role names ("Host", "Me" on a shared phone) | Polluted voice | `voiceEnabled = false` per person; outlier guard |
| Model update | Old vectors unusable | Model id in every record; idle recompute from audio and links |

## 5. Phasing, effort, risks

Effort is for one developer on both platforms, including tests, the three string locales (en,
zh-TW, zh-CN via OpenCC) and the standing README/guide media refresh plus release (memory
`readme-refresh-at-end`).

**Phase 1 — directory + picker (no ML): ~8–10 dev-days**
- Model, store and migration: Android 1.5 d, iOS 1 d.
- Rename sheet (search, recent, add, already-in-meeting/merge prompt, clear): Android 1.5 d,
  iOS 1 d.
- People screen (rename, merge, split, delete, meetings list): Android 1.5 d, iOS 1 d.
- Library filter chips plus Ask name routing (retrieval only): 1 d for both.
- Re-run carry-over by time overlap: 0.5 d.
- Unit tests (migration, normalisation, link rules, overlap carry-over), device checks, media,
  release: 1.5 d.
- Risks:
  - the Android blob schema change (forward-compatible, but check `ReaderParityTest`/export tests
    for byte-exact expectations);
  - migration auto-merging two real "Mark"s, fixed by Split;
  - name normalisation across scripts.

**Phase 2 — voice resemblance (opt-in): ~12–15 dev-days**
- Export CAM++ zh-en to LiteRT (fixed 4 s window, fp16; check int8). Validate against ONNX. Publish
  to HF pinned with sha256. Registry entry + calibrated thresholds: 2 d.
- Native embedder (fbank + LiteRtGraph), JNI + the iOS C bridge, cross-platform parity test: 2 d.
- Voice lane in the indexer (decode, clean-speech selection, sidecar format, backfill on opt-in),
  both platforms: 3 d.
- Scoring, joint assignment, sheet section with confidence dots, exemplar updates, outlier guard,
  forget/delete controls, opt-in screen: 3–4 d.
- Device cost runs (Note10+, iPhone 14 Pro Max, short), harness extension (bigger AMI set,
  fixed-window graph), media + release: 2–3 d.
- Risks:
  - real-phone Mandarin calibration differs from AMI (mitigated by the DEV log and thresholds in
    the registry);
  - the CPU budget on 4 GB phones (the lane is idle-only and skippable);
  - store-review wording about biometric data (iOS privacy manifest: no collection, since nothing
    leaves the device).

**Phase 3 options (pick later, 2–5 days each)**:
- (a) Provisional auto-labels with a review banner (§2.5).
- (b) "These two speakers sound alike — merge?" inside a meeting, a hint for over-segmentation.
- (c) Live-capture suggestions, once a speaker has 10 s of speech.
- (d) Enrol "Me" from a short recording, since the phone owner is in most meetings.
- (e) Top-k exemplar scoring and per-person adaptive thresholds.
- (f) Directory export/import between the user's own devices (names, and voices only with an
  explicit second consent).

## 6. Open questions for the user

1. **Voice opt-in default**: off by default with an explanation screen (proposed), or on?
2. **Deleting a meeting deletes the voice sample it gave to a person** (proposed, privacy-first).
   OK, even if an old meeting was the only source of someone's voice?
3. **Migration**: merge identical names into one person automatically (proposed, with Split
   available), or ask name by name?
4. **Auto-label** (Phase 3a, provisional names to confirm): wanted, or suggestions only in the
   rename sheet?
5. **A dedicated "Me"** (the phone owner), enrolled once: wanted in Phase 2 already?
