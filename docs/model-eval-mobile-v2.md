# Reader weights mobile-v2 vs mobile-v1 (E2B, E4B) — evaluation 2026-10-17, follow-up 2026-10-18

**Updated verdict (2026-10-18, after the model developer's feedback, §5–§8): switch E4B to mobile-v2
with the prose budget lifted to 1600 tokens; keep E2B on mobile-v1; lift the prose budget for the v1
cards too.** Two findings of the first report are withdrawn: the E2B "11–16 % slower" on the Note10+
was a measurement-order artefact (§5), and the summary drop was the 600-token prose cap, not the
weights (§6). The Ask Chinese regression (§2) stands, but it is not significant per size (§8).
Nothing is shipped: the default model and the android/ios branches are unchanged. The code is on
branch `mobile-v2-e4b` (§9), for the user to decide.

First report (2026-10-17), kept below as it was, with corrections marked:

Directive: test first, integrate only on a clear win. **Verdict (2026-10-17): no migration.** v2 is not a clear
win. It writes slightly more complete note journals, but its Ask answers in Chinese, its prose
summaries and its E2B speed on Android are all worse. v1 stays shipped; this branch holds only this report.

Weights: v2 graph `prefill_decode_fused.tflite` E2B df54bbbb…, E4B 2d346d0e…, v2 system prompt
044150e8… (repo `Luigi/gemma-4-E2B-meeting-agent-zh`). Embedders and tokenizer are identical to v1
(same sha). Harness: the app's own Kotlin reader and Ask (QualityEvalHarness, host JNI build of the
engine) at d0bf5665. The shipped card was used for both; for v2 it was a variant with
`reader.max_notes` 8, as integration doc 05b0d33b asks.

## 1. Notes quality (host, 10 reference meetings: 6 zh, 4 AMI en)

Two scorers. "Keyword" is score_notes.py, the gate rule. "Judge" is a blind Opus judge per meeting
over 4 shuffled outputs; coverage counts covered = 1 and partial = 0.5.

| | kw coverage | kw decisions | judge coverage | judge fully covered | notes/meeting | contradicted notes | title /5 | summary /5 | summary contradictions | language ok |
|---|---|---|---|---|---|---|---|---|---|---|
| E2B v1 | 0.727 | 0.763 | 0.616 | 0.411 | 33.7 | 13 (3.9 %) | 2.9 | **3.1** | 12 | 10/10 |
| E2B v2 | 0.741 | 0.760 | 0.660 | 0.454 | 39.7 | 12 (3.0 %) | 3.2 | 2.4 | 11 | 10/10 |
| E4B v1 | 0.789 | 0.790 | 0.702 | 0.530 | 29.6 | 16 (5.4 %) | 3.4 | **3.2** | 16 | 9/10 (IS1009c: zh notes) |
| E4B v2 | 0.810 | 0.855 | **0.727** | **0.580** | 39.9 | **7 (1.8 %)** | 3.5 | 2.8 | **5** | 10/10 |

- v2 gains 2–4 points of coverage. The gain is within meeting-to-meeting noise: 10 meetings, with per-meeting
  swings of ±0.2 in both directions. On E2B it comes from the English meetings (judge 0.58 → 0.65); on E4B from Chinese (0.73 → 0.77).
- v2 writes about 6–10 more notes per meeting. At max_notes 8 the cap drops 35–38 notes over 10 meetings
  (v1 at 12: 1 or none). More notes per window also pushes the prose summary (600-token cap, an open issue)
  to stop earlier. The judges score v2 summaries **0.4–0.7 lower** and often mark them as "stops at ~22–27 min".
- E4B v2 has clearly fewer contradicted notes (7 vs 16) and fewer summary contradictions (5 vs 16). This is
  its best point.
- Malformed timestamp tags (`tags_bad_raw`, all repaired before output): E2B 6 → 3, E4B 4 → 8 out of ~230.
- On iPhone, v2's first note on the move clip was garbled. On move, v2 tags dates as NUMBER where v1 writes ACTION.

## 2. Ask (host, 90 questions: suite v1 + held-out, the same library for all)

| | correct (kw) | zh | en | unanswerable refused | cite right |
|---|---|---|---|---|---|
| E2B v1 | **0.600** | **0.674** | 0.523 | 8/8 | 0.649 |
| E2B v2 | 0.556 | 0.587 | 0.523 | 8/8 | 0.661 |
| E4B v1 | **0.622** | **0.717** | 0.523 | 8/8 | 0.824 |
| E4B v2 | 0.578 | 0.630 | 0.523 | 8/8 | 0.851 |

Retrieval is identical (0.634), so the difference is the answering model. v2 loses 4 Chinese questions on
both sizes and is level in English. Host latency is not comparable (shared host, load 17–22).

## 3. Device cost, Note10+ (test app studio.primeminutes.androidtest, same APK, CPU)

`MfaEngineDeviceTest.longWindowSpeed`: 2935-token prompt, fresh weight cache, 2 runs each.

| | prefill tok/s | decode tok/s | VmHWM |
|---|---|---|---|
| E2B v1 | 165–171 | 10.8–10.9 | 1.39–1.47 GB |
| E2B v2 | 141–143 (**−16 %**) | 9.6–9.7 (**−11 %**) | 1.38–1.47 GB |
| E4B v1 | 46.5–47.6 | 5.3 | 2.94–3.02 GB |
| E4B v2 | 46.5–47.2 | 5.2 | 2.94–3.02 GB |

E4B costs the same. The E2B slowdown was reproduced in both runs. The file sizes are unchanged, so the
cause is unknown. Not verified: a 3rd run or a check of thermal state.
**Withdrawn 2026-10-18 (§5):** v1 was always measured first; in an alternating v1/v2/v2/v1 run v1 measured
last is as slow as v2 (139.6 vs 139.4–139.5 tok/s).
iPhone 14 Pro Max, E2B: v2 444–450 / 29.4–29.7 tok/s vs v1 447–469 / 30.1–31.0, with the same footprint (~−2 %).
iPhone clip import (end of prose): move 104 s v1 vs 122 s v2.
Doc §7 Reno7 reference (E2B 114–130 / ~9 tok/s, E4B 43 / ~4 tok/s): Note10+ is faster for E2B and on par for E4B.

`ReaderRegressionGate` recall on the device. v1 used its shipped card (max_notes 12); v2 was run at both 8 and 12.

| | move | weiya | english (ami_is1009c) | seconds (move/weiya/en) |
|---|---|---|---|---|
| E2B v1 @12 | 0.92 | 0.75 | 0.74 | 156/123/250 |
| E2B v2 @8 | 0.92 | 0.75 | 0.89 | 165/161/281 |
| E2B v2 @12 | 0.67 | 0.83 | 0.84 | 172/167/286 |
| E4B v1 @12 | 0.75 | 0.92 | 0.74 | 308/265/576 |
| E4B v2 @8 | 0.83 | 0.83 | 0.79 | 351/333/664 |
| E4B v2 @12 | 0.92 | 1.00 | 0.79 | 384/349/658 |

- The checklist §4 rule is "not worse, meeting by meeting". E2B v2 @12 fails it on move (0.67) and E4B v2 @8
  fails it on weiya. E2B v2 @8 and E4B v2 @12 pass. Each value is a single run with sampling, so ±1 key
  point is noise.
- 12 instead of 8 changes the outcome: E4B is better at 12, E2B is mixed. No single max_notes setting wins for both sizes.
- v2 makes the reader 6–25 % slower on the device: it writes more notes, and E2B decodes more slowly.
- Leaks [] everywhere. Language is correct on the device for all runs.

## 4. Recommendation

Keep mobile-v1 for E2B and E4B, and do not migrate. The reasons, in order:
1. Ask Chinese regression −9 points on both sizes; Ask is a user-facing feature.
2. Prose summaries are worse (judge −0.4/−0.7); v2's denser notes hit the open 600-token prose issue.
3. E2B on the Note10+ is 11–16 % slower, and the reader is slower on the device for both sizes.
4. The note coverage gain (+2–4 pts) is small and noisy, and the gate is not consistently "not worse".

What would change the verdict: E4B v2's lower contradiction rate is real and worth keeping in mind. A
re-test makes sense once the new summarizer (open long-prose issue) lands, or if upstream ships a v2 that
does not regress Chinese Ask. A migration would then follow checklist §7: new registry ids with v1 still
selectable, a per-model max_notes, the new E2B repo name, the iOS per-model promptSha, embedders reused by sha.

## Not verified
- Only one sampled run per meeting/config on the host and the device; no confidence intervals.
- No live-recording RSS or lag test (checklist §6) for v2, since there is no migration.
- No Android release-app or iOS E4B test. iOS covered only E2B (bench + 2 clips).
- Host timings are not comparable across configs: the host was shared, and the E2B v2 host run used 6 threads instead of 8.

Data: ~/voxsum-models/eval-corpora/work/mv2/ (r_*, ask_*, kw_scores.json, ask_scores_*.json,
judge/*/judge.json + judge_keys.json, n10/, ios/).

---

# Follow-up 2026-10-18: the developer's three points

Developer's claims: (a) the E2B v2 slowdown is not the model but context length (v2 writes 6–10 more
notes, so each window runs deeper in the context); (b) the summary drop is an artefact of the 600-token
prose cap; (c) E2B v2's contradiction rate was not given.

## 5. (a) Speed on the Note10+: not the model, and not context length either

Test app `studio.primeminutes.androidtest` 0.53.7 + branch `mobile-v2-e4b` (the androidTest
`ReaderRegressionGate` now logs every decode turn: prompt length, the prefill since the previous turn,
decode tokens and seconds, from the engine's counters). E2B, order **v1, v2, v2, v1**, weight caches
deleted before every run, fixed 90 s pause, same `move` meeting (v1 card max_notes 12, v2 8). Each run:
`longWindowSpeed` (fixed 2,935-token prompt) then the gate.

Fixed 2,935-token prompt (same length for both models):

| run | model | battery °C | prefill tok/s | decode tok/s |
|---|---|---|---|---|
| 1 | v1 | 40.0 | 153.0 | 8.6 (43 tok) |
| 2 | v2 | 41.8 | 139.4 | 9.6 (63 tok) |
| 3 | v2 | 42.7 | 139.5 | 9.6 (63 tok) |
| 4 | v1 | 43.4 | 139.6 | 9.5 (43 tok) |

Reader windows (`move`, 2 windows; prompt = tokens in the context when the window's reply starts):

| run | window | prompt | prefill tok, s, tok/s | decode tok, s, tok/s |
|---|---|---|---|---|
| 1 v1 | w1 | 2054 | 2054, 18.9 s, 108.7 | 400, 42.7 s, 9.36 |
| 2 v2 | w1 | 2054 | 2054, 18.3 s, 112.1 | 400, 42.9 s, 9.32 |
| 3 v2 | w1 | 2054 | 2054, 18.3 s, 112.1 | 400, 42.7 s, 9.37 |
| 4 v1 | w1 | 2054 | 2054, 18.3 s, 112.1 | 400, 42.4 s, 9.43 |
| 1 v1 | w2 | 1640 | 1146, 11.7 s, 97.8 | 315, 32.7 s, 9.63 |
| 2 v2 | w2 | 1547 | 1053, 10.6 s, 99.7 | 380, 39.1 s, 9.71 |
| 3 v2 | w2 | 1547 | 1053, 10.6 s, 99.5 | 380, 39.3 s, 9.66 |
| 4 v1 | w2 | 1640 | 1146, 11.4 s, 100.7 | 315, 34.3 s, 9.17 |
| 1 v1 | prose | 809 | 813, 5.7 s, 141.9 | 542, 53.3 s, 10.18 |
| 2 v2 | prose | 634 | 638, 4.3 s, 149.4 | 495, 48.1 s, 10.30 |

Gate wall time: v1 167 / 182 s, v2 165 / 171 s; recall 0.92 in all four runs (outputs identical run to run).

- **The model is not slower.** At the same prompt (w1, 2,054 tokens) v1 and v2 prefill at 108.7–112.1
  tok/s and decode at 9.32–9.43 tok/s. On the fixed 2,935-token prompt, v1 measured last (139.6) equals v2
  (139.4–139.5); only the first run of the session is faster (153). The first report's −16 % / −11 % came
  from measuring v1 first, on a cooler phone, every time.
- **Context length does not explain it either.** The first report's speed test used one fixed prompt for both
  models, so prompt length cannot have differed there. In the reader, v2 at max_notes 8 even has the
  *shorter* context on this meeting (w2 1,547 vs 1,640 tokens: 16 vs 23 notes). Decode speed falls only
  slightly with depth for both models (≈10.2–10.3 tok/s at 0.6–1.4k, 9.3–9.4 at 2.0–2.4k). What makes a v2
  reading longer, when it is, is the number of tokens it writes (w2: 380 vs 315), not the speed per token.
- Not verified: E4B (not re-run: it measured equal in the first report), longer meetings, a cooled-down
  phone (the rules forbid waiting for cooling).

## 6. (b) Summaries: the 600-token cap, confirmed

No segmented-prose draft was found: `git stash list` is empty (main repo and all its worktrees), the
unreachable commits hold no prose work, and no `*.patch`/`*.diff` about prose exists in the repo,
worktrees, `~/.claude/plans` or the eval dirs. It was written for this test instead (branch
`mobile-v2-e4b`, commit 207ca4d0, off unless a card asks for it): a journal longer than
`prose.segment_tokens` is summarized in up to `segment_max` parts of consecutive notes, one fresh reply
with the full budget each; part 1 reads the card's prompt, later parts `segment_prompt` ("continue, do not
introduce the meeting again", with the part's time range); the paragraphs are joined in order. A merge
pass by the model was not used: it would be capped again.

The cap: all 40 reader-run summaries (4 configs x 10 meetings) end mid-sentence at the cap; re-run in
the harness they take 590–599 of 600 tokens. v2 spends them faster: the last time cited averages 72 %
(E2B) and 71 % (E4B) of the last note's time, against 95 % / 92 % for v1.

Re-run from the same saved journals (harness `prose` mode; host, CPU): **0** = the reader run's summary
(cap 600); **L** = cap lifted to 1600 tokens (one reply; the notes still fit the 4k context); **S** =
segmented (segment_tokens 600, segment_max 4, cap 600 per part). Blind Opus judge per meeting over the 12
summaries (seg/judge/, instructions there): key-point coverage from the summary alone (covered 1, partial
0.5), completeness, readability, overall score (1–5), contradictions with the transcript.

| | time covered | chars | parts | judge coverage | score /5 | completeness | readability | contradictions |
|---|---|---|---|---|---|---|---|---|
| E2B v1 0 | 0.95 | 1443 | 1 | 0.509 | 2.4 | 3.0 | 3.0 | 9 |
| E2B v1 L | 1.00 | 1733 | 1 | 0.566 | 2.8 | 3.5 | 2.7 | 11 |
| E2B v1 S | 0.96 | 2058 | 2.0 | 0.627 | 3.1 | 3.9 | 2.8 | 13 |
| E2B v2 0 | 0.72 | 1449 | 1 | 0.432 | 2.0 | 2.1 | 2.6 | 6 |
| E2B v2 L | 1.00 | 2103 | 1 | 0.654 | 3.1 | 3.9 | 2.4 | 9 |
| E2B v2 S | 1.00 | 2515 | 2.6 | 0.682 | 3.0 | 4.1 | 2.3 | 11 |
| E4B v1 0 | 0.92 | 1444 | 1 | 0.587 | 2.7 | 3.0 | 3.7 | 16 |
| E4B v1 L | 1.00 | 1723 | 1 | 0.653 | 3.2 | 3.9 | 3.6 | 18 |
| E4B v1 S | 1.00 | 2142 | 2.0 | 0.683 | 3.0 | 3.9 | 2.8 | 22 |
| E4B v2 0 | 0.71 | 1453 | 1 | 0.507 | 2.4 | 2.3 | 3.3 | 4 |
| **E4B v2 L** | 1.00 | 2065 | 1 | 0.717 | **4.0** | 4.5 | **3.7** | 8 |
| E4B v2 S | 1.00 | 2516 | 2.5 | **0.746** | 3.8 | **4.6** | 3.1 | 10 |

(time covered = last cited time / last note time; chars = summary length, zh ~800–1,300, en 2,500–5,400.)

- **The developer is right.** With the cap lifted, v2 replies end on their own (no part reached its cap)
  and cover the whole meeting; v2 then beats v1 on the same size: E4B L 4.0 vs 3.2 (7 meetings better, 0
  worse), E2B L 3.1 vs 2.8 (3 better, 0 worse). v2's summary contradictions stay lower (E4B 8 vs 18).
- **The cap hurts v1 too**: lifting it raises E4B v1 2.7 → 3.2 and E2B v1 2.4 → 2.8 (5 meetings better,
  1 worse, each).
- **Segmented vs cap lifted, on 26–40-minute meetings**: a bit more coverage and completeness, but less
  readable (repeated introductions, longer) and not a better overall score (E4B v2 S vs L: 1 better, 3
  worse). Cap lifted is the simpler fix at these lengths. Segmenting matters where one reply cannot fit:
  with a 1600 budget the notes are compacted to ~2.4k tokens, i.e. meetings well over an hour.
- Cost: more output. Host prose time E4B v2 L 134 s vs S 175 s per meeting; on a phone a 1,000–1,600-token
  prose reply takes ~3–5 min on E4B (5.2 tok/s) and ~1.5–2.5 min on E2B (10 tok/s), against ~2 / ~1 min
  today. Not measured on a device.

## 7. (c) E2B v2 contradiction rate (first report's judge, notes journal)

Contradicted notes / notes, per meeting:

| meeting | E2B v1 | E2B v2 | E4B v1 | E4B v2 |
|---|---|---|---|---|
| L_R003S02C02 | 0/27 | 1/32 | 0/20 | 0/31 |
| L_R004S06C01 | 2/32 | 1/30 | 2/25 | 0/40 |
| M_R003S02C01 | 1/32 | 1/42 | 3/27 | 2/38 |
| M_R003S05C01 | 2/36 | 3/44 | 0/31 | 1/42 |
| S_R003S03C01 | 0/31 | 0/38 | 1/30 | 0/38 |
| S_R004S01C01 | 1/30 | 0/37 | 0/28 | 1/35 |
| ES2004b | 0/41 | 1/53 | 4/37 | 0/53 |
| ES2004d | 4/43 | 2/46 | 3/41 | 1/43 |
| IS1009c | 1/33 | 1/37 | 2/25 | 2/39 |
| TS3003c | 2/32 | 2/38 | 1/32 | 0/40 |
| **total** | **13/337 (3.9 %)** | **12/397 (3.0 %)** | **16/296 (5.4 %)** | **7/399 (1.8 %)** |

E2B: 13 → 12 contradicted notes for 60 more notes; the rate goes 3.9 % → 3.0 %, within noise (per-meeting
changes of ±1–2). E4B: 16 → 7 is the clear gain. (The first report printed 3.7 % and 5.2 % for v1: wrong
denominators; the counts were right.)

## 8. Ask, Chinese, paired

Same 90 questions, per question v1 correct / v2 correct: E4B zh 4 lost, 0 gained (exact McNemar p = 0.12);
E2B zh 6 lost, 2 gained (p = 0.29); English 0/0 and 1/1. Not significant per size; the direction is the
same on both. It is the remaining cost of v2 and should be watched (not re-run: the Ask harness has no seed).

## 9. Recommendation (2026-10-18)

1. **E4B → mobile-v2, with the prose budget at 1600 tokens.** Summary score 4.0 vs 3.2 for E4B v1 at the
   same budget, 7/0 meetings; contradicted notes 7 vs 16; same speed and memory on the Note10+; reader gate
   passes at max_notes 8 except weiya 0.83 vs 0.92 (one sampled run, ±1 key point). Cost: Ask zh −4
   questions (not significant), longer prose time.
2. **E2B stays mobile-v1.** The speed objection is gone and v2 summarizes slightly better once uncapped
   (3.1 vs 2.8), but its notes are not more accurate (3.0 vs 3.9 %, noise), Ask zh is −6/+2, and the gate
   on move was 0.67 at max_notes 12. No clear win; re-evaluate with the next weights.
3. **Lift the prose cap for v1 too** (600 → 1600 in the v1 card): +0.4–0.5 summary score on both sizes.
   Segmented prose is not needed for 40-minute meetings; keep it in the code (off) for meetings whose
   notes no longer fit one reply, and evaluate it on 60–90-minute meetings before turning it on.
   The user kept the long-meeting summary as an open issue for the new summarizer: this is their call.

Branch `mobile-v2-e4b` (from origin/android 1e265164, not merged, not released, default unchanged):
- 207ca4d0 segmented prose (card fields `segment_tokens`, `segment_max`, `segment_prompt`, off in the
  shipped card), harness `prose` mode, per-turn probe in `ReaderRegressionGate`; unit tests.
- dcb7543e E4B mobile-v2 as new id `gemma4-e4b-meeting-agent-zh-mobile-v2` (repo
  Luigi/gemma-4-E4B-meeting-agent-zh-LiteRT @389bc2f9, `mobile-v2/mfa`, prompt 044150e8), card
  `gemma4-meeting-agent-zh-v2` (max_notes 8); the E4B chip picks v2, E4B v1 stays selectable while chosen
  or installed; files identical to an installed model's (v1 embedders, tokenizer) are copied and
  sha-checked instead of downloaded.
- 02b94ec9 v2 card prose budget 1600.
- Unit tests: 393 pass, 1 skipped (the harness). Not done: the iOS port (same card file, ModelStore entry,
  per-model promptSha, no mfa-gpu for v2), the v1 card budget change (left for the user), an on-device
  download/reuse test, device prose timing, README/guide (Settings chip changes).

Data: ~/voxsum-models/eval-corpora/work/mv2/seg/ (cards, cfg_*, p_*/ outputs, judge/, judge_sum_keys.json,
sum_scores.json), n10/ctx/ (device turns, speed, runs).

## 10. Integration (2026-10-18): E4B mobile-v2 replaces E4B mobile-v1

Settings: v2 card `gemma4-meeting-agent-zh-v2`, **max_notes 12, prose budget 1600**; segmented prose off.

Host, 10 meetings, blind Opus judge (QualityEvalHarness is deterministic: two runs of the same config gave
identical notes and summaries, so one run per config is shown and the spread is 0):

| E4B config | coverage | summary /5 | title /5 | contradicted notes | summary contradictions | language |
|---|---|---|---|---|---|---|
| v1 shipped (600) | 0.675 | 2.80 | 3.6 | 14/296 (4.7 %) | 12 | 9/10 |
| v1, 1600 | 0.675 | 3.20 | 3.6 | 14/296 (4.7 %) | 13 | 9/10 |
| v2, 8 notes, 1600 | 0.723 | 3.90 | 3.7 | 4/399 (1.0 %) | 5 | 10/10 |
| **v2, 12 notes, 1600** | **0.743** | **3.90** | **4.0** | **4/422 (0.9 %)** | **5** | **10/10** |

12 notes: notes dropped by the cap go from 39 to 6; v2@12 beats shipped v1 on 9 of 10 meetings (TS3003c 3 vs 4).

Note10+ (test app, ReaderRegressionGate, order v1, v2, v2, v1, 90 s pauses; the two runs of each model
gave the same recall):

| | move recall / time / prose | weiya | AMI IS1009c (EN, 29 min) | VmHWM | prefill / decode |
|---|---|---|---|---|---|
| v1 | 0.75 / 315–366 s / 117 s | 0.92 / 314 s / 97 s | 0.74 / 687 s / 140 s | 2.88 GB | 47–48 / 4.9–5.4 tok/s |
| v2 | 0.92 / 438–459 s / 155 s | 1.00 / 407 s / 133 s | 0.79 / 823 s / 207 s | 2.89 GB | 48 / 5.3–5.4 tok/s |

v2 writes more notes (23 vs 15 on move), so reading takes ~30 % longer; the prose cites the last note on
both. Same speed and memory.

Migration (verified on the Note10+): a saved E4B v1 choice reads as v2; v1 keeps reading until v2 is
installed; the existing prefetch downloads v2 on Wi-Fi (2.3 GB net: the embedders and tokenizer are copied
from v1 and sha-checked; Android refuses app hard links, so they are copies); v1 is deleted once v2 is
installed and has built its weight cache.

E2B (measured, not switched): v1 vs v2, both at 1600 — coverage 0.641 vs 0.653, summary 3.30 vs 3.40, title
3.2 vs 3.5, contradicted notes 7/337 (2.1 %) vs 7/397 (1.8 %), 3 meetings better / 3 worse / 4 equal. No
clear win.

40-minute meeting on the Note10+ (M_R003S05C01, 37.5 min, `-e utts`): v1 32 notes, 746 s reading, prose 161 s
(748 chars, cites up to 37:12 of 37:27); v2 47 notes, 1064–1075 s, prose 277–279 s (1412 chars, cites the last
note). The fourth (v1) run had no v1 left: the reclaim had deleted it once v2 had built its cache. Host, 76-min
synthetic journal (94 notes): one 1600-token reply cites up to 74:12 and ends on its own, so segmented prose
stays off.

Ask (90 questions, host): E4B v1 → v2 zh 0.717 → 0.630, en 0.523 → 0.523. Lost zh: two false "not mentioned"
(L_R004S06C01-q4, M_R003S05C01-q3), one ASR homophone copied (M_R003S02C01-q5), one other excerpt
(S_R004S01C01-q5); none gained. No common cause a card change could fix, so the Ask card is unchanged.

E2B on the Note10+ (v1 at 12 notes vs v2 at 8 notes, prose 1600, order v1, v2, v2, v1): move 0.92 / 0.92,
weiya 0.75 / 0.75, AMI 0.74 / 0.89; reading 166–187 s / 176–180 s; same VmHWM (1.36 GB) and speed
(138–144 tok/s prefill, 9.7 decode). Ask E2B v1 → v2: zh 0.674 → 0.587, en 0.523 → 0.523 (7 lost, 3 gained).
E2B stays mobile-v1.
