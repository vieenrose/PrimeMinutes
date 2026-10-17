#!/usr/bin/env python3
"""Score clip-set hypotheses (run_clips.py *.jsonl) against the manifest references: mixed error rate (MER),
Han characters and Latin words as tokens, corpus-level (sum of edits / sum of reference tokens).
Normalization (both sides): OpenCC t2s (script-agnostic), lower case, Arabic numbers read out with cn2an
("100" -> "一百", "2025年" -> "二零二五年", "1.8" -> "一点八"; leftover digits folded one by one),
punctuation and spaces removed, English fillers (um, uh, ...) and the Chinese fillers 呃/嗯/啊 removed.
Also reports core-RTF (CPU seconds / audio seconds; 1 thread).

    python3 -I score_clips.py MANIFEST.jsonl HYP.jsonl [HYP.jsonl ...]
"""
import json, re, sys
import opencc
CC = opencc.OpenCC("t2s")
FILL = {"um", "uh", "mm", "hmm", "ah", "eh", "uhm", "umm", "mhm", "hm", "oh"}
HAN_FILL = set("呃嗯啊")
DIG = dict(zip("0123456789", "零一二三四五六七八九"))
try:
    import cn2an as CN2AN
except ImportError:  # fall back to the digit fold
    CN2AN = None


def toks(t):
    t = CC.convert(t or "").lower()
    t = re.sub(r"<[^>]*>", " ", t)
    if CN2AN is not None:
        try:
            t = CN2AN.transform(t, "an2cn")
        except Exception:
            pass
    t = "".join(DIG.get(c, c) for c in t)
    out = []
    for m in re.finditer(r"[㐀-鿿豈-﫿]|[a-z']+", t):
        w = m.group(0).strip("'")
        if not w or w in FILL or w in HAN_FILL:
            continue
        out.append(w)
    return out


def ed(r, h):
    n, m = len(r), len(h)
    if n == 0:
        return m
    prev = list(range(m + 1))
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        ri = r[i - 1]
        for j in range(1, m + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ri != h[j - 1]))
        prev = cur
    return prev[m]


def main():
    ref = {}
    for l in open(sys.argv[1]):
        it = json.loads(l); ref[it["id"]] = it
    files = sys.argv[2:]; common = None
    if files and files[0] == "--common":  # score only the clips every file has (partial runs)
        files = files[1:]
        for hf in files:
            ids = {json.loads(l)["id"] for l in open(hf)}
            common = ids if common is None else common & ids
        print(f"common clips: {len(common)}")
    for hf in files:
        E = N = 0; dur = cpu = 0.0; n = 0; lang = {}
        for l in open(hf):
            h = json.loads(l)
            if h["id"] not in ref or (common is not None and h["id"] not in common):
                continue
            r = toks(ref[h["id"]]["text"]); y = toks(h["hyp"]); e = ed(r, y)
            E += e; N += len(r); dur += h["dur"]; cpu += h["cpu"]; n += 1
            lg = ref[h["id"]].get("lang")
            if lg:
                a = lang.setdefault(lg, [0, 0]); a[0] += e; a[1] += len(r)
        extra = " ".join(f"{k} {100 * v[0] / max(1, v[1]):.2f}" for k, v in sorted(lang.items()))
        print(f"{hf.rsplit('/', 1)[-1]:40s} n={n:4d} MER {100 * E / max(1, N):6.2f} %  core-RTF {cpu / max(dur, 1e-9):.3f}  {extra}")


if __name__ == "__main__":
    main()
