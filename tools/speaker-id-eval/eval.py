#!/usr/bin/env python3
"""Cross-meeting speaker identification on AMI meeting series (same 4 people in meetings a-d).

The question behind docs/design/speaker-tags.md: once "Mark" is named in one meeting, can his voice
in a later meeting rank "Mark" first among the known names, and how sure can we be?

Per meeting (10-min excerpt, minutes 2:00-12:00, as tools/nemo-eval uses):
  segments  oracle = reference single-speaker runs; system = runs where exactly one diarizer speaker
            is active (the app's turns), each diarizer speaker mapped to the reference speaker that
            covers most of it (purity reported).
  profile   per (meeting, speaker): mean of L2-normalised segment embeddings over the first
            --budget seconds of clean speech (segments >= --min-seg s, edges trimmed 0.1 s).
Metrics:
  EER       over all cross-meeting profile pairs (same person vs different), and within-series
            only (same room, same microphones: the hard impostors).
  ident     enrol each person from meeting a, probe b/c/d against all enrolled people (15-16):
            top-1 / top-3; then "progressive" (a -> b, a+b -> c, a+b+c -> d: the centroid grows
            with every confirmed meeting, as the design proposes).
  at a threshold t (the EER threshold of the pairs): share of probes whose top suggestion is right
            and >= t, wrong and >= t, or below t (no suggestion shown).
Models: sherpa-onnx speaker-embedding ONNX files (--onnx), or Sortformer frame features from
sortformer_feats.py (--sf DIR:LAYER), mean-pooled over the segment, centred on the global mean.

Usage: see README.md next to this file.
"""
import argparse, glob, json, os, sys, time
from collections import defaultdict
import numpy as np
import soundfile as sf

SR = 16000
WIN0, WIN1 = 120.0, 720.0
FR = 0.01
N = int((WIN1 - WIN0) / FR)


def ref_segments(rttm):
    out = []
    for line in open(rttm):
        p = line.split()
        if len(p) < 8 or p[0] != "SPEAKER":
            continue
        t0, d, s = float(p[3]), float(p[4]), p[7]
        a, b = max(t0, WIN0), min(t0 + d, WIN1)
        if b > a:
            out.append((a - WIN0, b - WIN0, s))
    return out


def frame_labels(segs):
    lab = [set() for _ in range(N)]
    for a, b, s in segs:
        for i in range(int(a / FR), min(N, int(b / FR))):
            lab[i].add(s)
    return lab


def runs(lab, min_seg, trim=0.1):
    """Maximal runs of frames with exactly one active label -> (label, a_sec, b_sec)."""
    out, i = [], 0
    while i < N:
        if len(lab[i]) == 1:
            (s,) = lab[i]
            j = i
            while j < N and lab[j] == {s}:
                j += 1
            a, b = i * FR + trim, j * FR - trim
            if b - a >= min_seg:
                out.append((s, a, b))
            i = j
        else:
            i += 1
    return out


def hyp_segments(turns_json):
    t = json.load(open(turns_json))
    return [(x["start_sample"] / SR, x["end_sample"] / SR, x["speaker_id"]) for x in t]


def split_long(rs, maxlen):
    out = []
    for s, a, b in rs:
        while b - a > maxlen * 1.5:
            out.append((s, a, a + maxlen)); a += maxlen
        out.append((s, a, b))
    return out


class Onnx:
    def __init__(self, path):
        import sherpa_onnx
        self.name = os.path.basename(path).replace(".onnx", "")
        self.x = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=path, num_threads=1))
        self.secs = 0.0; self.audio = 0.0

    def embed(self, audio, a, b, meeting):
        seg = audio[int(a * SR):int(b * SR)]
        st = self.x.create_stream(); st.accept_waveform(SR, seg); st.input_finished()
        t = time.perf_counter(); e = np.array(self.x.compute(st), dtype=np.float32)
        self.secs += time.perf_counter() - t; self.audio += b - a
        return e


class SortformerFeat:
    def __init__(self, spec):
        self.dir, self.layer = spec.split(":")
        self.name = f"sortformer-{self.layer}"
        self.cache = {}; self.secs = 0.0; self.audio = 0.0

    def embed(self, audio, a, b, meeting):
        if meeting not in self.cache:
            f = glob.glob(os.path.join(self.dir, meeting + "*.npz"))[0]
            self.cache[meeting] = np.load(f)[self.layer]
        F = self.cache[meeting]
        i, j = int(a / 0.08), max(int(a / 0.08) + 1, int(b / 0.08))
        return F[i:j].mean(0)


def l2(v):
    return v / (np.linalg.norm(v) + 1e-9)


def eer(same, diff):
    s = np.concatenate([same, diff]); y = np.concatenate([np.ones(len(same)), np.zeros(len(diff))])
    o = np.argsort(-s); y = y[o]; s = s[o]
    tp = np.cumsum(y); fp = np.cumsum(1 - y)
    fnr = 1 - tp / len(same); fpr = fp / len(diff)
    k = np.argmin(np.abs(fnr - fpr))
    return float((fnr[k] + fpr[k]) / 2), float(s[k])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audio", required=True, help="pattern with {m}, e.g. der12/{m}_ex.wav")
    ap.add_argument("--rttm", required=True, help="pattern with {m}")
    ap.add_argument("--turns", help="pattern with {m}: diarizer turns JSON (system segmentation)")
    ap.add_argument("--series", default="ES2004,IS1009,TS3003,EN2002")
    ap.add_argument("--onnx", action="append", default=[])
    ap.add_argument("--sf", action="append", default=[], help="DIR:LAYER from sortformer_feats.py")
    ap.add_argument("--min-seg", type=float, default=1.0)
    ap.add_argument("--max-seg", type=float, default=8.0)
    ap.add_argument("--budgets", default="10,20,60,0", help="seconds of clean speech per profile (0 = all)")
    ap.add_argument("--min-speech", type=float, default=10.0, help="profiles with less clean speech are dropped")
    ap.add_argument("--json", help="write the numbers here")
    a = ap.parse_args()

    meetings = [s + x for s in a.series.split(",") for x in "abcd"]
    data = {}
    purity_all = []
    for m in meetings:
        audio, sr = sf.read(a.audio.format(m=m), dtype="float32")
        assert sr == SR
        ref = ref_segments(a.rttm.format(m=m))
        rlab = frame_labels(ref)
        if a.turns:
            hyp = hyp_segments(a.turns.format(m=m))
            hlab = frame_labels(hyp)
            rs = runs(hlab, a.min_seg)
            # map each hyp speaker to the ref speaker that covers most of its single-ref frames
            cnt = defaultdict(lambda: defaultdict(float))
            for i in range(N):
                if len(hlab[i]) == 1 and len(rlab[i]) == 1:
                    (h,) = hlab[i]; (r,) = rlab[i]; cnt[h][r] += FR
            mp = {}
            for h, c in cnt.items():
                r, v = max(c.items(), key=lambda kv: kv[1])
                mp[h] = r; purity_all.append((m, h, r, v / sum(c.values()), sum(c.values())))
            rs = [(m + "/" + h, mp.get(h, "?"), x, y) for h, x, y in rs]
        else:
            rs = [(m + "/" + s, s, x, y) for s, x, y in runs(rlab, a.min_seg)]
        tagged = split_long([(k + "|" + r, x, y) for k, r, x, y in rs], a.max_seg)
        rs = [(kr.split("|")[0], kr.split("|")[1], x, y) for kr, x, y in tagged]
        data[m] = (audio, rs)

    models = [Onnx(p) for p in a.onnx] + [SortformerFeat(s) for s in a.sf]
    budgets = [float(b) for b in a.budgets.split(",")]
    results = {"segmentation": "system" if a.turns else "oracle", "models": {}}
    if purity_all:
        tot = sum(p[4] for p in purity_all)
        results["purity"] = sum(p[3] * p[4] for p in purity_all) / tot
        print(f"diarizer clusters: {len(purity_all)}, time-weighted purity {results['purity']:.3f}")
    for M in models:
        emb = {}   # (profile key) -> list of (dur, vec)
        truth = {}
        for m in meetings:
            audio, rs = data[m]
            for k, r, x, y in rs:
                v = M.embed(audio, x, y, m)
                emb.setdefault(k, []).append((y - x, v))
                truth[k] = r
        if isinstance(M, SortformerFeat):   # centre on the global mean (raw LN outputs share a big common part)
            mu = np.mean([v for L in emb.values() for _, v in L], axis=0)
            emb = {k: [(d, v - mu) for d, v in L] for k, L in emb.items()}
        out = {}
        for B in budgets:
            prof = {}
            for k, L in emb.items():
                tot, acc = 0.0, None
                for d, v in L:                         # chronological, until the budget is spent
                    if B and tot >= B: break
                    acc = (acc if acc is not None else 0) + d * l2(v); tot += d
                if tot >= min(a.min_speech, B or a.min_speech):
                    prof[k] = (l2(acc), tot)
            keys = sorted(prof)
            meet = {k: k.split("/")[0] for k in keys}
            same, diff, same_w, diff_w = [], [], [], []
            for i, p in enumerate(keys):
                for q in keys[i + 1:]:
                    if meet[p] == meet[q]: continue
                    c = float(prof[p][0] @ prof[q][0])
                    sp = truth[p] == truth[q]
                    (same if sp else diff).append(c)
                    if meet[p][:-1] == meet[q][:-1]:
                        (same_w if sp else diff_w).append(c)
            e_all, thr = eer(np.array(same), np.array(diff))
            e_w, _ = eer(np.array(same_w), np.array(diff_w))
            # identification: enrol from 'a' (one profile per person: the largest cluster mapped to them)
            def enrol(ms):
                d = {}
                for k in keys:
                    if meet[k] in ms:
                        r = truth[k]
                        d.setdefault(r, []).append(k)
                return d
            series = sorted({m[:-1] for m in meetings})
            top1 = top3 = n = 0
            prog1 = prog3 = pn = 0
            above_ok = above_bad = below = 0
            margins = []
            for s in series:
                for mode in ("a", "prog"):
                    for t_i, tm in enumerate("bcd"):
                        ms_enrol = {s + "a"} if mode == "a" else {s + x for x in "abcd"[: "abcd".index(tm)]}
                        # directory: every person of every series enrolled from its 'a' meeting (+ this series' progressive)
                        dirp = {}
                        for s2 in series:
                            src = ms_enrol if s2 == s else {s2 + "a"}
                            for r, ks in enrol(src).items():
                                vs = [prof[k][0] * prof[k][1] for k in ks]
                                dirp[(s2, r)] = l2(np.sum(vs, axis=0))
                        names = list(dirp); D = np.stack([dirp[x] for x in names])
                        for k in keys:
                            if meet[k] != s + tm: continue
                            sc = D @ prof[k][0]
                            o = np.argsort(-sc)
                            want = (s, truth[k])
                            if want not in dirp: continue
                            r1 = names[o[0]] == want; r3 = want in [names[i] for i in o[:3]]
                            if mode == "a":
                                n += 1; top1 += r1; top3 += r3
                                if sc[o[0]] >= thr:
                                    if r1: above_ok += 1
                                    else: above_bad += 1
                                else: below += 1
                                margins.append(float(sc[o[0]] - sc[o[1]]))
                            else:
                                pn += 1; prog1 += r1; prog3 += r3
            out[str(int(B)) if B else "all"] = dict(
                eer=e_all, eer_thr=thr, eer_within_series=e_w,
                same_mean=float(np.mean(same)), same_min=float(np.min(same)), same_p5=float(np.percentile(same, 5)),
                diff_max=float(np.max(diff)), diff_p99=float(np.percentile(diff, 99)), diff_within_max=float(np.max(diff_w)), diff_mean=float(np.mean(diff)), diff_within_mean=float(np.mean(diff_w)),
                pairs=[len(same), len(diff)], probes=n,
                top1=top1 / max(n, 1), top3=top3 / max(n, 1), prog_top1=prog1 / max(pn, 1), prog_top3=prog3 / max(pn, 1),
                at_thr=dict(right=above_ok / max(n, 1), wrong=above_bad / max(n, 1), none=below / max(n, 1)),
                profiles=len(keys))
            r = out[str(int(B)) if B else "all"]
            print(f"{M.name:55s} budget={B or 'all':>4} profiles={len(keys):3d} EER={e_all*100:5.1f}% (thr {thr:.2f}) "
                  f"within-series EER={e_w*100:5.1f}%  same={r['same_mean']:.2f} diff={r['diff_mean']:.2f}/{r['diff_within_mean']:.2f} "
                  f"same_min={r['same_min']:.2f} p5={r['same_p5']:.2f} diff_max={r['diff_max']:.2f} p99={r['diff_p99']:.2f} "
                  f"top1={r['top1']*100:5.1f}% top3={r['top3']*100:5.1f}% prog top1={r['prog_top1']*100:5.1f}% "
                  f"@thr ok/wrong/none={r['at_thr']['right']*100:.0f}/{r['at_thr']['wrong']*100:.0f}/{r['at_thr']['none']*100:.0f}", flush=True)
        if M.audio:
            out["rtf_1thread"] = M.secs / M.audio
            print(f"{M.name}: {M.secs:.1f} s compute for {M.audio:.0f} s of speech, RTF {M.secs / M.audio:.4f} (1 thread, host)")
        results["models"][M.name] = out
    if a.json:
        json.dump(results, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
