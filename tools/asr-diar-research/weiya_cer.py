#!/usr/bin/env python3
"""尾牙 demo: speaker-agnostic CER of transcripts against the TTS script (docs/demo/weiya-meeting-zhTW.txt), after the
normalization of weiya_score.py (t2s, an2cn, keep CJK + alnum). The TTS audio is the script read verbatim, so the
script is the reference text.   venv/bin/python -I weiya_cer.py SCRIPT.txt A.asr.json [B.asr.json ...]
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from weiya_score import load_script, norm


def ed(a, b):
    """Levenshtein with (sub, del, ins) counts; a = reference."""
    n, m = len(a), len(b)
    prev = [(j, 0, 0, j) for j in range(m + 1)]  # (cost, sub, del, ins)
    for i in range(1, n + 1):
        cur = [(i, 0, i, 0)]
        for j in range(1, m + 1):
            c_sub = prev[j - 1]; c_del = prev[j]; c_ins = cur[j - 1]
            if a[i - 1] == b[j - 1]:
                best = c_sub
            else:
                best = min((c_sub[0] + 1, c_sub[1] + 1, c_sub[2], c_sub[3]), (c_del[0] + 1, c_del[1], c_del[2] + 1, c_del[3]),
                           (c_ins[0] + 1, c_ins[1], c_ins[2], c_ins[3] + 1))
            cur.append(best)
        prev = cur
    return prev[m]


ref = "".join(t for _, t in load_script(sys.argv[1]))
for p in sys.argv[2:]:
    d = json.load(open(p))
    hyp = norm("".join(u["text"] for u in sorted(d["utterances"], key=lambda u: u["start"])))
    c, s, de, i = ed(ref, hyp)
    print(json.dumps(dict(file=p, ref=len(ref), hyp=len(hyp), cer=round(c / len(ref), 4), sub=s, dele=de, ins=i)))
