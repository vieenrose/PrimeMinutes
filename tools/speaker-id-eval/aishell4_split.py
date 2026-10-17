#!/usr/bin/env python3
"""Mandarin far-field calibration on AISHELL-4 (channel 0 of the table array), oracle segments.

AISHELL-4 has no cross-session speaker identity, so this is a within-session proxy:
  same  = a speaker's profile from the first half of the session vs from the second half
          (>= 15 min apart; optimistic: same room, same day);
  diff  = profiles of two different speakers of the same session (same room, same microphone,
          same language: the hardest impostors a user's library will have).
Prints the two score distributions, the EER, and the share of diff pairs above score thresholds.
Usage: aishell4_split.py <dir with *.flac + *.rttm> model.onnx [--budget 20]
"""
import argparse, glob, os, sys
import numpy as np
import soundfile as sf
import sherpa_onnx

ap = argparse.ArgumentParser()
ap.add_argument("dir"); ap.add_argument("onnx"); ap.add_argument("--budget", type=float, default=20.0)
ap.add_argument("--min-seg", type=float, default=1.0)
a = ap.parse_args()
ex = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=a.onnx, num_threads=1))


def emb(x, s, e):
    st = ex.create_stream(); st.accept_waveform(16000, x[int(s * 16000):int(e * 16000)]); st.input_finished()
    v = np.array(ex.compute(st), dtype=np.float32); return v / np.linalg.norm(v)


same, diff = [], []
for f in sorted(glob.glob(os.path.join(a.dir, "*.flac"))):
    x, sr = sf.read(f, dtype="float32"); x = x[:, 0]
    segs = [(float(p[3]), float(p[3]) + float(p[4]), p[7]) for p in (l.split() for l in open(f[:-5] + ".rttm")) if p and p[0] == "SPEAKER"]
    dur = x.shape[0] / sr
    N = int(dur * 100); cnt = np.zeros(N, np.int16)
    for s, e, _ in segs: cnt[int(s * 100):int(e * 100)] += 1
    prof = {}
    for half in (0, 1):
        lo, hi = (0, dur / 2) if half == 0 else (dur / 2, dur)
        acc = {}
        for s, e, spk in sorted(segs):
            if not (lo <= s < hi): continue
            # clean part: no other speaker active, edges trimmed
            fr = cnt[int(s * 100):int(e * 100)]
            if len(fr) == 0 or fr.max() > 1: continue
            s2, e2 = s + 0.1, e - 0.1
            if e2 - s2 < a.min_seg: continue
            t = acc.setdefault(spk, [0.0, None])
            if t[0] >= a.budget: continue
            v = emb(x, s2, min(e2, s2 + 8))
            t[1] = v * (min(e2, s2 + 8) - s2) + (t[1] if t[1] is not None else 0); t[0] += min(e2, s2 + 8) - s2
        for spk, (secs, v) in acc.items():
            if secs >= min(10, a.budget): prof[(half, spk)] = v / np.linalg.norm(v)
    spks = sorted({k[1] for k in prof})
    for s1 in spks:
        if (0, s1) in prof and (1, s1) in prof: same.append(float(prof[(0, s1)] @ prof[(1, s1)]))
        for s2 in spks:
            if s2 <= s1: continue
            for h in (0, 1):
                if (h, s1) in prof and (h, s2) in prof: diff.append(float(prof[(h, s1)] @ prof[(h, s2)]))
    print(os.path.basename(f), len(spks), "speakers", file=sys.stderr, flush=True)

same, diff = np.array(same), np.array(diff)
s = np.concatenate([same, diff]); y = np.concatenate([np.ones(len(same)), np.zeros(len(diff))])
o = np.argsort(-s); y = y[o]; tp = np.cumsum(y); fp = np.cumsum(1 - y)
fnr = 1 - tp / len(same); fpr = fp / len(diff); k = np.argmin(np.abs(fnr - fpr))
print(f"{os.path.basename(a.onnx)} budget={a.budget}: same n={len(same)} mean={same.mean():.2f} min={same.min():.2f} p5={np.percentile(same,5):.2f} | "
      f"diff n={len(diff)} mean={diff.mean():.2f} p95={np.percentile(diff,95):.2f} max={diff.max():.2f} | EER={(fnr[k]+fpr[k])/2*100:.1f}% at {s[o][k]:.2f} | "
      + " ".join(f"diff>={t}: {(diff >= t).mean()*100:.0f}% same>={t}: {(same >= t).mean()*100:.0f}%" for t in (0.4, 0.5, 0.6)))
