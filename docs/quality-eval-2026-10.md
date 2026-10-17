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

### Ask after fixes (2026-10-17)

Change (Android `AskEngine` + iOS `AskSelect`, same parameters, `AskEngine.Retrieval`): **meeting routing**.
The meetings are ranked by reciprocal-rank fusion of two meeting scores (mean cosine of the 3 best
passages, sum of the 3 best BM25 scores); the top 2 give 16 candidates per list instead of 4 and their
passages lead the ranking. Overlapping transcript passages of a meeting merge into one excerpt (anchored
at its first line), a run of identical citations is collapsed, and the excerpt budget is 800 tokens
(what the old 8 passages filled on the median) instead of 1500. Nothing model-specific: no prompt or
card change.

Measured with the same suite and scorer, E2B on the host, the two retrievals asked **back to back per
question** (harness `variants`, order alternating) so the latencies share the machine load; judged by a
blind Opus judge over both runs shuffled together.

| Run (62 q) | Correct (keywords) % | Correct (judge) % | Partial (judge) % | Passage retrieved % | Answers citing % | Valid cites % | Right-source cites % (n) | Latency p50 / p90 s | First token p50 s | Prompt tokens p50 / mean / p90 |
|---|---:|---:|---:|---:|---:|---:|---:|---|---:|---|
| before | 50.0 | 45.2 | 12.9 | 56.9 | 72.6 | 100.0 | 73.6 (53) | 8.1 / 11.6 | 5.9 | 814 / 816 / 1067 |
| after | **62.9** | **56.5** | 12.9 | **65.5** | 82.3 | 100.0 | 68.8 (64) | 7.5 / 10.4 | 4.7 | 878 / 832 / 897 |

By language (judge): zh 43.8 → 59.4 % (retrieved 56.7 → 73.3 %), en 46.7 → 53.3 % (retrieved 57.1 → 57.1 %);
the 4 unanswerable questions are still declined. Paired deltas, after − before: total −1.0 s (median),
first token −1.0 s, prompt +4 tokens (median, +17 mean). The before run reproduces the table above
(keywords 50.0 vs 48.4 %, retrieved 56.9 % in both). Host timings, load ~8–20.

What did not help (retrieval only, no generation: `run_harness.sh retrieve` with `variants`; excerpt
tokens are those of the rendered excerpts, before the system prompt and question):

| Retrieval | Passage retrieved % | Excerpt tokens p50 / p90 |
|---|---:|---|
| before (8 passages, ≤ 1500 tokens) | 56.9 | 690 / 949 |
| + each hit widened by the next and previous chunk (≤ 1500) | 60.3 | 1306 / 1465 |
| 4 hits widened twice, ≤ 800 | 41.4 | 750 / 783 |
| budget 800 alone | 58.6 | 690 / 771 |
| a note replaced by the transcript chunk at its time | 60.3 | 946 / 1197 |
| chunks ≤ 75 s / 400 tokens (re-indexed) | 65.5 | 1018 / 1472 |
| chunks ≤ 75 s, ≤ 800 tokens | 62.1 | 758 / 776 |
| routing when one meeting holds 1.5–3× the fused score of the next | 56.9 (never triggers) | 690 / 949 |
| **routing top-2 meetings, ≤ 800 (shipped)** | **65.5** | ~780 / 798 |
| routing top-2, ≤ 900 + widening once | 69.0 | 886 / 898 |

- Widening finds the fact that sits just after a hit (5 of the 25 misses), but at a fixed budget it costs
  the other hits; the full run with routing + widening (≤ 900) was no more correct (61.3 % keywords) and
  +0.6 s slower (+106 prompt tokens). It stays available (`Retrieval.widen`, default 0).
- 60–90 s chunks help only because they hand over more text; at equal tokens they are no better, and
  they would force a re-index of every library. Not shipped.
- Most misses were ranking, not adjacency: before, only a third of the excerpts came from the asked
  meeting; after routing about 60 %. The routed meeting is the right one for 49 of 58 questions; the
  misses are mostly the three near-duplicate AMI remote-control meetings (English retrieval unchanged).
- Duplicate citations: no adjacent repeats occurred in either run; the collapse is covered by unit tests.

Devices (E2B, CPU):
- Note10+ (test app, same APK, DEV extra `--ez vox_ask_legacy true` for the old retrieval), 3 questions
  over the test library, two passes in alternating order:

  | Question | Prompt tokens before → after | Prefill s before → after | Total s before → after | Answer |
  |---|---|---|---|---|
  | 搬家要準備哪些事？ | 1016 → 887 | 6.6, 6.9 → 5.8, 5.6 | 18.5, 19.3 → 31.3, 31.4 | after: all 6 excerpts from the move meeting, a longer list (245 vs 119 answer tokens: decode time) |
  | 尾牙的頭獎是什麼？ | 910 → 875 | 6.3, 6.4 → 5.5, 5.7 | 9.0, 9.1 → 8.7, 9.0 | same right answer |
  | 車輛管理有什麼決定？ | 862 → 891 | 5.5, 5.9 → 5.7, 5.7 | 10.0, 10.7 → 8.7, 8.9 | both right (after adds the remaining reserve) |
- iPhone 14 Pro Max (DEV build, `VOX_ASK`): 車輛管理有什麼決定？ 823 prompt tokens, prefill 2.1 s, answer in
  4.8 s, right (one bus to the station, contact by next Wednesday); 尾牙的頭獎是什麼？ 884 tokens, prefill
  1.8 s, 2.8 s, right.

### Ask held-out check (2026-10-17)

The routing parameters (top-2 meetings, 16 candidates, 800 tokens) were tuned on `ask_suite_v1.json`. A
second suite, `ask_suite_heldout_v1.json` (28 questions over the same 10-meeting library: 12 zh, 12 en,
4 unanswerable; every answer span at least 60 s away from the v1 spans), was written afterwards and run
once, the two retrievals back to back per question (E2B host), judged blind with both runs shuffled together.

| Held-out (28 q) | Correct (judge) | Correct (keywords) % | Passage retrieved % | zh judge | en judge | unanswerable |
|---|---:|---:|---:|---|---|---|
| before (8 passages, <= 1500 tokens) | **18 / 28** | 67.9 | 70.8 | 6 / 12 | 8 / 12 | 4 / 4 |
| routing (shipped) | 13 / 28 | 53.6 | 58.3 | 5 / 12 (+3 partial) | 4 / 12 | 4 / 4 |

By keywords zh still gains (57 -> 64 %, retrieved 58 -> 75 %); English loses (79 -> 43 %, retrieved 83 -> 42 %):
in 3 of 12 English questions the router picked one of the near-duplicate AMI remote-control meetings
and the right one got no slot. The routed run was better on 2 questions, worse on 7.

**The v1 gain does not generalise.** Split by meeting, the v1 gain is uneven too (judged, half A of the
meetings 44.8 -> 62.1 %, half B 37.9 -> 44.8 %). Over both suites (82 answerable questions, judged) the two
retrievals tie: before 38, routing 40; zh favours routing (18 -> 22 of 42), English favours the old
retrieval (20 -> 18 of 40). Retrieval-only variants on both suites (1 or 3 routed meetings, 8 or 24
candidates, budgets 700–1000, keeping 1–4 passages of the other meetings) found none that wins on both.
Routing stays (a tie overall, better for Chinese, same prompt size); the known failure is a library of
near-duplicate meetings in English. Latencies of this run are not usable (host load 25–30).

### Ask answer language (2026-10-17)

Ask answers in the language of the question, not the display language. `MeetingLanguage` on the
question (4 units minimum; too short or mixed falls back to the display language) picks the card's
`ask.system[key]` for the first question of a conversation; a follow-up in another language keeps the
conversation's system prompt (the KV prefix is reused) and gets the card's `ask.answer_in[key]` appended
to the question (it also names the refusal phrase in that language). Unit tests on both platforms.

Host (E2B, 14 questions incl. 4 follow-ups in the other language, Chinese display): 14 / 14 answers in the
language of the question; 8 of the 10 first answers identical to those under the matching display
language (same prompt tokens; the other 2 differ by sampling). A first design (display-language system +
directive) answered 4 of 62 refusals in the display language and was dropped.
Devices (zh-TW display): Note10+ — "What is the grand prize at the year-end party?" -> English (a refusal:
the router sent it to the English meeting), 尾牙的頭獎是什麼？ -> Chinese, right; iPhone 14 Pro Max — the
same two questions and "When is the office move?" -> English, Chinese, English, 2–4 s each.

## 5. Top weaknesses and suggested fixes

Ranked by user impact. Fixes are suggestions only; nothing was changed in the product.

1. **The reader writes Chinese for English meetings.** All four AMI summaries/notes (E2B, and E4B on
   ES2004b) are in Traditional Chinese: the reader prompt and examples are Chinese-only
   (`system_prompt.txt`, model card `gemma4-meeting-agent-zh`). An English user gets notes they cannot
   use, and the keyword gate cannot even see English content.
   *Fix:* detect the transcript language (script ratio is enough for zh/en) and pass a language
   directive or an English prompt variant from the model card (keep it in the card, not in the
   harness); add one English meeting to `ReaderRegressionGate`.
   *Fixed 2026-10-17:* see §8 (notes, title and summary in English; Chinese meetings unchanged).
2. **Ask retrieval misses the passage in about half of the questions** (judged: 29 of 35 failures are
   retrieval; keyword check: the answer passage was among the excerpts for 57 % of answerable questions).
   Excerpts are short (median 144 chars) and the 1500-token budget is mostly unused; facts sit just
   after a retrieved chunk; similar meetings steal the slots.
   *Fix:* expand each hit with its next (and previous) chunk until the budget is used; give more slots
   to the top meeting once one meeting clearly dominates the fused ranking (meeting routing by title +
   summary first); test chunk 60–90 s. Measure with this suite (`ask_suite_v1.json`) before/after.
   *Done 2026-10-17* (section 4, "Ask after fixes"): meeting routing (top-2 meetings by passage evidence)
   at an 800-token budget: judged 45 → 56 %, retrieved 57 → 66 %, same prompt size and latency. Widening
   and 60–90 s chunks were measured and not kept. English near-duplicate meetings remain hard.
   *Held-out check:* the gain does not hold on a second suite (judged 18 -> 13 of 28; over both suites a
   tie, 38 vs 40 of 82): routing helps Chinese and hurts English near-duplicate meetings (section 4).
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
   *Fixed 2026-10-17 (in part):* timestamp tags are repaired; the agreement cue is applied to English
   meetings only (it lowered Chinese coverage), from the first window; the minutes proposal guard
   takes English cues from the card; see §8.

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
#   cfg "variants" {name: retrieval}: each question under each retrieval, back to back -> ask.<model>.<name>.json
tools/quality-eval/run_harness.sh retrieve <cfg> # retrieval only per variant -> retrieve.<name>.json ("dump": all candidates)
#   suites: app/src/androidTest/assets/ask_suite/ask_suite_v1.json, ask_suite_heldout_v1.json;
#   a question's "after": a first question asked in the same conversation (the scored one is a follow-up)
python3 -E -P tools/quality-eval/score_ask.py <suite> library.json ask.<model>.json [judge.json] > ask.json
python3 -E -P tools/quality-eval/report_tables.py asr|notes|ask ...
```
Config formats: see the harness header and `run_harness.sh`.

## 8. After fixes (2026-10-17)

Changes (Android and iOS, same model card):
- **Meeting language.** `MeetingLanguage` counts Han/kana characters against Latin words (a word weighs one
  character, at least 20 units) over the transcript read so far; `zh` or `en`. The card's `languages.en`
  adds a directive after each transcript window (write the NOTEs in English, tags unchanged), an
  agreement rule at the end of the reader's system prompt (agreed items are DECISION), English minutes
  headers and English title/prose prompts (same budgets). Chinese meetings read the upstream prompt
  unchanged: S_R004S01C01 gives byte-identical notes, title and summary to the baseline (E2B).
  The display language setting still only switches Traditional/Simplified script.
- **Timestamp tags in the summary** are checked against the notes' times: kept when known, snapped to a
  note within 30 s, dropped otherwise; lists and ranges are split, bare numbers dropped.
- **Titles** are cut at 40 characters, or at a word end within 60 for English.

Variants tried (E2B, host; keyword coverage / decisions on 4 AMI and 6 AISHELL-4 meetings):

| Variant | AMI cov / dec | AISHELL-4 cov / dec |
|---|---|---|
| baseline | 0.01 / 0.00 (notes in Chinese) | 0.70 / 0.80 |
| language directive only | 0.86 / 0.80 | unchanged |
| + agreement cue after each window, all languages | 0.77 / 0.69 | 0.63 / 0.83 |
| + agreement rule in the system prompt, all languages | 0.78 / 0.74 | 0.60 / 0.85 |
| **shipped**: directive + system rule, English only | **0.84 / 0.83** | unchanged (identical output) |

A directive written in English was ignored (notes stayed Chinese); the Chinese-written directive works.
Any agreement hint on Chinese meetings lowered AISHELL-4 coverage, so Chinese keeps the upstream prompt.

Blind LLM judgement (same protocol as §3, one round with baseline, the all-language variant and the
shipped card side by side; 69 AMI key points, 35 of them decisions/actions):

| AMI, E2B | covered / partial / missing | decisions+actions covered | DECISION notes agreed / proposed / wrong | contradictions | malformed tags |
|---|---|---|---|---|---|
| baseline | 25 / 30 / 14 | 6 | 7 / 3 / 1 | 8 | 4 |
| all-language rule | 30 / 24 / 15 | 10 | 11 / 2 / 2 | 6 | 0 |
| shipped | 28 / 30 / 11 | 8 | 9 / 3 / 1 | 9 | 0 |

The judge rates meaning, not language, so the baseline's Chinese notes still score; the gain for an
English reader is mostly that the notes are readable. Differences between the last two rows are within
one round's noise.

E4B (host, shipped card, 4 AMI meetings): all notes and summaries in English, keyword coverage / decisions
0.78 / 0.68 (baseline on ES2004b: 0.06 / 0.00, in Chinese; the other three were not run before).

Devices (E2B, 2026-10-17):
- Note10+: `ReaderRegressionGate` — English (IS1009c, new): recall 0.79, notes and summary English;
  move 0.92 and weiya 0.75, equal to the 2026-10-16 baseline. A 6-minute English clip (IS1009c 24:00–30:00)
  imported through the app: English title, 13 English notes, English summary, valid tags only.
- iPhone 14 Pro Max: the same clip gives an English title, 11 English notes and summary; a Chinese
  meeting (move) stays Chinese.

Follow-up (2026-10-17, second round):
- **First window.** The reader now waits for the language before its first prefill: the prefix
  (system prompt + language rule) is prefilled once `MeetingLanguage` has 60 units, or when the first
  window closes, whichever comes first; the lines fed meanwhile are held and prefilled afterwards in the
  same pieces, so the token stream and its chunking are unchanged. English meetings now read the agreement
  rule from the first window. Chinese meetings are byte-identical to the previous build (title, summary,
  minutes, notes, actions, raw prose) on L_R003S02C02, M_R003S05C01 and S_R004S01C01; AISHELL-4 keyword
  coverage / decisions 0.70 / 0.80, unchanged.
- **Proposal guard per language.** The card's `languages.<code>` may declare `proposal_cue` and `decided`;
  `languages.en` gives English tentative phrasings ("maybe", "should we", "how about", "not sure", a
  question mark...) and agreement words ("agreed", "decided", "will go with"...). Unit tests on both
  platforms. On the 4 AMI meetings the model's English DECISION/ACTION notes carried no tentative cue, so
  the guard demoted nothing in either run: it is a safeguard, not a measured gain.

Judged before/after (same blind protocol, before = the shipped card of §8, E2B host, 4 AMI meetings,
69 key points, 35 decisions/actions):

| AMI, E2B | covered / partial / missing | decisions+actions covered | DECISION notes agreed / proposed / wrong | ACTION notes task / wrong | contradictions | malformed tags |
|---|---|---|---|---|---|---|
| before | 28 / 30 / 11 | 8 | 10 / 2 / 1 | 3 / 1 | 10 | 0 |
| after | 30 / 25 / 14 | 10 | 11 / 2 / 2 | 5 / 0 | 8 | 0 |

Keyword coverage / decisions moved 0.84 / 0.83 -> 0.78 / 0.74 on the same meetings: the keyword and the
judged directions disagree, and both are within one run's noise (it matches the "all-language rule" row
above, which moved the same way). Read it as "no regression, small judged gain on decisions/actions".

Devices: Note10+ (test app) — the English clip (IS1009c 24:00–30:00) gives an English title, 14 English
notes, clean tags, no non-note lines; iPhone 14 Pro Max — the same clip gives an English title and 11 English
notes, one DECISION in the first window (2:03). Test meetings deleted afterwards.

Still open: one run per variant; E4B not rerun with the first-window change.
