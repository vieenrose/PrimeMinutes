# X-ASR streaming 480 ms -> punct int8 with a latency setting (160 / 480 / 960 / 1920 ms, default 1920)

Status: implemented on branches `xasr-1920` (Android) and `xasr-1920-ios` (iOS). Research and export tools: branch
`xasr-offline-import` (`tools/xasr-litert/`, `docs/design/xasr-offline-import.md`).
User decisions: runtime LiteRT only; int8 unless float is MUCH better; offline pseudo-streaming failed the 5 s
display bar (word-to-screen p50 9.7-13.5 s), so the plan is to change the streaming checkpoint. Offline second pass
only for imports, and only if it clearly wins (not decided).

## 1. Model
- `sherpa-onnx-x-asr-1920ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05` -> torch (`recon_from_onnx.py`)
  -> litert_torch -> dynamic wi8 OCTAV (`export_s1920.py`), the recipe of the shipped 480 ms graph.
- Files: `xasr_stream1920_punct_q8.tflite` 154 783 344 B (sha256 cb739c21...), `xasr1920_host.bin` 16 497 008 B
  (sha256 2fdfc889..., same layout as xasr_host.bin). Same vocabulary as the shipped model (punct included).
- Chunk geometry: 205 fbank frames in, 48 encoder frames out per step, read from the graph (XasrLiteRT) and from
  chunk_ms (xasr_host.cc); engine.cpp takes chunk_ms from the graph, so the latency estimate follows.

## 2. Accuracy (host harness = the app's import path; 6 AISHELL-4 zh CER / 4 AMI en WER; cpER = speaker-attributed)
| | zh ER | en ER | zh cpER | en cpER | en confT |
|---|---|---|---|---|---|
| shipped 480 ms | 18.47 | 18.78 | 33.92 | 26.87 | 3.6 |
| 1920 ms, before the timing fix | 17.22 | 17.64 | 32.95 | 30.84 | 3.6 |
| **1920 ms + timing fix (this branch)** | **17.22** | **17.65** | **32.95** | **26.31** | 3.8 |
ER improves on 10/10 meetings. cpER: better or equal to 480 ms on 9/10 meetings, ES2004d 39.9 -> 40.1.

### en cpER regression: cause and fix
Not the token offset. On ES2004d and TS3003c the engine silently fell back to *inferred* timing (character
placement by speaking rate) because the cumulative token decode was not append-only: the detokenizer drops the
space before a punctuation token ("Um, " -> "Um,'" one token later), and push_timed_tokens gave up on the whole
meeting. The punct model emits such tokens more often than the 480 ms model. Fix (engine.cpp push_timed_tokens):
each token owns its decode's common prefix with the final text, never less than the previous token's end; the
dropped space belongs to no token. All 10 meetings now use token timing; ES2004d 50.4 -> 40.1, TS3003c 30.3 -> 22.5,
the other 8 meetings are byte-identical in score.
- Token offset (`token_offset_ms` 300, tuned on 480 ms): the 1920 ms token times are 1.43 / 1.46 s earlier than the
  480 ms ones (median, ES2004d / TS3003c, `tl_shift.py`), but re-centering (NEMO_TOKEN_OFFSET_MS=-1100) made cpER
  WORSE on every meeting (zh 34.69, en 33.58). 300 is kept. Not re-derived on the sequential clip
  (tools/validate_timestamps.py) for this model: TODO before release.
- zh: no regression (cpER 32.95 vs 33.92, confT 9.3 vs 9.4).

## 3. int8 vs float
sherpa-onnx, same streaming decode, whole corpus: float 17.07 / 17.63 vs int8 17.23 / 17.70 (zh / en ER):
0.16 / 0.07 points. Not "much better": **int8 source kept** (rule). (Float-sourced LiteRT q8 export exists,
xpunctf_s1920_q8.tflite, not scored on the corpus.)

## 4. Cost (emulator ratios only, x86_64 emulator-5554, encoder graph alone, xoff_bench, 2 reps)
| | per step | per audio second | VmHWM |
|---|---|---|---|
| 480 ms, 2 threads | 57.6 ms | 0.120 | 188-193 MB |
| 1920 ms, 2 threads | 197.5 ms | 0.103 (0.86x) | 203-208 MB |
| 480 ms, 4 threads | 39.5 ms | 0.082 | 188-189 MB |
| 1920 ms, 4 threads | 112.4 ms | 0.059 (0.71x) | 202-204 MB |
1920 ms needs 14-29 % less encoder compute per second of audio and ~+15 MB peak. Each step is ~3.4x longer
(one 1.92 s chunk), so the live display moves in 1.92 s jumps: analytic word-to-screen ~1.4 s p50 / ~2.3 s p95 +
one step of compute, vs ~0.7 s for 480 ms. The app-level emulator import comparison was attempted and aborted
(the separate test package did not start the import within 10 min while another agent's import ran): import wall
time and app RSS ratios are NOT measured. Phone absolutes: pending the Note10+.

## 5. Four latencies (Settings slider, 2026-10-18)
The sherpa-onnx 2026-06-05 punct int8 release has four streaming checkpoints (`sherpa-onnx-x-asr-{160,480,960,1920}ms-
streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2`, sha256 from the GitHub release digests: 160
8a6fca05...c399, 480 fa5f63d6...bf74, 960 0a92b798...8603, 1920 fdae3104...e705e4). Their weights are IDENTICAL (all 214
large encoder tensors, decoder / joiner / tokens): one model, four chunk sizes. So one torch reconstruction and one host
bin serve all four; only the exported graph differs (chunk = latency / 20 ms frames, window 2*chunk+13, shift 2*chunk).

| file (Luigi/primeminutes-nemo-litert @7ac87f4b) | bytes | sha256 | tflite vs torch max abs diff |
|---|---|---|---|
| xasr_punct_160ms_q8.tflite | 152 257 712 | 2fe0e2c5... | 3.7e-6 |
| xasr_punct_480ms_q8.tflite | 152 697 936 | 311b18d7... | 5.1e-6 |
| xasr_punct_960ms_q8.tflite | 153 370 208 | 7ca126a3... | 3.8e-6 |
| xasr_punct_1920ms_q8.tflite | 154 783 344 | cb739c21... | 3.5e-6 |
| xasr_punct_host.bin (shared) | 16 497 008 | 2fdfc889... | |

Accuracy, app harness (host, same corpus and scorer as section 2, timing fix included; 160/480 run on training-machine,
960/1920 on the laptop, same code):
| latency | zh ER | en ER | zh cpER | en cpER | en confT |
|---|---|---|---|---|---|
| 160 ms | 19.61 | 20.03 | 34.88 | 28.06 | 3.9 |
| 480 ms | 18.24 | 18.65 | 33.78 | 26.87 | 3.7 |
| 960 ms | 17.90 | 18.26 | 33.50 | 26.73 | 3.7 |
| **1920 ms (default)** | **17.22** | **17.65** | **32.95** | **26.31** | 3.8 |
| shipped 480 ms non-punct (reference) | 18.47 | 18.78 | 33.92 | 26.87 | 3.6 |
Accuracy increases with latency on every aggregate and on ER for 10/10 meetings: the four options are kept. 160 ms is
worse than the shipped model (labelled "fastest, least accurate"). Sherpa parity (160 ms, sherpa-onnx int8 streaming
decode vs the app's LiteRT q8, 3 zh meetings): 16.68 / 13.26 / 27.75 vs 16.77 / 13.37 / 28.58 ER.

Encoder cost, emulator ratios only (training-machine KVM emulator, xoff_bench, encoder graph alone, 2 reps identical):
| | ms per step, 2 / 4 threads | compute per audio second, 2 / 4 threads (vs 480) | VmHWM |
|---|---|---|---|
| 160 ms | 10.1 / 8.4 | 0.063 / 0.053 (1.47x / 1.77x) | 184-190 MB |
| 480 ms | 20.6 / 14.6 | 0.043 / 0.030 (1x) | 188-194 MB |
| 960 ms | 37.3 / 24.3 | 0.039 / 0.025 (0.91x / 0.83x) | 194-199 MB |
| 1920 ms | 64.4 / 44.0 | 0.034 / 0.023 (0.79x / 0.77x) | 203-208 MB |
Longer chunks are cheaper per second of audio and ~+15 MB peak; the display moves in chunk-sized jumps.

## 6. App changes (Android: branch xasr-1920; iOS: branch xasr-1920-ios)
- Shared C++ (app/src/main/cpp/nemo, built by both apps): chunk geometry from the graph; push_timed_tokens fix;
  `token_offset_ms` is a Config field set by the app (JNI nativeCreate / nemo_create_ex), NEMO_TOKEN_OFFSET_MS research
  override, NEMO_DEBUG_ATTR diagnostics.
- Registry (`core/models/AsrRegistry.kt`, `ios/App/AsrRegistry.swift`): per variant latency, graph pin and token
  offset (300 ms for all four: not re-derived per variant, see section 2); default 1920. Engine stays model-agnostic.
- Files: diarizer set in models/nemo (unchanged pins, kept), shared host bin in nemo/xasr-punct-host, each graph in
  nemo/xasr-punct-<ms>ms. Only the chosen variant is downloaded.
- Switch / upgrade: the chosen variant is the download target; until it is installed the closest installed variant on
  the slider transcribes (ties: the longer one), else the pre-variant 480 ms non-punct pair of an existing install. So
  a switch never leaves the app without ASR, and the upgrade does not block a recording (DownloadPlan speechUpgrade /
  speechStandIn). The download runs as the background prefetch (consent and Wi-Fi rules of the reader upgrade). Once
  the chosen variant has loaded, the other variants and the pre-variant pair (+ .wcache) are deleted
  (reclaimOtherAsr). Existing 480 ms non-punct users therefore move to 1920 punct.
- Settings: "Transcription latency" slider under the speaker delay, 4 stops (0.16 / 0.48 / 0.96 / 1.92 s, fastest ->
  most accurate), en / zh-TW / zh-CN (Android), en / zh-Hant / zh-Hans (iOS).
- Tests: AsrRegistryTest + DownloadPlanTest stand-in cases (Android unit suite 420/420); iOS AsrRegistryTests in
  ios/tests/run.sh; androidTest NemoStreamEngineTest#latencySwitchDownloadsThenReclaims.

## 7. Verified
- Emulator (training-machine, own package studio.primeminutes.xasrtest): 1920 installed -> switch to 160 / 480 / 960:
  stand-in = 1920, real download of the pinned graph from HF (17-23 s), engine runs (2 speakers), 1920 reclaimed.
- iPhone 14 Pro Max (DEV build, VOX_ASRCHECK=fetch, no session saved): existing pre-variant install -> 1920 fetched in
  18 s, engine on the 123 s sample in 9.7 s (22 lines), legacy xasr files deleted.
- NOT verified: phone (Note10+) latency / RSS absolutes; live word-to-screen per variant; token offset re-derived per
  variant; the UI consent path for a switch on a phone (with "Later" the switch download waits, by design).

## 8. Device test plan (Note10+, when back) — `flock /tmp/n10.lock`, test app studio.primeminutes.androidtest only
0. Cleanup left from 2026-10-18 first: `run-as studio.primeminutes.androidtest rm -f files/dev_hold_reader`,
   `rm -rf /data/local/tmp/luigi_xasr`, check the library for a half-made ES2004b import (delete it only by exact id).
1. Encoder micro-bench (`tools/xasr-litert/remote/n10_bench.sh`, big cores taskset f0, 90 s rests): the four graphs,
   2 and 4 threads -> ms/step, RTF, VmHWM.
2. Import A/B (`n10_xasr.sh`, reader held by dev_hold_reader, FILES="weiya L_R003S02C02"): import wall, engine rtf,
   VmHWM, transcript json (score 尾牙 with weiya_score.py).
3. Live: record 5 min (mic-file feed) with each variant; word-to-screen latency, the UI keeps up (queue <= 1).
4. Upgrade: install over a 480 ms install: the 1920 graph is fetched in the background, the old pair is deleted after
   the first load, recordings during the download use the old model.
5. End: `run-as ... rm -f files/dev_hold_reader`, `rm -rf /data/local/tmp/luigi_xasr /data/local/tmp/luigi_xbench`,
   `svc power stayon false`.
