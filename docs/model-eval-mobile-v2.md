# Reader weights mobile-v2 vs mobile-v1 (E2B, E4B) — evaluation 2026-10-17

Directive: test first, integrate only on a clear win. **Verdict: no migration.** v2 is not a clear
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
| E2B v1 | 0.727 | 0.763 | 0.616 | 0.411 | 33.7 | 13 (3.7 %) | 2.9 | **3.1** | 12 | 10/10 |
| E2B v2 | 0.741 | 0.760 | 0.660 | 0.454 | 39.7 | 12 (3.0 %) | 3.2 | 2.4 | 11 | 10/10 |
| E4B v1 | 0.789 | 0.790 | 0.702 | 0.530 | 29.6 | 16 (5.2 %) | 3.4 | **3.2** | 16 | 9/10 (IS1009c: zh notes) |
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
