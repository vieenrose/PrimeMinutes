#!/usr/bin/env python3
"""尾牙 speaker A/B scoring. The demo is a TTS script (docs/demo/weiya-meeting-zhTW.txt, "Speaker N: line").
  ref   : reference RTTM built from one transcript: script chars aligned to the transcript's chars (difflib),
          char times interpolated inside each utterance, line edges snapped to silence gaps of the audio.
  score : for each asr.json: speakers (all / >=2 % of time), transcript DER (utterances as hyp segments, c25/c0),
          confT (share of aligned script chars whose utterance speaker != the mapped reference speaker; mapping
          = optimal 1-1 over aligned chars, extra speakers count as wrong), lines whose majority speaker is wrong.
    venv/bin/python -I weiya_score.py SCRIPT.txt AUDIO.f32 REF_OUT.rttm BASE.asr.json [OTHER.asr.json ...]
"""
import difflib, json, re, sys
import numpy as np
import cn2an, opencc
from scipy.optimize import linear_sum_assignment
sys.path.insert(0, "/home/luigi/VoxSumDroid/.claude/worktrees/asr-research/tools/quality-eval")
import score_asr as S

cc = opencc.OpenCC("t2s")
KEEP = re.compile(r"[0-9A-Za-z㐀-鿿]")


def norm(t):
    t = cc.convert(t)
    def num(m):
        try: return cn2an.an2cn(m.group(0))
        except Exception: return m.group(0)
    t = re.sub(r"\d+(?:\.\d+)?", num, t)
    return "".join(KEEP.findall(t))


def load_script(p):
    lines = []
    for ln in open(p, encoding="utf-8"):
        m = re.match(r"\s*Speaker\s*(\d+)\s*[:：]\s*(.*)", ln)
        if m: lines.append((int(m.group(1)), norm(m.group(2))))
    return lines


def hyp_chars(d):
    chars, times, spk = [], [], []
    for u in d["utterances"]:
        t = norm(u["text"]); n = len(t)
        for i, c in enumerate(t):
            chars.append(c); times.append(u["start"] + (u["end"] - u["start"]) * (i + 0.5) / max(1, n)); spk.append(u["speaker"])
    return "".join(chars), np.array(times), spk


def align(lines, d):
    ref = "".join(t for _, t in lines); lid = np.concatenate([[i] * len(t) for i, (_, t) in enumerate(lines)])
    hyp, ht, hs = hyp_chars(d)
    sm = difflib.SequenceMatcher(None, ref, hyp, autojunk=False)
    rt = np.full(len(ref), np.nan); rs = [None] * len(ref)
    for a, b, n in sm.get_matching_blocks():
        for k in range(n): rt[a + k] = ht[b + k]; rs[a + k] = hs[b + k]
    return ref, lid, rt, rs


def gaps(pcm_path, fr=0.01, min_gap=0.15):
    x = np.fromfile(pcm_path, np.float32); h = int(16000 * fr)
    e = np.array([np.sqrt(np.mean(x[i:i + h] ** 2) + 1e-12) for i in range(0, len(x) - h, h)])
    db = 20 * np.log10(e); thr = np.percentile(db, 95) - 35
    sil = db < thr; out = []; i = 0
    while i < len(sil):
        if sil[i]:
            j = i
            while j < len(sil) and sil[j]: j += 1
            if (j - i) * fr >= min_gap: out.append((i * fr, j * fr))
            i = j
        else: i += 1
    return out, len(sil) * fr


def build_ref(lines, d, pcm, out):
    ref, lid, rt, _ = align(lines, d)
    G, dur = gaps(pcm)
    segs = []
    for i, (sp, t) in enumerate(lines):
        tt = rt[lid == i]; tt = tt[~np.isnan(tt)]
        cov = len(tt) / max(1, len(t))
        if len(tt) == 0: segs.append([sp, None, None, cov]); continue
        segs.append([sp, float(tt.min()), float(tt.max()), cov])
    # snap: start -> end of the latest gap ending in [s-2.0, s+0.3]; end -> start of the earliest gap starting in [e-0.3, e+2.0]
    for s in segs:
        if s[1] is None: continue
        c = [g for g in G if s[1] - 2.0 <= g[1] <= s[1] + 0.3]
        if c: s[1] = max(c, key=lambda g: g[1])[1]
        c = [g for g in G if s[2] - 0.3 <= g[0] <= s[2] + 2.0]
        if c: s[2] = min(c, key=lambda g: g[0])[0]
    # no overlap between consecutive lines (TTS turns are sequential)
    for a, b in zip(segs, segs[1:]):
        if a[1] is not None and b[1] is not None and a[2] > b[1]: a[2] = b[1] = (a[2] + b[1]) / 2
    with open(out, "w") as f:
        for sp, s, e, cov in segs:
            if s is not None and e > s:
                f.write("SPEAKER weiya 1 %.2f %.2f <NA> <NA> spk%d <NA> <NA>\n" % (s, e - s, sp))
    return segs


def score(lines, d, ref_rttm):
    R = S.rttm(ref_rttm)
    hyp = [("h%s" % u["speaker"], u["start"], u["end"]) for u in d["utterances"] if u["speaker"] is not None]
    tot = {}
    for s, a, b in hyp: tot[s] = tot.get(s, 0) + b - a
    allt = sum(tot.values()) or 1
    ref, lid, rt, rs = align(lines, d)
    refspk = np.array([lines[i][0] for i in lid])
    rsp = sorted(set(refspk)); hsp = sorted({s for s in rs if s is not None}, key=str)
    co = np.zeros((len(rsp), len(hsp)))
    for r, h in zip(refspk, rs):
        if h is not None: co[rsp.index(r), hsp.index(h)] += 1
    ri, hi = linear_sum_assignment(-co); mp = {hsp[h]: rsp[r] for r, h in zip(ri, hi)}
    matched = sum(1 for h in rs if h is not None)
    wrong = sum(1 for r, h in zip(refspk, rs) if h is not None and mp.get(h) != r)
    bad_lines = []
    for i, (sp, t) in enumerate(lines):
        hs = [rs[k] for k in np.where(lid == i)[0] if rs[k] is not None]
        if not hs: continue
        maj = max(set(hs), key=hs.count)
        if mp.get(maj) != sp: bad_lines.append(i + 1)
    times = [float(np.nanmin(rt[lid == i])) if np.any(~np.isnan(rt[lid == i])) else None for i in range(len(lines))]
    return dict(speakers=len(tot), speakers_2pct=sum(1 for v in tot.values() if v / allt >= 0.02),
                spk_time={k: round(v, 1) for k, v in sorted(tot.items(), key=lambda x: -x[1])},
                der_c25=S.der(R, hyp, 0.25), der_c0=S.der(R, hyp, 0.0),
                confT=wrong / max(1, matched), aligned=matched / len(ref),
                bad_lines=[(n, round(times[n - 1] or -1, 1)) for n in bad_lines])


if __name__ == "__main__":
    script, pcm, rout, base = sys.argv[1:5]
    lines = load_script(script)
    segs = build_ref(lines, json.load(open(base)), pcm, rout)
    for i, (sp, s, e, cov) in enumerate(segs):
        print("line %2d spk%d %7s %7s cov %.2f n %d" % (i + 1, sp, "%.2f" % s if s is not None else "-", "%.2f" % e if e is not None else "-", cov, len(lines[i][1])), file=sys.stderr)
    for p in sys.argv[4:]:
        r = score(lines, json.load(open(p)), rout)
        print(json.dumps(dict(file=p, **r), ensure_ascii=False))
