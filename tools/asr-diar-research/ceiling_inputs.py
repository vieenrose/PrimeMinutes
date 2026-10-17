#!/usr/bin/env python3
"""Ceiling analysis inputs: cross the app's and the gold transcript/speakers into four reader inputs
(harness utterance JSON, see QualityEvalHarness.utterances):
  app_app   app text + app speakers (what the user gets; a copy of the harness' asr.json utterances)
  gold_app  gold text + app speakers (perfect ASR, the app's diarization)
  app_gold  app text + gold speakers (the app's ASR, perfect diarization)
  gold_gold gold text + gold speakers (gold_utts.py output)

Speaker transfer: a 10 ms speaker timeline is built from the other source (app: its utterance spans and
labels; gold: the RTTM, the longest covering segment wins in overlap). Each utterance is split at the
timeline's speaker changes inside it (runs < MIN_RUN s are absorbed by a neighbour) and its text is
cut proportionally to the speech time of each run (on characters for Han text, on words for Latin;
a Han cut moves to a punctuation mark within 4 characters). Speakers are renumbered by first appearance,
as the engine does. This approximates word-level attribution: the harness' asr.json has no token times.

    python3 -I ceiling_inputs.py <asr.json dir> <goldutts dir> <rttm dir...> --out DIR NAME...
"""
import argparse, json, os, re, sys

FR = 0.01
MIN_RUN = 0.6
PUNCT = set("，。？！、；：,.?!;:")


def load_rttm(paths, name):
    for d in paths:
        p = os.path.join(d, name + ".rttm")
        if os.path.exists(p):
            segs = []
            for line in open(p):
                f = line.split()
                if len(f) >= 8 and f[0] == "SPEAKER":
                    s, dur = float(f[3]), float(f[4])
                    segs.append((s, s + dur, f[7]))
            return segs
    raise FileNotFoundError(name + ".rttm")


def timeline_from_segments(segs, n):
    """segs: (start, end, label); longest segment wins where they overlap."""
    tl = [None] * n
    best = [0.0] * n
    for s, e, lab in segs:
        dur = e - s
        for i in range(max(0, int(s / FR)), min(n, int(e / FR) + 1)):
            if tl[i] is None or dur > best[i]:
                tl[i], best[i] = lab, dur
    return tl


def nearest_label(tl, i):
    for d in range(0, len(tl)):
        for j in (i - d, i + d):
            if 0 <= j < len(tl) and tl[j] is not None:
                return tl[j]
    return None


def runs_in(tl, s, e):
    a, b = max(0, int(s / FR)), min(len(tl), max(int(s / FR) + 1, int(e / FR)))
    runs = []  # [label, frames]
    for i in range(a, b):
        lab = tl[i]
        if lab is None:
            continue
        if runs and runs[-1][0] == lab:
            runs[-1][1] += 1
        else:
            runs.append([lab, 1])
    if not runs:
        return [[nearest_label(tl, (a + b) // 2), 1]]
    # absorb short runs into the longer neighbour, then merge equal neighbours
    changed = True
    while changed and len(runs) > 1:
        changed = False
        k = min(range(len(runs)), key=lambda j: runs[j][1])
        if runs[k][1] * FR < MIN_RUN:
            left = runs[k - 1] if k > 0 else None
            right = runs[k + 1] if k + 1 < len(runs) else None
            tgt = left if (right is None or (left is not None and left[1] >= right[1])) else right
            tgt[1] += runs[k][1]
            del runs[k]
            merged = [runs[0]]
            for r in runs[1:]:
                if r[0] == merged[-1][0]:
                    merged[-1][1] += r[1]
                else:
                    merged.append(r)
            runs = merged
            changed = True
    return runs


def is_latin(text):
    lat = len(re.findall(r"[A-Za-z]", text))
    han = len(re.findall(r"[㐀-鿿]", text))
    return lat > han


def split_text(text, weights):
    if len(weights) == 1:
        return [text]
    tot = float(sum(weights))
    if is_latin(text):
        toks = text.split(" ")
        n = len(toks)
        cuts, acc = [], 0
        for w in weights[:-1]:
            acc += w
            cuts.append(round(n * acc / tot))
        parts, prev = [], 0
        for c in cuts + [n]:
            c = max(prev, min(n, c))
            parts.append(" ".join(toks[prev:c]))
            prev = c
        return parts
    n = len(text)
    cuts, acc = [], 0
    for w in weights[:-1]:
        acc += w
        c = round(n * acc / tot)
        for d in range(0, 5):  # snap to a punctuation mark nearby
            if 0 < c + d <= n and text[c + d - 1] in PUNCT:
                c = c + d; break
            if 0 < c - d <= n and text[c - d - 1] in PUNCT:
                c = c - d; break
        cuts.append(c)
    parts, prev = [], 0
    for c in cuts + [n]:
        c = max(prev, min(n, c))
        parts.append(text[prev:c])
        prev = c
    return parts


def transfer(utts, tl):
    out = []
    for u in utts:
        runs = runs_in(tl, u["start"], u["end"])
        parts = split_text(u["text"], [r[1] for r in runs])
        t = u["start"]
        span = max(u["end"] - u["start"], 0.0)
        tot = sum(r[1] for r in runs)
        for (lab, fr), txt in zip(runs, parts):
            dur = span * fr / tot
            if txt.strip():
                out.append(dict(text=txt.strip(), start=round(t, 2), end=round(t + dur, 2), label=lab))
            t += dur
    return out


def renumber(items):
    ids, res = {}, []
    for i, u in enumerate(sorted(items, key=lambda x: x["start"])):
        lab = u.get("label")
        spk = None if lab is None else ids.setdefault(lab, len(ids))
        res.append(dict(index=i, text=u["text"], start=u["start"], end=u["end"], speaker=spk))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("asr_dir"); ap.add_argument("gold_dir"); ap.add_argument("rttm_dirs", nargs="+")
    ap.add_argument("--out", required=True); ap.add_argument("--names", nargs="+", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for name in a.names:
        app = json.load(open(os.path.join(a.asr_dir, name + ".asr.json")))["utterances"]
        gold = json.load(open(os.path.join(a.gold_dir, name + ".gold.json")))["utterances"]
        rttm = load_rttm(a.rttm_dirs, name)
        end = max([u["end"] for u in app] + [u["end"] for u in gold] + [e for _, e, _ in rttm]) + 5
        n = int(end / FR) + 1
        app_tl = timeline_from_segments([(u["start"], u["end"], "a%d" % u["speaker"]) for u in app if u["speaker"] is not None], n)
        gold_tl = timeline_from_segments(rttm, n)
        sets = {
            "app_app": renumber([dict(u, label=u["speaker"]) for u in app]),
            "gold_gold": renumber([dict(u, label=u["speaker"]) for u in gold]),
            "gold_app": renumber(transfer(gold, app_tl)),
            "app_gold": renumber(transfer(app, gold_tl)),
        }
        for k, utts in sets.items():
            json.dump(dict(name=name, source=k, utterances=utts),
                      open(os.path.join(a.out, "%s.%s.json" % (name, k)), "w"), ensure_ascii=False)
        print(name, {k: (len(v), len({u['speaker'] for u in v})) for k, v in sets.items()})


if __name__ == "__main__":
    main()
