#!/usr/bin/env python3
"""Per-character time shift between two engine timelines (NEMO_DUMP_TIMELINE) of the same audio: chars aligned with
difflib (equal blocks only, letters/CJK only), shift = t(B) - t(A). Prints median / quartiles / share |shift| > 0.5 s,
and the same around speaker changes of A's segments is not needed: the turns are the diarizer's, identical in A and B.
  tl_shift.py A.json B.json"""
import json, sys, difflib, statistics as st
def load(p):
    d = json.load(open(p)); return [x for x in d if x["c"].strip() and (x["c"].isalnum())]
a, b = load(sys.argv[1]), load(sys.argv[2])
sm = difflib.SequenceMatcher(None, [x["c"].lower() for x in a], [x["c"].lower() for x in b], autojunk=False)
sh = []
for blk in sm.get_matching_blocks():
    if blk.size < 8: continue
    for k in range(blk.size): sh.append(b[blk.b + k]["t"] - a[blk.a + k]["t"])
sh.sort(); n = len(sh); q = lambda f: sh[int(f * (n - 1))]
print(f"aligned {n}/{len(a)} chars  shift B-A: median {st.median(sh):+.3f}s  p10 {q(.1):+.3f}  p25 {q(.25):+.3f}  p75 {q(.75):+.3f}  p90 {q(.9):+.3f}  |>0.5s| {sum(abs(x)>0.5 for x in sh)/n:.1%}")
