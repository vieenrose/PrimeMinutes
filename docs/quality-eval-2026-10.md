# Quality evaluation on public meetings — 2026-10

Prime Minutes measured end to end on public multi-speaker meetings, on a Linux host, with the shipped
models and the app's own code paths: AISHELL-4 (Mandarin, 6 test meetings, 229 min) and AMI (English,
4 meetings, 149 min). Scope: transcription + speakers, the reader's notes, and Ask over the library.

**Headline**

| Area | AISHELL-4 (zh) | AMI (en) |
|---|---|---|
| ASR error rate (long-form, speaker-agnostic) | CER 18.5 % | WER 18.8 % |
| Speaker-attributed error (cpCER / cpWER) | 34.2 % | 29.8 % |
| DER, diarizer turns, collar 0.25 s, overlap scored | 21.5 % | 15.8 % |
| DER, transcript segments as shown in the app (c 0.25) | 18.3 % | 29.0 % |
| Speaker count exact (ignoring speakers < 2 % of time) | 3/6 (4/6) | 1/4 (3/4) |
| Notes: key points covered, judged (E2B / E4B) | 57 % / 65 % (+32 % / 25 % partial) | E2B 35 % (+39 % partial) |
| Notes: decisions+actions covered, judged (E2B / E4B) | 61 % / 74 % | E2B 14 % |
| Ask (E2B), correct, judged — 62 questions | 11/30 (+4 partial) | 12/28 (+4 partial) |

Everything ran on the host CPU, so the reader's and Ask's numerics are not bit-identical to a phone,
and every timing below is a host timing under heavy machine load (not a device latency).

## 1. Setup

| Piece | What ran |
|---|---|
| Code | branch `android` at 566c9470 (+ the harness below), Kotlin code on the JVM via Robolectric |
| ASR + speakers | `NemoStreamEngine`, backend `litert_host` (the NOGGML engine), the app's pinned tflite files (sha-checked), host `libLiteRt.so` 2.2.0 (same version as the AAR) |
| Import path | 16 kHz mono → `GainNormalizer` → 4096-sample blocks → `SilenceSkipper` → engine → final snapshot, times mapped back with `skipper.toOriginal` (as `ImportPipeline`) |
| Reader | `ReaderLane` with the model card, whole transcript, as `ReaderWorker.finishLane` / `ReaderRegressionGate`; E2B `gemma4-e2b-meeting-agent-zh-mobile-v1` (rev f4708617) and E4B `gemma4-e4b-meeting-agent-zh-mobile` (rev 70e095dc), 8 threads |
| Ask | a Robolectric library seeded with the 10 meetings (session.m4a with the embedded session + reading), indexed by `LibraryIndexer` with `embed-gemma2-270m` (rev c961c049), `AskEngine.ask` over the whole library, fresh conversation per question, E2B |
| Audio | AISHELL-4: channel 0 of the 8-channel array (far field). AMI: Mix-Headset (close talk mix) |

Harness: `app/src/test/java/studio/primeminutes/eval/QualityEvalHarness.kt` (skipped unless
`PM_EVAL_MODE`/`PM_EVAL_CFG` are set), run by `tools/quality-eval/run_harness.sh asr|reader|ask <cfg>`.
Host JNI libraries: `tools/quality-eval/build_host_jni.sh`; host diarizer CLI:
`tools/nemo-eval/build_host_noggml.sh` (+ `--backend/--xasr-tflite/--diar-tflite/--diar-pre` in
`nemo_eval.cpp`). `-PpmEval` turns on `isIncludeAndroidResources` (OpenCC tables). Data stays out of
the repo (`~/voxsum-models/eval-corpora`, ~3 GB).

Meetings: AISHELL-4 L_R003S02C02, L_R004S06C01, M_R003S02C01, M_R003S05C01, S_R003S03C01,
S_R004S01C01; AMI ES2004b, ES2004d, IS1009c, TS3003c.

## 2. Transcription and speakers

Scorer `tools/quality-eval/score_asr.py`. Long-form ER: all reference tokens in time order vs the whole
transcript (CER on characters for zh, WER for en); lower-cased, punctuation and AISHELL markup removed,
English fillers removed on both sides, numbers not normalized (so "30" vs "三十" counts as an error —
a few points of the zh CER). cpER: each reference speaker's text vs one app speaker's text, optimal
mapping. DER: own frame scorer (10 ms), overlap included, optimal mapping, collar 0.25 s (md-eval
convention) and 0; "turns" = the diarizer's own turns (`nemo_eval_noggml`), "transcript" = the app's
utterance segments with their speaker. Speaker counts include speakers holding < 2 % of the time in
parentheses.

| Meeting | Lang | Min | Gain | ER (CER/WER) % | sub/del/ins % | cpER % | DER transcript c0.25 % | DER turns c0.25 % | DER turns c0 % | miss/FA/conf (turns c0.25) % | Speakers ref → app (≥2 %) |
|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---|
| L_R003S02C02 | zh | 37 | 4.2 | 16.1 | 7.7/7.0/1.5 | 20.9 | 14.0 | 36.1 | 43.1 | 0.3/9.7/26.1 | 6 → 7 (6) |
| L_R004S06C01 | zh | 37 | 2.3 | 12.2 | 7.3/2.8/2.1 | 62.2 | 29.7 | 24.8 | 30.1 | 0.0/7.9/16.9 | 7 → 5 (5) |
| M_R003S02C01 | zh | 39 | 4.9 | 26.7 | 10.6/14.8/1.4 | 46.5 | 25.5 | 23.4 | 30.1 | 0.8/8.0/14.5 | 6 → 5 (4) |
| M_R003S05C01 | zh | 39 | 12.0 | 31.3 | 11.7/17.3/2.2 | 47.7 | 21.8 | 29.7 | 39.0 | 0.8/18.0/10.9 | 6 → 6 (6) |
| S_R003S03C01 | zh | 38 | 5.9 | 10.5 | 4.5/5.0/0.9 | 13.0 | 8.6 | 7.5 | 18.0 | 0.0/6.3/1.1 | 5 → 5 (5) |
| S_R004S01C01 | zh | 39 | 2.9 | 10.0 | 5.3/4.2/0.5 | 13.2 | 10.4 | 8.1 | 12.5 | 0.4/7.0/0.7 | 5 → 5 (5) |
| **AISHELL-4 (6)** | | 229 | | **18.5** | | **34.2** | 18.3 | **21.5** | 28.7 | | exact 3/6 (4/6) |
| ES2004b | en | 39 | 10.6 | 18.2 | 5.6/11.7/0.9 | 24.4 | 18.4 | 14.4 | 25.3 | 1.3/12.0/1.1 | 4 → 6 (4) |
| ES2004d | en | 37 | 10.6 | 23.8 | 5.5/17.6/0.7 | 45.0 | 32.7 | 23.7 | 38.0 | 1.9/19.7/2.1 | 4 → 5 (4) |
| IS1009c | en | 30 | 1.0 | 14.8 | 5.5/8.4/0.9 | 20.3 | 18.0 | 11.9 | 22.6 | 0.1/11.4/0.4 | 4 → 4 (4) |
| TS3003c | en | 43 | 7.5 | 16.3 | 6.4/8.0/1.9 | 25.7 | 43.3 | 13.0 | 23.3 | 3.5/9.2/0.3 | 4 → 5 (5) |
| **AMI (4)** | | 149 | | **18.8** | | **29.8** | 29.0 | **15.8** | 27.3 | | exact 1/4 (3/4) |

Notes
- **Far field hurts.** S rooms (small, few overlaps) are at 10 % CER; the M rooms are at 27–31 %,
  dominated by deletions (15–17 %). ES2004d (much overlap, fast exchanges) has 17.6 % deletions.
- **Speaker merges cost more than recognition**: cpCER 62 % on L_R004S06C01 (7 speakers → 5) and
  46–48 % on both M rooms, while their speaker-agnostic CER is 12–31 %.
- **False alarm dominates DER**: segments and turns bridge pauses (FA 6–20 %); on TS3003c the
  transcript segments reach 43 % DER from 36 % FA while the diarizer's own turns are at 13 %.
- **Instability**: L_R003S02C02 has 26 % confusion on the diarizer turns but 3 % on the transcript
  segments; the two runs differ only by small input differences (gain-applied audio through the CLI vs
  the harness). Not investigated further.
- Host RTF of the import path was 0.42–0.50 with four meetings in parallel on a machine at load ~30;
  this is not a device figure (Note10+ measured 0.15–0.27 earlier).

Published reference points (not the same protocol, for orientation only):

| System | AISHELL-4 | AMI |
|---|---|---|
| AISHELL-4 paper baseline, speaker-independent CER (oracle segments) | 32.6 % | |
| AISHELL-4 paper baseline, speaker-dependent cpCER-like (full pipeline) | 41.6 % | |
| pyannote 3.1 DER (no collar, overlap scored) | 12.2 % | 18.8 % (IHM) |
| **Prime Minutes** CER/WER · cpER · DER turns c0 | 18.5 % · 34.2 % · 28.7 % | 18.8 % · 29.8 % · 27.3 % |

Recognition is far better than the AISHELL-4 paper baseline; diarization trails pyannote 3.1 by ~10–16
DER points at collar 0 (mostly false alarm, see above).

## 3. Notes (reader)

References: `tools/quality-eval/refs/<meeting>.json` (spec `tools/quality-eval/SPEC.md`).
AISHELL-4 has no summaries, so each meeting's 10 key points (kind decision/action/figure/topic/problem,
with a time span and keyword groups) were written by a Claude Opus pass over the gold transcript
(`gold_transcripts.py`: one `[mm:ss-mm:ss] speaker: text` line per reference utterance). AMI key points
(16–19 per meeting) map the corpus' abstractive annotation (abstract, decisions, actions, problems) to
the same format. Two scores:
- **keyword rule** (`score_notes.py`, the `ReaderRegressionGate` rule): a key point is covered when every
  keyword group appears in title + summary + actions + notes (zh in Simplified). Cheap and repeatable,
  but blind to meaning and language.
- **judged** (an Opus judge, per key point covered / partial / missing; a decision presented only as a
  proposal is "partial"; files in `work/judge_notes/`).

App transcripts (what a user gets), judged:

| Meeting | Model | Covered / partial / missing | Decisions+actions covered |
|---|---|---|---|
| L_R003S02C02 | E2B · E4B | 5/4/1 · 4/5/1 | 2/3 · 1/3 |
| L_R004S06C01 | E2B · E4B | 5/3/2 · 9/1/0 | – |
| M_R003S02C01 | E2B · E4B | 6/3/1 · 6/2/2 | 4/6 · 6/6 |
| M_R003S05C01 | E2B · E4B | 5/5/0 · 4/4/2 | 1/5 · 1/5 |
| S_R003S03C01 | E2B · E4B | 7/2/1 · 8/1/1 | 3/5 · 5/5 |
| S_R004S01C01 | E2B · E4B | 6/2/2 · 8/2/0 | 4/4 · 4/4 |
| **AISHELL-4 (60 points)** | **E2B · E4B** | **34/19/7 · 39/15/6** | **14/23 · 17/23** |
| ES2004b | E2B | 4/9/4 of 17 | 0/8 |
| ES2004d | E2B | 3/4/9 of 16 | 0/9 |
| IS1009c | E2B | 11/6/2 of 19 | 3/9 |
| TS3003c | E2B | 6/8/3 of 17 | 2/9 |
| **AMI (69 points)** | **E2B** | **24/27/18** | **5/35** |

Keyword rule, same outputs: AISHELL-4 mean coverage E2B 70 %, E4B 78 %; decisions+actions 83 % / 87 %
(the rule accepts a decision written as a proposal). On AMI the rule gives 0–6 % because **every AMI
summary was written in Traditional Chinese** (E2B on all four, E4B on ES2004b), so English keywords
cannot match — the judged numbers above are the meaningful ones.

**ASR errors vs reader errors.** E2B was rerun on the *gold* transcripts of 5 meetings (M_R003S02C01,
M_R003S05C01, S_R004S01C01, ES2004d, IS1009c), same judge:

| Transcript | Covered / partial / missing (72 points) | Decisions+actions covered (33) |
|---|---|---|
| App ASR transcript | 31 / 20 / 14 | 12 |
| Gold transcript | 32 / 24 / 9 | 13 |

A perfect transcript changes little: the notes' limits come from the reader, not from the 18 % CER.
(Keyword rule on the three zh meetings: 0.73 app vs 0.80 gold, within single-run noise.) The gold
transcript does not fix the language problem either: ES2004d and IS1009c were again summarized in
Chinese.

Observations (judge + reading the outputs)
- Both models tag nearly every note `(PROPOSAL)`, including what the meeting agreed; this is most of
  the decision loss on AMI (ES2004b 0/8, ES2004d 0/9).
- Figures are right when present, but secondary numbers are dropped (the 20-yuan fine, 22.3 %, 1.8 m,
  299 yuan); E4B keeps more of them.
- 4 contradictions in 16 outputs, e.g. ES2004d E2B says the cost came in under budget (it exceeded it);
  M_R003S05C01 E2B gives the yearly plan as 1000 yuan instead of 365; E4B turned "100 → 300 yuan"
  into "100 g → 300 g".
- E2B prose has malformed timestamp tags: `[5:6]`, `[7:8]`, `[8:03, 11:04, 7]` (and `[21]`, `[13]` in
  the gate sanity run). None from E4B.
- Host vs device: on the two `ReaderRegressionGate` meetings the host recall differs from the Note10+
  baseline by up to ±0.25 per meeting (E2B move 0.67 vs 0.92, weiya 0.58 vs 0.75; E4B move 1.00 vs 0.75,
  weiya 0.75 vs 0.92). A single-meeting gate threshold is within run-to-run noise.

## 4. Ask

Suite: `app/src/androidTest/assets/ask_suite/ask_suite_v1.json` (v1, 62 questions: 30 zh in
Traditional Chinese with ui `zh-TW`, 28 en, 4 unanswerable). Each has the expected answer, the source
meeting and time span, and keyword groups; built by `tools/quality-eval/build_ask_suite.py` from the
reference files. All 10 meetings are in the library at once, so every question also tests meeting
routing. Scorer `tools/quality-eval/score_ask.py`:
- correct (keywords): every keyword group of the expected answer in the answer (unanswerable: a refusal phrase);
- correct (judge): an Opus judge with the expected answer (ASR homophones of the right fact accepted),
  with a cause for each miss (retrieval / reading / asr / suite);
- retrieved: an excerpt handed to the model is from the right meeting and holds the answer's keywords;
- cites: `[[entry@sec]]` tags — valid = names an excerpt of this answer; right-source = that excerpt is
  from the expected meeting and holds the answer (or overlaps its span).

| Run | Subset | n | Correct (keywords) % | Correct (judge) % | Partial (judge) % | Passage retrieved % | Answers citing % | Valid cites % | Right-source cites % | Latency p50 / p90 s | First token p50 s |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|
| E2B | all | 62 | 48.4 | 43.5 | 12.9 | 56.9 | 72.6 | 100.0 | 75.0 | 14.0 / 22.6 | 10.6 |
| E2B | zh | 32 | 40.6 | 40.6 | 12.5 | 56.7 | 68.8 | 100.0 | 78.6 | 9.6 / 15.8 | 7.4 |
| E2B | en | 30 | 56.7 | 46.7 | 13.3 | 57.1 | 76.7 | 100.0 | 71.4 | 18.6 / 24.5 | 14.4 |
| E2B | unanswerable | 4 | 100.0 | 100.0 | 0.0 | – | 0.0 | – | 0.0 | 9.6 / 9.6 | 8.7 |

Judged causes of the 35 answerable questions not fully correct: **retrieval 29** (25 wrong, 4 partial),
**reading 6** (2 wrong, 4 partial), ASR 0 as the main cause (recognition errors such as "一八米" for
1.8 m or "幺幺零" for 110 were read correctly), suite 0. When an excerpt overlapping the expected span was
retrieved (33 questions), about 27 were answered correctly. Details in `work/ask_e2b/judge_detail.json`.

- **Retrieval is the bottleneck.** 8 excerpts are handed over (median 144 characters each — chunks are
  ≤ 40 s / 200 tokens — so the 1500-token budget is far from used). Five questions got no excerpt from
  the target meeting; often the chunk ends just before the fact (L_R003S02C02-q1 fine, M_R003S05C01-q4
  point validity, TS3003c-q3 "for whom", IS1009c-q6).
- **When retrieval misses, the model refuses (12) or answers a neighbouring question (~9)**, often from
  another meeting with a similar topic (ES2004d-q2 and TS3003c-q1/q7 answered from ES2004b; the bedding
  offer attributed to women's wear). The three AMI remote-control meetings are near-duplicates in topic,
  which makes meeting routing hard — a realistic case for a user with a series of meetings.
- Some answers restate the question with a citation (M_R003S02C01-q2, S_R004S01C01-q5, IS1009c-q7).
- Citations: 100 % syntactically valid (they always name an excerpt of the answer), 75 % point to a passage
  of the right meeting that holds the answer; duplicates such as `[[x@879]], [[x@879]]` occur.
- All 4 unanswerable questions were declined without a citation.
- Latency (host, load ~15–30, E2B, app default threads): p50 14 s, p90 23 s, first token p50 11 s; English
  questions are slower (p50 19 s) than Chinese (10 s). Not a device figure.

## 5. Top weaknesses and suggested fixes

Ranked by user impact. Fixes are suggestions only; nothing was changed in the product.

1. **The reader writes Chinese for English meetings.** All four AMI summaries/notes (E2B, and E4B on
   ES2004b) are in Traditional Chinese: the reader prompt and examples are Chinese-only
   (`system_prompt.txt`, model card `gemma4-meeting-agent-zh`). An English user gets notes they cannot
   use, and the keyword gate cannot even see English content.
   *Fix:* detect the transcript language (script ratio is enough for zh/en) and pass a language
   directive or an English prompt variant from the model card (keep it in the card, not in the
   harness); add one English meeting to `ReaderRegressionGate`.
2. **Ask retrieval misses the passage in about half of the questions** (judged: 29 of 35 failures are
   retrieval; keyword check: the answer passage was among the excerpts for 57 % of answerable questions).
   Excerpts are short (median 144 chars) and the 1500-token budget is mostly unused; facts sit just
   after a retrieved chunk; similar meetings steal the slots.
   *Fix:* expand each hit with its next (and previous) chunk until the budget is used; give more slots
   to the top meeting once one meeting clearly dominates the fused ranking (meeting routing by title +
   summary first); test chunk 60–90 s. Measure with this suite (`ask_suite_v1.json`) before/after.
3. **Speaker attribution: merges, over-split and instability.** cpCER is ~2× CER on far-field
   AISHELL-4 (34 % vs 18.5 %); L_R004S06C01 merges 7 speakers into 5 (cpCER 62 %); speaker count exact
   in 4/10 meetings. The same meeting gave 26 % vs 3 % confusion with slightly different input.
   *Fix:* tune the clustering threshold / speaker-count estimate on these 10 meetings (AISHELL-4 dev
   is available too), and look for the source of run-to-run differences (input gain? block alignment?).
4. **Segments bridge pauses (false alarm) and far-field speech is deleted.** DER is FA-dominated
   (TS3003c transcript DER 43 % with 36 % FA); far-field M rooms lose 15–17 % of characters to deletions
   (CER 27–31 %), ES2004d 17.6 % deletions in overlap.
   *Fix:* clip segment times to the VAD/diarizer speech regions before display; check whether
   `SilenceSkipper` drops quiet far-field speech (rerun the M rooms with the skipper off — not tested
   here); gain staging on very quiet input (M_R003S05C01 needed ×12).
5. **Reader decisions and fidelity.** Agreed decisions are logged as `(PROPOSAL)` (AMI decisions+actions
   judged 5/35; AISHELL-4 E2B 14/23); secondary figures are dropped; 4 contradictions in 16 outputs;
   E2B emits malformed timestamp tags (`[5:6]`, `[8:03, 11:04, 7]`, `[21]`). Host and device recall differ
   by up to ±0.25 on a single meeting, so the one-meeting gate is within noise.
   *Fix:* validate/repair `[m:ss]` tags against the transcript's line times after generation (drop or
   snap invalid ones); in the card's prompt, add a cue for agreement markers ("OK, let's go with",
   "就這樣定了") → DECISION; gate on the mean over ≥ 4 meetings (this set's refs can serve).

Also found while building the harness (engineering, not quality):
- Two `MfaEngine`s in one process crash (SIGSEGV in `i8_attn` `Run` `_omp_fn.4`): the fused-attention op
  is not reentrant across engines. The app runs one engine at a time today, but a reader and Ask in
  parallel would hit it. *Fixed 2026-10-16:* the op locks its shared scratch (as the iOS copy already did)
  and `MfaEngine` computes one engine at a time process-wide (`MfaCompute`); harness mode `engines`
  (two E2B engines on two threads, 3 rounds) gave wrong replies before, identical replies after.
- Native `printf` follows the process locale on glibc: with a decimal-comma locale the JNI segment
  encoding writes "0,000" and `parseSegments` throws `NumberFormatException` (the harness forces
  `LC_ALL=C.UTF-8`). Android's bionic always formats with "." so phones should be unaffected; it matters
  for any desktop/host reuse of the engine (not verified on a device). *Fixed 2026-10-16:* the segment
  and stats encodings use integer formatting (`nemo/fixed_fmt.h`); the ASR mode under `fr_FR.UTF-8` now
  passes (it threw on "7,240" before), and the Kotlin timestamps fed to the model / LRC use `Locale.ROOT`.

## 6. Not verified / caveats

- No device run: all numbers are host (x86 CPU, LiteRT 2.2.0 host build). The reader and Ask numerics
  differ from the phone (see the gate sanity check); timings are host timings under load ~30.
- One run per configuration; the reader's single-meeting variance is about ±0.2 in recall.
- AISHELL-4 references (key points, questions) are LLM-written from the gold transcript; AMI key points
  come from the human abstractive annotation, AMI questions are LLM-written. Judges are an LLM too.
- E4B was not run on three AMI meetings (time); E4B Ask was not run.
- The turns-vs-transcript DER gap on L_R003S02C02 (diarization instability) is unexplained.
- Numbers are not normalized in CER/WER (digits vs words count as errors).

## 7. Reproduce

```
tools/quality-eval/build_host_jni.sh            # host libprimeminutes-mfa.so / -nemo.so
tools/nemo-eval/build_host_noggml.sh            # nemo_eval_noggml (diarizer turns)
tools/quality-eval/run_harness.sh asr    <cfg>  # -> <name>.asr.json
tools/quality-eval/run_turns.sh ...             # diarizer turns
python3 -E -P tools/quality-eval/score_asr.py <corpora> <out> [turns] > asr_scores.json
tools/quality-eval/run_harness.sh reader <cfg>  # -> <name>.reader.<model>.json
python3 -E -P tools/quality-eval/score_notes.py tools/quality-eval/refs <reader out> > notes.json
tools/quality-eval/run_harness.sh ask    <cfg>  # -> ask.<model>.json, library.json
python3 -E -P tools/quality-eval/score_ask.py <suite> library.json ask.<model>.json [judge.json] > ask.json
python3 -E -P tools/quality-eval/report_tables.py asr|notes|ask ...
```
Config formats: see the harness header and `run_harness.sh`.
