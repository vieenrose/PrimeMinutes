#!/usr/bin/env python3
"""Blind judging packets: one directory per meeting with the gold transcript and the reader outputs of
several conditions under shuffled letters (the letter -> condition key is written elsewhere).

Each output carries a speaker map: every S-label the reader saw is mapped to the gold speakers by time
overlap of that condition's utterances with the gold utterances (10 ms frames), so a judge can check
speaker-attribution claims ("S2 proposed ...") against the gold transcript (labelled G1, G2, ...).

    python3 -I judge_prep.py --meeting NAME --gold gold_gold.json --out PACKETS --keys KEYS \
        --seed 7 COND=utts.json:reader.json [COND=...]
"""
import argparse, json, os, random

FR = 0.01


def mmss(t):
    t = int(t); return "%d:%02d" % (t // 60, t % 60)


def timeline(utts, n, prefix):
    tl = [None] * n
    for u in utts:
        if u.get("speaker") is None:
            continue
        for i in range(max(0, int(u["start"] / FR)), min(n, int(u["end"] / FR))):
            tl[i] = "%s%d" % (prefix, u["speaker"] + 1)
    return tl


def speaker_map(utts, gold_tl):
    acc = {}
    for u in utts:
        if u.get("speaker") is None:
            continue
        s = "S%d" % (u["speaker"] + 1)
        d = acc.setdefault(s, {})
        for i in range(max(0, int(u["start"] / FR)), min(len(gold_tl), int(u["end"] / FR))):
            g = gold_tl[i] or "silence/none"
            d[g] = d.get(g, 0) + 1
    lines = []
    for s in sorted(acc, key=lambda x: int(x[1:])):
        tot = sum(acc[s].values()) or 1
        parts = sorted(acc[s].items(), key=lambda kv: -kv[1])
        lines.append("%s -> %s  (%.0f s)" % (s, ", ".join("%s %.0f%%" % (g, 100.0 * c / tot) for g, c in parts[:3] if c / tot >= 0.05), tot * FR))
    return lines


def render_output(r):
    out = []
    out.append("TITLE: " + (r.get("title") or ""))
    out.append("\nSUMMARY (prose):\n" + (r.get("prose_raw") or r.get("summary") or ""))
    out.append("\nMINUTES:\n" + (r.get("minutes_raw") or ""))
    out.append("\nNOTES:\n" + (r.get("notes") or ""))
    out.append("\nACTIONS:\n" + (r.get("actions") or ""))
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--meeting", required=True); ap.add_argument("--gold", required=True)
    ap.add_argument("--out", required=True); ap.add_argument("--keys", required=True)
    ap.add_argument("--seed", type=int, default=7); ap.add_argument("conds", nargs="+")
    a = ap.parse_args()
    gold = json.load(open(a.gold))["utterances"]
    n = int((max(u["end"] for u in gold) + 600) / FR)
    gold_tl = timeline(gold, n, "G")
    d = os.path.join(a.out, a.meeting); os.makedirs(d, exist_ok=True); os.makedirs(a.keys, exist_ok=True)
    with open(os.path.join(d, "gold_transcript.txt"), "w") as f:
        for u in gold:
            f.write("[%s] %s: %s\n" % (mmss(u["start"]), "G%d" % (u["speaker"] + 1) if u.get("speaker") is not None else "G?", u["text"]))
    conds = [c.split("=", 1) for c in a.conds]
    rnd = random.Random(a.seed * 7919 + sum(map(ord, a.meeting)))
    rnd.shuffle(conds)
    key = {}
    for i, (cond, paths) in enumerate(conds):
        letter = chr(ord("A") + i)
        up, rp = paths.split(":", 1)
        utts = json.load(open(up))["utterances"]
        r = json.load(open(rp))
        with open(os.path.join(d, "output_%s.txt" % letter), "w") as f:
            f.write("OUTPUT %s\n\nSPEAKER MAP (labels used in this output -> gold speakers, by overlap of speech time):\n" % letter)
            f.write("\n".join(speaker_map(utts, gold_tl)) + "\n\n")
            f.write(render_output(r) + "\n")
        key[letter] = cond
    json.dump(key, open(os.path.join(a.keys, a.meeting + ".json"), "w"), indent=1)
    print(a.meeting, "->", d, len(conds), "outputs")


if __name__ == "__main__":
    main()
