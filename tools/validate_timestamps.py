#!/usr/bin/env python3
"""Re-derive the X-ASR token-timestamp offset against ground-truth silence (engine.h Config::token_offset_ms).

The transducer emits a token at the frame where it DECIDED the token, not at the audio that produced it, so
raw frame times run late; the offset is the shift that puts the tokens back on the audio. It is derived from
the audio - NOT from a WER or attribution score - as the share of tokens landing in ground-truth silence,
exactly the criterion engine.h documents (3 of 119 tokens at 0 ms, 2 at 450 ms, 0 at 300 ms on the bilingual
clip). Chunk length changes the decision lag, so each shipped chunk variant needs its own number: the sweep
here is per variant, on the same clips, with the same reference.

    /usr/bin/python3 -E -P validate_timestamps.py <corpora dir> <tokens dir> [--offsets 0,100,...] NAME...

<tokens dir> holds <name>.tokens.json as written by tools/nemo-eval nemo_eval --tokens ({"t","text","spk",
"snap"}, with the run's offset applied). The raw frame index is (t + offset/1000) / 0.040 - the encoder's
frame list does not depend on the offset, so one engine run answers the whole sweep.

Ground truth silence: AMI words.xml intervals (en), AISHELL-4 TextGrid segment intervals (zh). A token counts
as inside speech when some interval, widened by --tol (default 0.05 s), covers it. Reference: score_asr.py's
readers, so the definition matches the scoring pipeline.

The reference's own gaps matter: AMI word intervals leave a few tens of ms between words in fluent speech, and
a token time is a coarse (40 ms) mark on the audio, so gaps shorter than --gap-merge (default 0.25 s) are
merged into the speech - the criterion is the meeting's PAUSES, which is what "inside ground-truth silence"
meant when the 300 ms constant was derived (engine.h: 3 of 119 tokens at 0 ms, 2 at 450 ms, 0 at 300 ms).
"""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "quality-eval"))
from score_asr import ami_words, textgrid   # noqa: E402

FRAME_S = 0.040


def merge(iv, gap):
    """Speech intervals with the gaps shorter than [gap] merged - the pauses a token time can land in."""
    out = []
    for a, b in sorted(iv):
        if out and a - out[-1][1] < gap:
            out[-1] = (out[-1][0], max(out[-1][1], b))
        else:
            out.append((a, b))
    return out


def intervals(corp, name, tol, gap):
    tg = os.path.join(corp, "aishell4", name + ".TextGrid")
    if os.path.exists(tg):
        return "zh", merge([(a - tol, b + tol) for _, a, b, _ in textgrid(tg)], gap)
    return "en", merge([(a - tol, b + tol) for _, a, b, _ in ami_words(os.path.join(corp, "ami-annot", "x"), name)], gap)


def frames(tokens_path, run_offset_ms):
    out = []
    for t in json.load(open(tokens_path)):
        out.append((t["t"] + run_offset_ms / 1000.0) / FRAME_S)
    return out


def main():
    corp, tokdir = sys.argv[1], sys.argv[2]
    argv, offsets, tol, gap, run_off, names = sys.argv[3:], None, 0.05, 0.25, 300.0, []
    i = 0
    while i < len(argv):
        if argv[i] == "--offsets":
            offsets = [float(x) for x in argv[i + 1].split(",")]; i += 2
        elif argv[i] == "--tol":
            tol = float(argv[i + 1]); i += 2
        elif argv[i] == "--gap-merge":
            gap = float(argv[i + 1]); i += 2
        elif argv[i] == "--run-offset":
            run_off = float(argv[i + 1]); i += 2
        else:
            names.append(argv[i]); i += 1
    offsets = offsets or [float(o) for o in range(-200, 1201, 100)]

    per = {}
    for name in names:
        lang, iv = intervals(corp, name, tol, gap)
        fr = frames(os.path.join(tokdir, name + ".tokens.json"), run_off)
        iv = sorted(iv)
        per[name] = (lang, fr, iv)

    agg = {l: {o: [0, 0] for o in offsets} for l in ("en", "zh")}
    rows = []
    for name, (lang, fr, iv) in per.items():
        row = dict(meeting=name, lang=lang, tokens=len(fr), silence={})
        for o in offsets:
            ts = [f * FRAME_S - o / 1000.0 for f in fr]
            bad = sum(1 for t in ts if not any(a <= t <= b for a, b in iv))
            row["silence"][f"{o:g}"] = round(bad / max(1, len(ts)), 4)
            agg[lang][o][0] += bad; agg[lang][o][1] += len(ts)
        rows.append(row)

    out = dict(meetings=rows, aggregate={
        l: {f"{o:g}": round(bad / max(1, tot), 4) for o, (bad, tot) in agg[l].items()} for l in agg})
    # The per-variant pick: the offset with the least silence, tie-broken toward the middle of the plateau
    # of offsets that tie at that minimum (a number in the middle of the flat part is the robust pick, the
    # edge of a plateau is where the next 100 ms starts costing).
    pick = {}
    for l in agg:
        best = min(agg[l].items(), key=lambda kv: kv[1][0] / max(1, kv[1][1]))
        mn = best[1][0] / max(1, best[1][1])
        tied = sorted(o for o, (bad, tot) in agg[l].items() if bad / max(1, tot) <= mn + 1e-9)
        pick[l] = dict(best=best[0], share=round(mn, 4), plateau=tied)
    out["pick"] = pick
    json.dump(out, sys.stdout, indent=1)


if __name__ == "__main__":
    main()
