#!/usr/bin/env python3
"""Would a "suggested boundary" at the diarizer's own B-turn start beat the transcript's split?
    /usr/bin/python3 -E -P score_boundary_suggest.py <corpora dir> boundaries.json   (score_boundaries.py output)
Turns: <corpora>/work/turns/<name>.turns.json (run_turns.sh). Suggestion = start of the nearest turn (+-3 s) of the
speaker mapped to B; offsets in reference tokens, as score_boundaries.py."""
import json, os, sys, bisect, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from score_boundaries import ref_tokens, turns
from scipy.optimize import linear_sum_assignment
C = sys.argv[1]
B = json.load(open(sys.argv[2]))
rows = []
for name, changes in B["changes"].items():
    lang, ref = ref_tokens(C, name)
    tj = json.load(open(f"{C}/work/turns/{name}.turns.json"))
    # map diarizer speakers -> ref speakers by time overlap (ref token spans)
    rs = sorted({r[0] for r in ref}); ds = sorted({t["spk"] for t in tj})
    co = np.zeros((len(rs), len(ds)))
    starts = [r[1] for r in ref]
    for t in tj:
        i0 = bisect.bisect_left(starts, t["start"]); i1 = bisect.bisect_left(starts, t["end"])
        for r in ref[i0:i1]: co[rs.index(r[0]), ds.index(t["spk"])] += 1
    ri, di = linear_sum_assignment(-co)
    dmap = {ds[d]: rs[r] for r, d in zip(ri, di)}
    T = turns(ref)
    tb = {}
    for (sa, a0, a1), (sb, b0, b1) in zip(T, T[1:]):
        tb[round(ref[b0][1], 3)] = (sa, sb, b0)
    for c in changes:
        if not c["detected"]: continue
        sa, sb, b0 = tb[round(c["t"], 3)]
        t_hyp = c["t"] + c["off_sec"]
        cand = [t["start"] for t in tj if dmap.get(t["spk"]) == sb and abs(t["start"] - t_hyp) <= 3.0]
        if not cand:
            rows.append((lang, c["overlap"], abs(c["off_tok"]), None)); continue
        ts = min(cand, key=lambda x: abs(x - t_hyp))
        k = bisect.bisect_left(starts, ts - 0.05)
        rows.append((lang, c["overlap"], abs(c["off_tok"]), abs(k - b0)))
for lang in ("en", "zh"):
    for ov in (True, False):
        r = [x for x in rows if x[0] == lang and x[1] == ov]
        s = [x for x in r if x[3] is not None]
        if not s: continue
        h = np.array([x[2] for x in s]); g = np.array([x[3] for x in s])
        print(lang, "overlap" if ov else "clean", "n", len(r), "with_sugg", len(s),
              "hyp exact %.2f le2 %.2f med %.1f" % ((h == 0).mean(), (h <= 2).mean(), np.median(h)),
              "| sugg exact %.2f le2 %.2f med %.1f" % ((g == 0).mean(), (g <= 2).mean(), np.median(g)),
              "| sugg better %.2f worse %.2f" % ((g < h).mean(), (g > h).mean()))
