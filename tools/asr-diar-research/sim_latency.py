#!/usr/bin/env python3
"""Display latency of a pseudo-streaming segmentation on the phone, simulated from measured decode costs.
decode(len) = a + b * len, least squares over the per-utterance decode times of paced Note10+ runs (pseudo_bench JSON,
offline ASR decoding WHILE the app engine runs). One worker, FIFO, an utterance can start when the audio clock reaches
its availability time:
  vad    end + min_silence           (the VAD needs that much silence to close it)
  merge  end + 1.0 s                 (0.3 s text gap + the streaming draft's own latency, as in the phone runs)
  fixed  start + max_cut             (the cut point is the quietest frame of [max_cut/2, max_cut]: known only then)
Word latency = time a word is shown - time it was spoken, speech-time weighted (0.1 s steps).
    python3 -I sim_latency.py PHONE_JSON[,PHONE_JSON...] KIND PARAM SEGS_FILE [SEGS_FILE ...]
"""
import json, sys


def pct(v, p):
    v = sorted(v); return v[min(len(v) - 1, int(round(p / 100 * (len(v) - 1))))]


rows = []
for f in sys.argv[1].split(","):
    rows += [(r[1] - r[0], r[2]) for r in json.load(open(f))["utts"] if r[1] - r[0] >= 0.1]
n = len(rows); sx = sum(x for x, _ in rows); sy = sum(y for _, y in rows)
sxx = sum(x * x for x, _ in rows); sxy = sum(x * y for x, y in rows)
b = (n * sxy - sx * sy) / (n * sxx - sx * sx); a = (sy - b * sx) / n
kind, param = sys.argv[2], float(sys.argv[3])
for segf in sys.argv[4:]:
    segs = [tuple(map(float, l.split())) for l in open(segf) if l.strip()]
    free, wl, dl, back = 0.0, [], [], 0.0
    for s, e in segs:
        avail = e + param if kind == "vad" else e + 1.0 if kind == "merge" else max(e, s + param)
        start = max(avail, free); done = start + a + b * (e - s); free = done
        back = max(back, start - avail)
        dl.append(done - e)
        t = s
        while t < e:
            wl.append(done - t); t += 0.1
    L = [e - s for s, e in segs]
    print(f"{segf.split('/')[-1]:22} {kind} {param}: n={len(segs)} len p50 {pct(L,50):.1f}s | after end p50 {pct(dl,50):.1f}s p95 {pct(dl,95):.1f}s"
          f" | word latency p50 {pct(wl,50):.1f}s p95 {pct(wl,95):.1f}s | max queue wait {back:.1f}s  (decode = {a:.3f} + {b:.4f} x len)")
