#!/usr/bin/env python3
"""The app's voice-vector protocol on AMI series (and the AISHELL-4 proxy), with the exported LiteRT graph.

Protocol (what the app's voice lane does, docs/design/speaker-tags.md §4.3):
  clean speech  runs where exactly one diarizer speaker is active, edges trimmed 0.1 s, >= 1.5 s;
  selection     the meeting split in thirds, each third's runs longest first, taken round-robin over the
                thirds until 60 s (the last run cut to fit); < 10 s of clean speech -> no vector;
  windows       the selected runs concatenated, cut into WIN-sample windows (400 fbank frames); a tail
                >= 2 s is embedded as the LAST full window of the stream (overlapping the previous one)
                and weighted by its new seconds; a shorter tail is dropped;
  vector        per window: app C++ fbank (fbank_check) -> per-bin mean over the window's frames removed
                -> tflite -> L2; speaker vector = seconds-weighted mean, L2.
Metrics as eval.py: EER all / within-series, top-1 from meeting a, progressive top-1, score distributions.
Also (--sherpa ONNX) the cosine between our window vector and sherpa-onnx's on the same 4 s of audio.

Usage (venv with ai_edge_litert, soundfile, numpy; sherpa_onnx for --sherpa):
  python -I eval_windows.py --tflite campplus_zh_en_400f_fp16.tflite --fbank ./fbank_check \
     --audio 'far/{m}.wav' --rttm 'der12/{m}.rttm' --turns 'turns/far/{m}.json' [--fb official|sherpa]
  python -I eval_windows.py --tflite ... --fbank ... --aishell4 eval-corpora/aishell4
"""
import argparse, glob, json, os, subprocess, sys, tempfile, time
from collections import defaultdict
import numpy as np
import soundfile as sf

SR = 16000
WIN0, WIN1 = 120.0, 720.0
FR = 0.01
N = int((WIN1 - WIN0) / FR)
FRAMES = 400
CAP, FLOOR, MIN_RUN, TAIL_MIN = 60.0, 10.0, 1.5, 2.0


class Emb:
    def __init__(self, tflite, fbank, fb, threads=1):
        from ai_edge_litert.interpreter import Interpreter
        self.it = Interpreter(model_path=tflite, num_threads=threads); self.it.allocate_tensors()
        self.i = self.it.get_input_details()[0]['index']; self.o = self.it.get_output_details()[0]['index']
        self.fbank = fbank
        # official = 3D-Speaker's torchaudio.compliance.kaldi.fbank defaults; sherpa = sherpa-onnx's front-end
        self.snip, self.high = (1, 0) if fb == "official" else (0, -400)
        self.win = (FRAMES - 1) * 160 + 400 if self.snip else FRAMES * 160
        self.tmp = tempfile.mkdtemp(); self.secs = 0.0; self.n = 0

    def feats(self, pcm):
        a, b = os.path.join(self.tmp, "i.f32"), os.path.join(self.tmp, "o.f32")
        pcm.astype(np.float32).tofile(a)
        subprocess.run([self.fbank, a, b, str(self.snip), str(self.high)], check=True)
        T, M = np.fromfile(b, dtype=np.int32, count=2)
        f = np.fromfile(b, dtype=np.float32)[2:].reshape(T, M)
        assert T == FRAMES, T
        return f - f.mean(0, keepdims=True)

    def window(self, pcm):
        f = self.feats(pcm)
        t = time.perf_counter()
        self.it.set_tensor(self.i, f[None]); self.it.invoke(); e = self.it.get_tensor(self.o)[0].copy()
        self.secs += time.perf_counter() - t; self.n += 1
        return e / np.linalg.norm(e)

    def vector(self, stream):
        """Seconds-weighted mean over the windows of [stream] (concatenated clean speech)."""
        W = self.win; acc = None; tot = 0.0
        k = 0
        while (k + 1) * W <= len(stream):
            acc = (acc if acc is not None else 0) + self.window(stream[k * W:(k + 1) * W]) * (W / SR); tot += W / SR; k += 1
        tail = len(stream) - k * W
        if tail >= TAIL_MIN * SR and len(stream) >= W:
            acc = (acc if acc is not None else 0) + self.window(stream[-W:]) * (tail / SR); tot += tail / SR
        if acc is None: return None
        return acc / np.linalg.norm(acc)


def select(runs_, dur):
    """Round-robin over the meeting's thirds, longest first, until CAP seconds."""
    thirds = [sorted([r for r in runs_ if int(3 * r[0] / dur) == t or (t == 2 and r[0] >= dur)], key=lambda r: r[0] - r[1]) for t in range(3)]
    out, tot = [], 0.0
    while tot < CAP and any(thirds):
        for th in thirds:
            if not th or tot >= CAP: continue
            a, b = th.pop(0)
            b = min(b, a + CAP - tot); out.append((a, b)); tot += b - a
    return sorted(out), tot


def ref_segments(rttm):
    out = []
    for line in open(rttm):
        p = line.split()
        if len(p) < 8 or p[0] != "SPEAKER": continue
        t0, d, s = float(p[3]), float(p[4]), p[7]
        a, b = max(t0, WIN0), min(t0 + d, WIN1)
        if b > a: out.append((a - WIN0, b - WIN0, s))
    return out


def frame_labels(segs):
    lab = [set() for _ in range(N)]
    for a, b, s in segs:
        for i in range(int(a / FR), min(N, int(b / FR))): lab[i].add(s)
    return lab


def runs(lab, min_seg, trim=0.1):
    out, i = [], 0
    while i < N:
        if len(lab[i]) == 1:
            (s,) = lab[i]; j = i
            while j < N and lab[j] == {s}: j += 1
            a, b = i * FR + trim, j * FR - trim
            if b - a >= min_seg: out.append((s, a, b))
            i = j
        else: i += 1
    return out


def eer(same, diff):
    s = np.concatenate([same, diff]); y = np.concatenate([np.ones(len(same)), np.zeros(len(diff))])
    o = np.argsort(-s); y = y[o]; s = s[o]
    tp = np.cumsum(y); fp = np.cumsum(1 - y)
    fnr = 1 - tp / len(same); fpr = fp / len(diff)
    k = np.argmin(np.abs(fnr - fpr))
    return float((fnr[k] + fpr[k]) / 2), float(s[k])


def dist(x):
    x = np.array(x)
    return dict(n=len(x), mean=float(x.mean()), min=float(x.min()), p5=float(np.percentile(x, 5)), p95=float(np.percentile(x, 95)),
                p99=float(np.percentile(x, 99)), max=float(x.max()))


def ami(a, E):
    meetings = [s + x for s in a.series.split(",") for x in "abcd"]
    prof, truth = {}, {}
    sherpa_cos = []
    sx = None
    if a.sherpa:
        import sherpa_onnx
        sx = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=a.sherpa, num_threads=1))
    for m in meetings:
        audio, sr = sf.read(a.audio.format(m=m), dtype="float32"); assert sr == SR
        rlab = frame_labels(ref_segments(a.rttm.format(m=m)))
        if a.turns:
            hyp = [(x["start_sample"] / SR, x["end_sample"] / SR, x["speaker_id"]) for x in json.load(open(a.turns.format(m=m)))]
            hlab = frame_labels(hyp)
            rs = runs(hlab, MIN_RUN)
            cnt = defaultdict(lambda: defaultdict(float))
            for i in range(N):
                if len(hlab[i]) == 1 and len(rlab[i]) == 1:
                    (h,) = hlab[i]; (r,) = rlab[i]; cnt[h][r] += FR
            mp = {h: max(c.items(), key=lambda kv: kv[1])[0] for h, c in cnt.items()}
        else:
            rs = runs(rlab, MIN_RUN); mp = {s: s for s, _, _ in rs}
        by = defaultdict(list)
        for s, x, y in rs: by[s].append((x, y))
        for s, L in by.items():
            sel, tot = select(L, N * FR)
            if tot < FLOOR or s not in mp: continue
            stream = np.concatenate([audio[int(x * SR):int(y * SR)] for x, y in sel])
            v = E.vector(stream)
            if v is None: continue
            k = m + "/" + s; prof[k] = v; truth[k] = mp[s]
            if sx is not None and len(sherpa_cos) < 60 and len(stream) >= E.win:
                seg = stream[:E.win]
                st = sx.create_stream(); st.accept_waveform(SR, seg); st.input_finished()
                r = np.array(sx.compute(st), dtype=np.float32); r /= np.linalg.norm(r)
                sherpa_cos.append(float(E.window(seg) @ r))
        print(m, "profiles", sum(1 for k in prof if k.startswith(m + "/")), file=sys.stderr, flush=True)
    keys = sorted(prof); meet = {k: k.split("/")[0] for k in keys}
    same, diff, same_w, diff_w = [], [], [], []
    for i, p in enumerate(keys):
        for q in keys[i + 1:]:
            if meet[p] == meet[q]: continue
            c = float(prof[p] @ prof[q]); sp = truth[p] == truth[q]
            (same if sp else diff).append(c)
            if meet[p][:-1] == meet[q][:-1]: (same_w if sp else diff_w).append(c)
    e_all, thr = eer(np.array(same), np.array(diff)); e_w, _ = eer(np.array(same_w), np.array(diff_w))
    series = sorted({m[:-1] for m in meetings})
    res = {"a": [0, 0], "prog": [0, 0]}; top_scores, margins = [], []
    for s in series:
        for mode in ("a", "prog"):
            for tm in "bcd":
                ms = {s + "a"} if mode == "a" else {s + x for x in "abcd"[: "abcd".index(tm)]}
                dirp = {}
                for s2 in series:
                    src = ms if s2 == s else {s2 + "a"}
                    for k in keys:
                        if meet[k] in src: dirp.setdefault((s2, truth[k]), []).append(prof[k])
                names = list(dirp); D = np.stack([np.mean(dirp[x], 0) / np.linalg.norm(np.mean(dirp[x], 0)) for x in names])
                for k in keys:
                    if meet[k] != s + tm or (s, truth[k]) not in dirp: continue
                    sc = D @ prof[k]; o = np.argsort(-sc)
                    ok = names[o[0]] == (s, truth[k])
                    res[mode][0] += ok; res[mode][1] += 1
                    if mode == "a": top_scores.append((float(sc[o[0]]), ok)); margins.append(float(sc[o[0]] - sc[o[1]]))
    out = dict(profiles=len(keys), eer=e_all, eer_thr=thr, eer_within=e_w, same=dist(same), diff=dist(diff), diff_within=dist(diff_w),
               top1_from_a=res["a"][0] / res["a"][1], probes=res["a"][1], prog_top1=res["prog"][0] / res["prog"][1],
               wrong_top=[t for t, ok in top_scores if not ok], margin_min_right=min(m for m, (t, ok) in zip(margins, top_scores) if ok))
    if sherpa_cos: out["cos_vs_sherpa_4s"] = dist(sherpa_cos)
    return out


def aishell4(a, E):
    same, diff = [], []
    for f in sorted(glob.glob(os.path.join(a.aishell4, "*.flac"))):
        x, sr = sf.read(f, dtype="float32"); x = x[:, 0]
        segs = [(float(p[3]), float(p[3]) + float(p[4]), p[7]) for p in (l.split() for l in open(f[:-5] + ".rttm")) if p and p[0] == "SPEAKER"]
        dur = x.shape[0] / sr; n = int(dur * 100); cnt = np.zeros(n, np.int16)
        for s, e, _ in segs: cnt[int(s * 100):int(e * 100)] += 1
        prof = {}
        for half in (0, 1):
            lo, hi = (0, dur / 2) if half == 0 else (dur / 2, dur)
            by = defaultdict(list)
            for s, e, spk in segs:
                if not (lo <= s < hi): continue
                fr = cnt[int(s * 100):int(e * 100)]
                if len(fr) == 0 or fr.max() > 1: continue
                if e - s - 0.2 >= MIN_RUN: by[spk].append((s + 0.1 - lo, e - 0.1 - lo))
            for spk, L in by.items():
                sel, tot = select(L, hi - lo)
                if tot < FLOOR: continue
                v = E.vector(np.concatenate([x[int((u + lo) * SR):int((w + lo) * SR)] for u, w in sel]))
                if v is not None: prof[(half, spk)] = v
        spks = sorted({k[1] for k in prof})
        for s1 in spks:
            if (0, s1) in prof and (1, s1) in prof: same.append(float(prof[(0, s1)] @ prof[(1, s1)]))
            for s2 in spks:
                if s2 <= s1: continue
                for h in (0, 1):
                    if (h, s1) in prof and (h, s2) in prof: diff.append(float(prof[(h, s1)] @ prof[(h, s2)]))
        print(os.path.basename(f), len(spks), file=sys.stderr, flush=True)
    e, t = eer(np.array(same), np.array(diff))
    return dict(same=dist(same), diff=dist(diff), eer=e, diff_ge_05=float(np.mean(np.array(diff) >= 0.5)), diff_ge_06=float(np.mean(np.array(diff) >= 0.6)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tflite", required=True); ap.add_argument("--fbank", required=True)
    ap.add_argument("--fb", default="official", choices=["official", "sherpa"])
    ap.add_argument("--audio"); ap.add_argument("--rttm"); ap.add_argument("--turns")
    ap.add_argument("--series", default="ES2004,IS1009,TS3003,EN2002")
    ap.add_argument("--sherpa", help="sherpa-onnx CAM++ ONNX: cosine of our 4 s window vector vs sherpa's")
    ap.add_argument("--aishell4"); ap.add_argument("--json")
    a = ap.parse_args()
    E = Emb(a.tflite, a.fbank, a.fb)
    r = aishell4(a, E) if a.aishell4 else ami(a, E)
    r["ms_per_window_1thread"] = 1000 * E.secs / max(E.n, 1)
    print(json.dumps(r, indent=1))
    if a.json: json.dump(r, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
