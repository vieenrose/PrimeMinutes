#!/usr/bin/env python3
"""One line per score_asr.py JSON: token-weighted error rate (zh CER, en WER), cpER, transcript DER c25 conf,
substitution/deletion/insertion split. Weighted by reference tokens (er, cp) or audio seconds (DER).

    python3 -I sum_scores.py scores/*.json
"""
import json, os, sys


def agg(rows):
    N = sum(r["ref_tokens"] for r in rows)
    w = lambda k: sum(r[k] * r["ref_tokens"] for r in rows) / N
    A = sum(r["audio_sec"] for r in rows)
    d = lambda k: sum(r["der_transcript_c25"][k] * r["audio_sec"] for r in rows) / A
    return "er %5.2f (S %4.1f D %4.1f I %4.1f) cp %5.2f confT %4.1f" % (
        100 * w("er"), 100 * w("sub"), 100 * w("dele"), 100 * w("ins"), 100 * w("cp_er"), 100 * d("conf"))


for f in sys.argv[1:]:
    rows = json.load(open(f))
    zh = [r for r in rows if r["lang"] == "zh"]; en = [r for r in rows if r["lang"] == "en"]
    print("%-16s n=%d/%d | zh %s | en %s" % (os.path.basename(f)[:-5], len(zh), len(en),
          agg(zh) if zh else "-", agg(en) if en else "-"))
