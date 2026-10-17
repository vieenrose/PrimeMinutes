# X-ASR offline in pseudo-streaming: can it replace or complement the streaming ASR?

Research only (2026-10-18). Nothing in android/ or ios/ changed. Model under test:
`sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03` (encoder int8 161 MB + decoder fp32 11.3 MB + joiner
2.6 MB, about 175 MB). Shipped baseline: X-ASR 480 ms streaming, LiteRT `xasr_stream_q8_octav.tflite` + `xasr_host.bin`,
one continuous stream, diarizer decode 0.5/20.

Corpus and metric: the same 6 AISHELL-4 (zh CER) + 4 AMI (en WER) meetings and `score_asr.py` as the ASR/diarization
report. Speaker-attributed scores (cp, confT) come from splitting offline tokens on the app's speaker timeline
(`split_spk.py --offset=0.3`, word boundaries only). 尾牙 CER is measured against `docs/demo/weiya-meeting-zhTW.txt`.

Tools (tools/asr-diar-research/): `pseudo_stream.py` (host decoder, segmentation modes), `ps_run.sh`, `ps_score.sh`,
`ps_score_dir.sh`, `split_spk.py`, `edge_dels.py`, `weiya_cer.py`, `pseudo_bench.cpp` + `build_pseudo_bench.sh`
(Note10+ bench: the app's nemo engine + sherpa C API in one process), `pb_summary.py`, `sim_latency.py`,
`remote/*.sh`.

## 1. Accuracy

Segmentations: **app** = the app's own transcript lines; **merge** = app lines joined across speakers while the gap is
< 0.3 s; **fixed** = no endpointer, cut every 10-20 s at the quietest 100 ms frame; **vad** = Silero VAD.
`cN` = max segment N s, `bp` = blank penalty.

| config | zh CER | en WER | 尾牙 CER |
|---|---|---|---|
| **streaming (shipped)** | **18.47** | **18.77** | **3.60** |
| offline, app lines, c20 | 17.00 | 20.12 | 3.28 |
| app lines + tail-zero 1.61 s | 16.92 | 19.87 | 2.96 |
| app lines + ctx 1.0 s | 16.70 | 20.19 | |
| app lines + bp 1.0 | 16.61 | 19.18 | |
| app lines + beam search | 16.61 | 19.41 | |
| app lines c10 / c30 | 17.76 / 16.50 | 20.26 / 20.01 | |
| merge c20 | 16.10 | 17.42 | |
| merge c20 bp 1.0 | 15.78 | 16.78 | 2.90 |
| merge c30 bp 1.0 | 15.30 | 16.54 | 2.58 |
| fixed c20 | 16.33 | 16.92 | |
| **fixed c20 bp 1.0** | **15.86** | **16.19** | 3.01 |
| fixed c15 bp 1.0 | 16.07 | 16.16 | 2.69 |
| fixed c10 bp 1.0 | 16.65 | 16.78 | 2.85 |
| fixed c30 bp 1.0 | 15.40 | 16.17 | |
| vad thr 0.5, sil 0.5, c20 | 21.05 | 18.08 | 3.4-3.98 |
| vad thr 0.2, pad 0.3, c20 bp 1.0 | 16.39 | 16.34 | (all VAD variants 3.4-3.98) |
| vad thr 0.2, pad 0.3, c10 bp 1.0 | 17.69 | 17.14 | |

Speaker-attributed (token split on the app's turns):

| config | zh cp / confT | en cp / confT |
|---|---|---|
| streaming (shipped) | 34.21 / 9.5 | 29.82 / 4.8 |
| fixed c20 bp 1.0 | 32.74 / 9.6 | 27.30 / 5.0 |
| merge c20 bp 1.0 | 32.73 / 9.5 | 27.36 / 4.6 |
| vad thr 0.2 c20 bp 1.0 | 33.15 / 9.6 | 27.54 / 5.0 |
| fixed c10 bp 1.0 | 33.56 / 9.6 | 27.54 / 5.0 |

**The en regression (18.8 -> 20.1) is a segmentation artefact, not the model.** On the app's speaker-split lines, 2.6 % of
reference words fall in gaps between lines (the streaming decoder hears them, the offline one never gets them), and
words are lost at the edges of short fragments. ES2004d alone (495 lines, median 2 s) contributes +2.4 WER. When
segments no longer stop at speaker changes (merge, fixed), en drops to 16.2-17.4, i.e. 2.6 points better than streaming.
The blank penalty 1.0 gives another 0.3-0.7 everywhere. Silero VAD at its default threshold drops quiet zh speech
(zh 21 %), and only a permissive threshold (0.2) + 0.3 s pad gets close to fixed windows.

Caveats: merge and the speaker split use the app's FINAL lines/turns, not the live ones. A grid machine
(training-machine) gave corrupted results under heavy load (segfaults, empty segments, repeated garbage tokens). All
retained numbers were rerun on ai-workstation or the host, and those agree within +-0.1.

## 2. Latency (Note10+, 2 threads, big cores)

Decode cost: RTF 0.072-0.075 per second of speech for 5-20 s segments, 0.10-0.11 under 2 s; a 20 s segment takes
1.3-1.5 s. Model load 6.1-6.7 s. Measured while the full engine runs, the fit decode = 0.041 + 0.0718 x len reproduces
the measured word latency (simulated 9.6 / 18.7 s vs measured 9.7 / 18.9 s for merge).

"Word latency" = time from a word being spoken to its offline text being shown (speech-time weighted).

| segmentation | after end of segment p50 / p95 | word latency p50 / p95 |
|---|---|---|
| vad thr 0.5 (measured) | 1.05 / 1.9 s | 5.9 / 16 s |
| merge c20 (measured) | 2.2 / 2.7 s | 9.7 / 19.2 s |
| merge c20 (sim) | | 9.6 / 18.7 s |
| fixed c20 (sim) | | 13.5 / 20.4 s |
| fixed c10 (sim) | | 6.8 / 10.3 s |
| vad thr 0.2 c20 (sim) | | 7.6 / 17.3 s |
| vad thr 0.2 c10 (sim) | | 4.2 / 9.0 s |

The decode itself is never the bottleneck: latency is set by the segment length. The streaming draft shows words in
about 0.5-1 s.

## 3. Memory (Note10+ VmHWM)

The bench holds the whole audio in RAM (77 MB for 1200 s); "net" subtracts it.

| process | VmHWM | net |
|---|---|---|
| diarizer only | 234 MB | 157 MB |
| streaming ASR only | 320 MB | 243 MB |
| today's engine (streaming + diarizer) | 442 MB | 365 MB |
| offline alone | 611-648 MB | 480-570 MB |
| mode A: diarizer + offline | 730 MB | 653 MB (+~290 MB vs today) |
| mode B: today's engine + offline | 934-961 MB | 857-884 MB (+~490 MB vs today) |

Offline = +260 MB at model load plus about +270 MB of onnxruntime activations at 20 s segments (host x86 activation
peak: 65 / 134 / 236 / 445 MB at 5 / 10 / 20 / 30 s max cut, so c10 would roughly halve it). App reference figures:
engine ~580 MB in the app process, 958 MB with E2B loaded; E2B VmHWM 1.47 GB, E4B 3.0 GB. Mode B with E4B loaded
during recording would reach about 4 GB.

## 4. Sustained real-time concurrency (paced 1x)

| run | audio | result |
|---|---|---|
| A_zh: diarizer + offline | 1200 s | keeps up: max queue 1, backlog <= one segment (<= 20 s), drain <= 1.6 s |
| B_zh: full engine + offline | 1200 s | keeps up, same |
| B_en | 600 s | keeps up |
| B_zh merge segments | 1200 s | keeps up |
| B_zh_llm (planned with the reader; reader not loaded, see Not verified) | 600 s | keeps up |

No backlog growth in any quarter of any run. Contention cost to the engine: ASR 148.8 -> 169.6 s, diarizer
134.1 -> 165.8 s of CPU for 1200 s, push p95 65 -> 78 ms (still real time). Battery 37.5-43.6 C.

## Verdicts

**(A) Replace streaming with offline pseudo-streaming.** Accuracy: better on every set (fixed c20 bp 1.0: zh -2.6,
en -2.6, 尾牙 -0.6, cp -1.5 / -2.5). Cost: fine (+~290 MB, keeps up). But live text appears 5-20 s late (p95
10-20 s) instead of under 1 s. **Recommended for imported files** (no live constraint: use fixed c20 bp 1.0).
For live recording only if a 7-10 s lag is acceptable (vad thr 0.2 / pad 0.3 / c10 bp 1.0: p50 4.2 s, p95 9 s, still
better than streaming at zh 17.69 / en 17.14).

**(B) Streaming draft + offline replacement.** Keeps the instant draft and gets the offline accuracy. The replacement of
a merge or fixed c10-c20 segment usually lands inside the 15 s live-settle window. Costs about +490 MB peak and some
engine CPU, but stays real time. **Recommended if the memory budget allows** (check it with the LLM resident; c10
segments save about 100 MB of activations).

**(C) Offline for zh only.** **Not needed.** The en regression disappears once segments do not stop at speaker
changes, and en then gains as much as zh.

## Not verified

- Concurrency with the LLM reader loaded (E2B absent from the test app; `longWindowSpeed` fails "model not pushed").
- Reno7 or any other phone than the Note10+.
- Merge / speaker split from live (unsettled) lines; only the app's final lines were used.
- An onnxruntime memory-arena limit, and fp16/int8 decoder variants.
- Integration: the app ships no onnxruntime (needs libonnxruntime.so 22.2 MB + libsherpa-onnx-c-api.so 4.5 MB, or a
  LiteRT export of the offline model, not attempted). Input of ~4790+ frames crashes the model (reshape), so a max cut
  is mandatory.
- VAD threshold 0.1 run did not finish.
