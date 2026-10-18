# X-ASR streaming 480 ms -> 1920 ms punct: migration plan (prototype, 2026-10-18)

Status: prototype on branch `xasr-1920` (from origin/android, NOT merged). Research and export tools: branch
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

## 5. App changes (branch xasr-1920)
- C++ (shared with iOS: app/src/main/cpp/nemo is the same engine code the iOS app builds): chunk geometry from the
  graph; push_timed_tokens fix; NEMO_TOKEN_OFFSET_MS research override; debug diagnostics under NEMO_DEBUG_ATTR.
- ModelManager: the two X-ASR pins move to the 1920 ms files (rev `unpublished-xasr1920`: TODO upload to
  Luigi/primeminutes-nemo-litert and pin the commit); NEMO_REVISION changes, so an existing install fetches the new set;
  unchanged files (diarizer graph + host bin) are kept after a SHA-256 check (`carriedOver`) instead of
  re-downloaded (170 MB instead of 270 MB); the 480 ms files and their .wcache are reclaimed after the set verifies.
  Upgrade window: ASR is unavailable until the 171 MB download finishes (same as any nemo re-pin today).
- Unit tests: FileSetTest (re-pin carry-over, wrong bytes not kept, same revision untouched).
- iOS: same C++ (sync engine.cpp, xasr_host.cc, xasr_litert.*), the iOS model registry pins the same two files;
  same carry-over rule. Not done on this branch.

## 6. Device test plan (Note10+, when back) — `flock /tmp/n10.lock`, test app studio.primeminutes.androidtest only
0. Cleanup left from 2026-10-18 first: `run-as studio.primeminutes.androidtest rm -f files/dev_hold_reader`,
   `rm -rf /data/local/tmp/luigi_xasr`, check the library for a half-made ES2004b import (delete it only by exact id).
1. Encoder micro-bench (`tools/xasr-litert/remote/n10_bench.sh`, big cores taskset f0, 90 s rests): s480 vs s1920,
   2 and 4 threads -> ms/step, RTF, VmHWM.
2. Import A/B (`n10_xasr.sh`, reader held by dev_hold_reader, VARIANTS="base s1920", FILES="weiya L_R003S02C02"):
   import wall, engine rtf, VmHWM, transcript json (score 尾牙 with weiya_score.py).
3. Live: record 5 min (VOX mic-file feed or speaker playback of the 尾牙 audio) with each variant; log word-to-screen
   latency (snapshot timestamps) and that the UI keeps up (queue <= 1).
4. Upgrade path: install the xasr-1920 debug build over the 480 ms install with the files pre-staged on a local
   HTTP mirror or pushed: verify the diarizer files are kept (no re-download), the old 480 files and .wcache are
   deleted, the .wcache for the new graph is written.
5. End: `run-as ... rm -f files/dev_hold_reader`, `rm -rf /data/local/tmp/luigi_xasr /data/local/tmp/luigi_xbench`,
   `svc power stayon false`.

## 7. Before release
Upload + pin the two files; re-derive token_offset_ms on the sequential clip; Note10+ numbers (above); iOS port;
README/guide refresh if any user-visible text changes (none so far).
