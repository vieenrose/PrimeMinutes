#!/usr/bin/env python3
"""Give a pseudo-streaming transcript the app's speakers at TOKEN level: every token (pseudo_stream.py stores the
offline model's token times) takes the speaker of the app line that covers its time + --offset (nearest line when
none does), and each run of same-speaker tokens becomes its own utterance. This is what a real integration would do
with the diarizer's turns (the engine's Fusion does it for the streaming text), so cpER / confT become comparable
with the app's.     python3 -I split_spk.py --offset 0.0 APP_DIR IN_DIR OUT_DIR
"""
import argparse, bisect, json, os

ap = argparse.ArgumentParser(); ap.add_argument("--offset", type=float, default=0.0)
ap.add_argument("app"); ap.add_argument("src"); ap.add_argument("dst"); a = ap.parse_args()
os.makedirs(a.dst, exist_ok=True)
for f in sorted(os.listdir(a.src)):
    if not f.endswith(".asr.json"):
        continue
    d = json.load(open(os.path.join(a.src, f)))
    tl = sorted((u["start"], u["end"], u["speaker"]) for u in json.load(open(os.path.join(a.app, f)))["utterances"] if u["speaker"] is not None)
    starts = [x[0] for x in tl]
    def spk_at(t):
        i = bisect.bisect_right(starts, t) - 1
        if i >= 0 and tl[i][0] <= t < tl[i][1]:
            return tl[i][2]
        cand = [c for c in (i, i + 1) if 0 <= c < len(tl)]
        return tl[min(cand, key=lambda c: min(abs(tl[c][0] - t), abs(tl[c][1] - t)))][2]
    out = []
    for u in d["utterances"]:
        words = []   # speaker changes only between words: a token without a leading space continues the word
        for tok, t in u.get("toks", []):
            cjk = any("\u3400" <= ch <= "\u9fff" for ch in tok)
            if words and not tok.startswith(" ") and not cjk and not words[-1][2]:
                words[-1][0] += tok
            else:
                words.append([tok, t, cjk])
        runs = []
        for w, t, _ in words:
            k = spk_at(t + a.offset)
            if runs and runs[-1][0] == k:
                runs[-1][1].append((w if w.startswith(" ") else " " + w, t))
            else:
                runs.append([k, [(w if w.startswith(" ") else " " + w, t)]])
        for j, (k, toks) in enumerate(runs):
            s = u["start"] if j == 0 else toks[0][1]
            e = u["end"] if j == len(runs) - 1 else runs[j + 1][1][0][1]
            txt = " ".join("".join(t for t, _ in toks).replace("▁", " ").split())
            if txt:
                out.append(dict(text=txt, start=round(s, 3), end=round(e, 3), speaker=k))
    for i, u in enumerate(out):
        u["index"] = i
    d["utterances"] = out
    json.dump(d, open(os.path.join(a.dst, f), "w"), ensure_ascii=False)
