# Streaming ASR + diarization: where the notes lose quality, and what to change (2026-10-17)

Research only. Nothing here is shipped. The 尾牙 speaker over-count stays open until the user decides.
Annex: [survey-2026-10.md](survey-2026-10.md) (web survey of candidate models).
Scripts: `tools/asr-diar-research/`. Data: `~/voxsum-models/asr-research/` (host) and `ai-workstation:~/asr-research`.

Corpus: 10 meetings, 6 AISHELL-4 (zh) and 4 AMI (en). The ceiling and judging study uses 7 of them.
Reader: Gemma-4 E2B meeting agent mobile-v1 (host LiteRT harness). Judges: Claude Opus, blind, 2 shuffles per meeting.

## 1. Ranked recommendations

1. **Improve ASR before diarization (zh first).** Perfect ASR raises the notes' key-point coverage from 64 to 74 %
   and halves the errors. Perfect speaker labels change nothing measurable (§2).
   The cheapest ASR gain is an **X-ASR offline second pass** over the app's own utterances.
   - Same model family, int8 sherpa.
   - zh CER 18.5 → 17.0 (−8 % rel.).
   - Core-RTF ~0.03 on Zen5.
   - Catch: AMI en WER gets worse, 18.8 → 20.2, from extra deletions. So run it on zh meetings only, or fix the en deletions first.
   - Phone cost **not measured**.
2. **Decode thresholds 0.5 / pad 20 instead of 0.3 / pad 45.** It costs nothing: same graph, same phone time, measured (§4).
   - DER zh 14.8 → 9.8, en 17.0 → 12.0. The gain is in false alarms.
   - Transcript-level speaker confusion: confT en 3.4 → 2.6, zh 6.4 → 6.1 (noise level).
   - Speaker count at ≥2 % of speech: zh exact 6/6 → 5/6, en unchanged at 2/4.
   - This is the safest diarization change. It is **not** a fix for the 尾牙 over-count. That was never tested on the 尾牙 recording.
3. **spkcache 264 (re-exported f380 graph).** This is the best diarization accuracy measured, at **2.2× the diarizer time on the phone** (§4).
   - With t05p20: DER zh 8.9, en 9.9; en confusion 0.3 %; confT en 1.1 %.
   - Speaker count: en 4/4 exact (≥2 %), but zh drops to 3/6 (the c264 runs under-count zh speakers). So it is a mixed result for the over-count question.
   - Worth an A/B on the 尾牙 recording itself before any decision.
4. **CAM++ post-meeting re-assignment (merge 0.7, margin 0.1).** It lowers confusion on every gain replica (zh 10.2 → 7.1 in the worst replica, en up to 2.8 → 1.2). Miss rises by about 1 point.
   - The app already ships CAM++ (People directory), so the model is in the APK.
   - Phone cost: ~0.06 × audio duration (2 threads), about 3.5 min for a 1 h meeting, after the meeting.
   - It needs new code (embedding every turn, centroid relabel). It is a second step after 2/3.
5. **Not recommended now:**
   - FireRed2-AED: best zh (14.9 CER on meetings, 9.3 MER ASCEND), but core-RTF 0.4–1.4 on Zen5. Infeasible on a phone.
   - Qwen3-ASR-0.6B: worse than shipped on meetings (zh 19.3).
   - SenseVoice, FireRed-CTC, Paraformer, Dolphin, Nemotron-3.5: worse, or broken on English.
   - FunASR-nano: 19.6 / 25.2.

Since the notes barely depend on speaker labels, items 2–4 matter for the transcript view and for speaker attribution, not for summary quality.

## 2. Ceiling: what perfect ASR / perfect speakers buy (Plan A)

Setup:
- Inputs for 7 meetings (4 zh, 3 en):
  - `app_app`: the shipped app transcript and speakers;
  - `gold_app`: gold text, app segmentation and speakers;
  - `app_gold`: app text, gold speakers;
  - `gold_gold`: gold text and gold speakers.
- The reader produces the notes.
- Two blind Opus judges per meeting (`judge_prompt.md`) score key-point coverage, errors and usefulness against the gold transcript.
- n = 14 judgements per condition.

| cond | key points % | errors | major | asr_term errs | speaker errs | usefulness 1-5 |
|---|---|---|---|---|---|---|
| app_app | 63.9 | 6.86 | 1.36 | 2.71 | 0.07 | 2.86 |
| gold_app (perfect ASR) | 73.8 | 3.71 | 0.93 | 0.29 | 0.36 | 3.43 |
| app_gold (perfect speakers) | 64.3 | 6.86 | 0.79 | 3.07 | 0.21 | 3.07 |
| gold_gold | 76.3 | 3.29 | 0.64 | 0.29 | 0.29 | 3.71 |

By language (key points % / errors / usefulness):

| | app_app | gold_app | app_gold | gold_gold |
|---|---|---|---|---|
| zh (8) | 61.9 / 8.25 / 2.62 | 74.2 / 2.88 / 3.75 | 66.9 / 7.12 / 3.25 | 73.2 / 2.62 / 3.75 |
| en (6) | 66.5 / 5.00 / 3.17 | 73.5 / 4.83 / 3.00 | 60.9 / 6.50 / 2.83 | 80.5 / 4.17 / 3.67 |

- ASR is the bottleneck. On zh, perfect ASR alone reaches the gold_gold level.
- Speaker errors in the notes are rare in every condition (< 0.4 per output). The notes rarely attribute.
- Gold speakers still change the output somewhat, because segmentation changes what the reader sees. That effect is noisy and not consistently positive.
- Caveats:
  - 7 meetings only.
  - One reader (E2B mobile-v1).
  - The judges are LLMs, though the two shuffles agree closely per meeting.
  - The en gold_gold advantage over gold_app (80.5 vs 73.5) suggests that speakers matter more on en; that is 3 meetings only.

## 3. ASR

### Meetings (sherpa int8 replicas on the app's VAD segments; zh CER / en WER %, after t2s + an2cn)

| tag | zh | en | notes |
|---|---|---|---|
| app (shipped, on device) | 18.47 | 18.77 | |
| x480 sherpa replica | 18.03 | 18.34 | validates the replica |
| x1920 streaming | 16.83 | 17.72 | own segmentation, so not directly comparable on cp/confT |
| **xoff_app0** (X-ASR offline over app utts, pad 0) | **17.00** | 20.18 | en: more deletions |
| xoff_app (pad 0.2 s) | 17.44 | 21.22 | duplicates boundary words |
| xoff_vad | 17.81 | 18.33 | 7 of 10 meetings |
| FireRed2-AED (fraed_app) | **14.87** | 23.78 | core-RTF ~1.2–1.4 under load on Zen5 |
| Qwen3-ASR-0.6B | 19.31 | 22.56 | core-RTF ~0.6 |
| FireRed2-CTC | 18.39 | 34.12 | |
| FunASR-nano | 19.58 | 25.21 | |
| SenseVoice | 20.09 | 41.77 | |
| Paraformer | 32.44 | 297.9 | zh only |
| Dolphin | 24.68 | 99.8 | no en |
| Nemotron-3.5 | 34.61 | 32.65 | |

### Clip sets (MER %, core-RTF on 1 Zen5 thread)

| model | zhtwbench (9) | parl (200) | ASCEND (300) | RTF |
|---|---|---|---|---|
| x480 (old 0.8 s tail pad) | 8.80 | 22.87 | 12.72 | 0.07 |
| x480p (app's tail pad) | 8.73 | 22.87 | 9.95 | 0.18 |
| x1920p | 8.38 | 22.20 | 11.31 | 0.07 |
| xoff | 9.95 | **21.35** | 9.48 | **0.02** |
| FireRed2-AED | 8.62 | 21.67 | **9.31** | 0.4–0.6 |
| Qwen3-ASR-0.6B | 10.34 | 21.59 | 11.02 | 0.36 |
| SenseVoice | 12.34 | 22.70 | 13.32 | 0.03 |
| FunASR-nano | 13.11 | 23.28 | 12.21 | 0.04 |
| FireRed-CTC | 13.32 | 23.61 | 15.02 | 0.2 |
| Nemotron-3.5 (p) | 23.20 | 26.53 | 25.68 | 0.4–0.6 |

- The large ASCEND gain from x480 to x480p is a bug in my replica, not in the app.
  - My first runs used a 0.8 s tail pad. The app pads `kWindow*10 + 1000` ms (`xasr_host.cc`), and the p-runs match that.
  - The app is therefore already at the x480p level.

## 4. Diarization

### Accuracy (aw LiteRT replica, c25 DER %, 10 meetings)

| variant | zh DER (miss/FA/conf) | en DER (miss/FA/conf) | spk exact ≥2 % zh / en | confT zh / en (transcript) |
|---|---|---|---|---|
| shipped decode thr 0.3 pad 45 | 14.8 (0.3/8.7/5.8) | 17.0 (1.6/13.6/1.8) | 6/6, 2/4 | 6.4 / 3.4 |
| thr 0.5 pad 20 | 9.8 (1.4/2.3/6.1) | 12.0 (8.4/1.7/1.8) | 5/6, 2/4 | 6.1 / 2.6 |
| spkcache 264 + t05p20 | 8.9 (1.6/2.2/5.1) | 9.9 (8.0/1.7/0.3) | 3/6, 4/4 | 5.5 / 1.1 |
| t05p20 + CAM++ reassign | 8.9 (2.3/1.8/4.9) | 12.0 (9.5/1.5/1.0) | 4/6, 3/4 | 5.8 / 1.8 |
| c264 + t05p20 + reassign | 8.3 (2.5/1.8/4.0) | 11.4 (9.3/1.5/0.6) | 3/6, 4/4 | 5.0 / 1.5 |

How to read the table:
- confT is the share of reference tokens attributed to the wrong speaker after relabelling the app's utterances with each variant's turns (`relabel.py`, `score_asr.py`).
- Noise: a ±1 % input gain perturbation moves zh confusion between 5.1 and 10.2 %, and confT between 5.4 and 10.7 %. Differences under ~3 points are noise.
- The on-device shipped run gives confT 9.5 / 4.8, which is inside that band.
- On the zh speaker count, c264 under-counts in some meetings where the shipped decode is exact. This is not an over-count fix in general.

### On-device cost (Note10+, Exynos 9825, `taskset f0`, 2 threads, 600 s of ES2004d and L_R004S06C01, int8)

| leg | wall s / 600 s audio | cpu s | RTF |
|---|---|---|---|
| diarizer f190, spkcache 128, thr 0.3/pad 45 | 59.6 | 108.6 | 0.099 |
| diarizer f190, thr 0.5/pad 20 | 59.6 | 108.8 | 0.099 (decode only, no cost) |
| diarizer f380, spkcache 264, thr 0.5/pad 20 | 132.6 | 253.8 | **0.221 (2.2×)** |
| CAM++ fp32, 3 s pieces over all audio, 1 thread | 51.3–53.4 | 51–53 | 0.085–0.089 |
| CAM++ fp32, 2 threads | 32.3–35.8 | 59–64 | 0.054–0.060 |

- The result is identical on both meetings. Battery temperature was 37–40 °C, with a 90 s rest between runs.
- `engine.h` says spkcache 264 was only ~22 % slower in the old ggml build. With the LiteRT graph it is 2.2× here, because the f380 graph's attention window doubles.
- The diarizer runs alongside streaming ASR during recording, so 0.22 RTF on 2 cores matters. On the Reno7 (target) it is **not measured**.
- CAM++ reassign is a post-meeting pass: ~3.5 min per hour of audio at 2 threads (upper bound: it embeds all audio, the real pass embeds only speech turns).

## 5. Not verified

- No phone timing for the ASR candidates (the Note10+ ASR probe was not run). No timing on the Reno7 at all.
- No end-to-end "Plan D" run (xoff text + c264/reassign speakers through the reader). The ceiling suggests the gain would come from the ASR part.
- Nothing was tested on the 尾牙 recording.
- 7–10 meetings, 2 corpora, all far-field-ish. The DER references are md-eval style, not NVIDIA's.
- The CAM++ cost uses `spk_bench` (the app's SpeakerEmbedder, fp32 tflite, 3 s pieces), not the app's own call path.
