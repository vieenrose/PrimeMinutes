#!/usr/bin/env python3
"""Readable gold transcripts (one line per reference utterance: [mm:ss-mm:ss] speaker: text) from the
AISHELL-4 TextGrids and the AMI words.xml, the input of the reference key-point / question pass.
    /usr/bin/python3 -E -P gold_transcripts.py <corpora dir> <out dir> NAME..."""
import os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from score_asr import textgrid, ami_words

def mmss(t): return f"{int(t // 60):02d}:{int(t % 60):02d}"

corp, out = sys.argv[1], sys.argv[2]
for name in sys.argv[3:]:
    tg = os.path.join(corp, "aishell4", name + ".TextGrid")
    lines = []
    if os.path.exists(tg):
        for spk, a, b, t in textgrid(tg):
            t = re.sub(r"<[^>]*>", "", t).strip()
            if t: lines.append((a, b, spk, t))
    else:
        ws = ami_words(os.path.join(corp, "ami-annot", "x"), name)
        cur = None
        for spk, a, b, w in ws:
            if cur and cur[2] == spk and a - cur[1] < 1.0:
                cur[1] = max(cur[1], b); cur[3].append(w)
            else:
                if cur: lines.append((cur[0], cur[1], cur[2], " ".join(cur[3])))
                cur = [a, b, spk, [w]]
        if cur: lines.append((cur[0], cur[1], cur[2], " ".join(cur[3])))
        lines.sort()
    with open(os.path.join(out, name + ".gold.txt"), "w", encoding="utf-8") as f:
        for a, b, spk, t in lines:
            f.write(f"[{mmss(a)}-{mmss(b)}] {spk}: {t}\n")
    print(name, len(lines), "lines")
