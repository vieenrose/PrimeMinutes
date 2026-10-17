#!/usr/bin/env python3
"""Re-attribute a transcript's utterances to the speakers of another diarization (turns JSON), to measure what a
diarization change does to the transcript the user reads (cpER, transcript DER) rather than to frame DER.
Each utterance takes the turn speaker with the largest time overlap; no overlap -> nearest turn's speaker.
(Approximation of the app's attribution: the app labels an utterance from the diarizer's frames over its span.)

    python3 -I relabel.py ASR_DIR TURNS_DIR OUT_DIR
"""
import json, os, sys

src, tdir, dst = sys.argv[1:4]
os.makedirs(dst, exist_ok=True)
for f in sorted(os.listdir(src)):
    if not f.endswith(".asr.json"):
        continue
    tf = os.path.join(tdir, f[:-9] + ".turns.json")
    if not os.path.exists(tf):
        continue
    turns = json.load(open(tf)); d = json.load(open(os.path.join(src, f)))
    for u in d["utterances"]:
        ov = {}
        for t in turns:
            o = min(u["end"], t["end"]) - max(u["start"], t["start"])
            if o > 0:
                ov[t["spk"]] = ov.get(t["spk"], 0) + o
        if ov:
            u["speaker"] = max(ov, key=ov.get)
        elif turns:
            m = (u["start"] + u["end"]) / 2
            u["speaker"] = min(turns, key=lambda t: min(abs(t["start"] - m), abs(t["end"] - m)))["spk"]
    json.dump(d, open(os.path.join(dst, f), "w"), ensure_ascii=False)
