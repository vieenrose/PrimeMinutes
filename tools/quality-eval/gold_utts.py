#!/usr/bin/env python3
"""Gold transcript (gold_transcripts.py output) -> the harness' utterance JSON, so the reader can also read
the human transcript (separates ASR errors from reader errors).
    /usr/bin/python3 -E -P gold_utts.py <gold dir> <out dir> NAME..."""
import json, os, re, sys
gold, out = sys.argv[1], sys.argv[2]
for name in sys.argv[3:]:
    utts, spk = [], {}
    for line in open(os.path.join(gold, name + ".gold.txt"), encoding="utf-8"):
        m = re.match(r"\[(\d+):(\d+)-(\d+):(\d+)\] ([^:]+): (.*)", line.strip())
        if not m: continue
        a = int(m[1]) * 60 + int(m[2]); b = int(m[3]) * 60 + int(m[4])
        sid = spk.setdefault(m[5], len(spk))
        utts.append(dict(index=len(utts), text=m[6], start=float(a), end=float(max(b, a)), speaker=sid))
    json.dump(dict(name=name, source="gold", utterances=utts), open(os.path.join(out, name + ".gold.json"), "w"), ensure_ascii=False)
    print(name, len(utts))
