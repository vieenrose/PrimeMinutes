# X-ASR offline import / 1920 ms streaming: LiteRT integration plan (WIP, 2026-10-18)

Status: work in progress, interrupted by a host reboot. Runtime decision (user): LiteRT only, no onnxruntime.
Branch `xasr-offline-import` (from origin/android). Tools: `tools/xasr-litert/`.

## 1. Runtime: LiteRT export works (no blocker)
- The sherpa-onnx X-ASR ONNX files (int8 or float) are rebuilt as an icefall torch model (`recon_from_onnx.py`:
  every MatMulInteger weight dequantised, linear_pos, chunkwise_conv_scale halves, downsample softmax weights,
  decoder/joiner), then exported with litert_torch and quantised with ai_edge_quantizer (dynamic wi8, OCTAV),
  the recipe of the shipped 480 ms graph.
- Torch vs ONNX: float source max|d| 2e-6 (exact); int8 source corr 0.999 (ORT dynamic activation quant).
- **The punct offline model (2026-06-03) and the 1920 ms punct streaming model (2026-06-05) are the same weights**
  (decoders identical; the torch model rebuilt from the offline float ONNX reproduces the 1920 ms streaming float
  encoder at max|d| 1.7e-6). One checkpoint, two graphs. Tokens = the shipped 480 ms vocabulary (punct included), so
  no app text post-processing change is needed (the app already gets punctuation from the 480 ms model).
- Sizes: offline encoder q8 (fixed T = 2000 frames = 20 s) 195 MB; 1920 ms streaming q8 155 MB (shipped 480 ms: 153 MB);
  xasr_host.bin 16.5 MB (same layout, new weights).

## 2. Accuracy (corpus 6 AISHELL-4 zh CER / 4 AMI en WER, punctuation stripped by score_asr.py)
| config | zh | en |
|---|---|---|
| shipped 480 ms streaming, app segmentation (host harness) | 18.47 | 18.78 |
| **1920 ms punct int8 → LiteRT q8, app engine (host harness)** | **17.22** | **17.64** |
| sherpa-onnx 1920 ms punct int8 (reference, one stream) | 17.23 | 17.70 |
| offline non-punct, fixed c20 bp1.0: sherpa int8 / LiteRT q8 | 15.83 / 15.86 | 16.26 / 16.08 |
| offline punct, fixed c20 bp1.0: sherpa int8 / LiteRT q8 | 15.97 / 16.03 | 16.12 / 16.71 |
| offline punct LiteRT fp32 (int8-dequant weights) | – | 16.57 |
1920 ms improves ER on 10/10 meetings; 尾牙 CER 3.60 → 3.55 (host).
Speaker-attributed (cpER): zh 33.92 → 32.95, **en 26.87 → 30.84**, all from ES2004d (39.9 → 50.4) and TS3003c
(23.8 → 30.3); confT unchanged (3.6). Investigation running (token time shift vs the 300 ms token_offset tuned on 480 ms).

## 3. Note10+ (test app, reader held by DEBUG `files/dev_hold_reader`)
- shipped 480 ms import: 尾牙 460 s → 94.9 s (RTF 0.206), peak 565 MB; L_R003S02C02 2247 s → 491.5 s (RTF 0.219), 565 MB.
  With the reader live (normal app), the same 尾牙 import took 139.9 s and the process peaked at 3.2 GB (E4B).
- 1920 ms and offline on the device: NOT measured (the phone dropped off USB during the run).

## 4. Plan (draft)
- Display latency: offline pseudo-streaming with the accuracy-keeping cut (fixed c20) has word-to-screen p50 9.7-13.5 s,
  fails the user's 5 s bar; VAD 0.2 c10 is 4.2 s p50 (sim) but p95 9 s and loses most of the gain. **Main plan: migrate
  the streaming checkpoint 480 ms → 1920 ms punct int8** (analytic word-to-screen ~1.4 s p50 / ~2.3 s p95 + compute,
  vs ~0.7 s for 480 ms; device compute not yet measured).
- Engine: chunk geometry now read from the graph (XasrLiteRT) and from chunk_ms (xasr_host.cc); engine.cpp derives
  chunk_ms from the graph. Shared C++ with iOS. Model file = registry/ModelManager pin change only.
- Offline for imports: only if it beats 1920 ms clearly (now zh −1.2 / en −0.9 vs 1920 ms on host); costs a second
  195 MB download and ~+290 MB peak. Decision pending device numbers.
- Open: token_offset_ms per model, en cpER regression, int8 vs float (float-sourced q8 exports being built).
