#!/usr/bin/env python3
"""Speaker-change boundary offsets of the app's transcripts (QualityEvalHarness asr: <name>.asr.json)
against AMI words.xml / AISHELL-4 TextGrids: how far, in tokens (en words / zh characters) and seconds,
the transcript puts the change between two consecutive speakers from where the reference puts it.

    /usr/bin/python3 -E -P score_boundaries.py <corpora dir> <harness out dir> NAME... > boundaries.json

Method
  1. Reference tokens in time order with their speaker and time (AMI: word times; AISHELL-4: characters
     spread linearly over their segment). Transcript tokens with the speaker of their utterance.
  2. Token alignment (difflib on normalized tokens, as score_asr.tokens) -> each matched reference token
     gets the transcript speaker, mapped to a reference speaker (Hungarian on matched-token counts).
  3. Reference turns = runs of one speaker. For each change A -> B where both turns have >= MIN_TOK
     tokens, look at the matched tokens of the two turns: if the transcript has both A and B there (the
     change was detected), the transcript boundary = split point that best agrees with A...A|B...B.
     offset = transcript split - reference split, in reference tokens (+ = late: B's first words left
     with A; - = early: A's last words given to B) and in seconds (reference times at both points).
  4. Overlap = the two reference turns overlap in time (A's last token ends after B's first starts).
Also: the transcript utterance boundary time vs the reference change time (does the stored time move
with the text?).
"""
import difflib, json, os, sys
import numpy as np
from scipy.optimize import linear_sum_assignment
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from score_asr import textgrid, ami_words, tokens

MIN_TOK = 3


def ref_tokens(corp, name):
    tg = os.path.join(corp, "aishell4", name + ".TextGrid")
    out = []
    if os.path.exists(tg):
        for spk, a, b, t in textgrid(tg):
            ts = tokens(t, "zh")
            for i, w in enumerate(ts):
                ta = a + (b - a) * i / len(ts); tb = a + (b - a) * (i + 1) / len(ts)
                out.append((spk, ta, tb, w))
        out.sort(key=lambda x: x[1])
        return "zh", out
    for spk, a, b, w in ami_words(os.path.join(corp, "ami-annot", "x"), name):
        for t in tokens(w, "en"):
            out.append((spk, a, b, t))
    return "en", out


def hyp_tokens(path, lang):
    d = json.load(open(path))
    out = []
    for u in d["utterances"]:
        ts = tokens(u["text"], lang)
        for i, w in enumerate(ts):
            out.append((u["speaker"], u["start"], u["end"], w, u["index"]))
    return out, d["utterances"]


def turns(ref):
    """runs of one speaker -> [(spk, i0, i1)] (i1 exclusive)"""
    out, i0 = [], 0
    for i in range(1, len(ref) + 1):
        if i == len(ref) or ref[i][0] != ref[i0][0]:
            out.append((ref[i0][0], i0, i)); i0 = i
    return out


def score(corp, outdir, name):
    lang, ref = ref_tokens(corp, name)
    hyp, utts = hyp_tokens(os.path.join(outdir, name + ".asr.json"), lang)
    sm = difflib.SequenceMatcher(None, [r[3] for r in ref], [h[3] for h in hyp], autojunk=False)
    match = {}  # ref index -> hyp index
    for blk in sm.get_matching_blocks():
        for k in range(blk.size):
            match[blk.a + k] = blk.b + k
    rs = sorted({r[0] for r in ref}); hs = sorted({h[0] for h in hyp})
    co = np.zeros((len(rs), len(hs)), int)
    for i, j in match.items():
        co[rs.index(ref[i][0]), hs.index(hyp[j][0])] += 1
    ri, hi = linear_sum_assignment(-co)
    hmap = {hs[h]: rs[r] for r, h in zip(ri, hi)}
    lab = {i: hmap.get(hyp[j][0], "?extra") for i, j in match.items()}

    res = []
    T = turns(ref)
    for (sa, a0, a1), (sb, b0, b1) in zip(T, T[1:]):
        if a1 - a0 < MIN_TOK or b1 - b0 < MIN_TOK:
            continue
        overlap = ref[a1 - 1][2] > ref[b0][1] + 1e-6
        # window: the last <= 40 tokens of A, the first <= 40 of B
        lo, hi_ = max(a0, a1 - 40), min(b1, b0 + 40)
        idx = [i for i in range(lo, hi_) if i in lab]
        labs = [lab[i] for i in idx]
        if sa not in labs or sb not in labs:
            bl = [lab[i] for i in range(b0, hi_) if i in lab]
            why = "B_given_to_A" if bl and bl.count(sa) * 2 > len(bl) else ("A_given_to_B" if sb in labs else "other")
            res.append(dict(t=ref[b0][1], overlap=overlap, detected=False, why=why, b_tok=b1 - b0))
            continue
        # best split over idx positions: tokens before split == sa, after == sb
        best, bk = -1, 0
        nb = sum(1 for l in labs if l == sb)
        na_before = 0; nb_before = 0
        for k in range(len(idx) + 1):
            sc = na_before + (nb - nb_before)
            if sc > best: best, bk = sc, k
            if k < len(idx):
                na_before += labs[k] == sa; nb_before += labs[k] == sb
        split = idx[bk] if bk < len(idx) else hi_
        off_tok = split - b0  # in reference tokens
        t_ref = ref[b0][1]
        t_hyp = ref[split][1] if split < len(ref) else ref[-1][2]
        # the transcript's own utterance boundary time near this change: hyp utterance containing the
        # first B-labelled matched token after the split
        hj = match.get(split) if split in match else None
        t_utt = utts[hyp[hj][4]]["start"] if hj is not None else None
        res.append(dict(t=t_ref, overlap=overlap, detected=True, off_tok=off_tok,
                        off_sec=round(t_hyp - t_ref, 3),
                        utt_sec=None if t_utt is None else round(t_utt - t_ref, 3),
                        purity=round(best / len(idx), 3)))
    return dict(name=name, lang=lang, ref_tokens=len(ref), hyp_tokens=len(hyp),
                matched=len(match), changes=res)


def summarize(rows):
    det = [r for r in rows if r["detected"]]
    und = [r for r in rows if not r["detected"]]
    why = {k: sum(1 for r in und if r["why"] == k) for k in ("B_given_to_A", "A_given_to_B", "other")}
    if not det:
        return dict(n=len(rows), detected=0, missed=why)
    o = np.array([abs(r["off_tok"]) for r in det]); s = np.array([abs(r["off_sec"]) for r in det])
    u = np.array([abs(r["utt_sec"]) for r in det if r["utt_sec"] is not None])
    sg = np.array([r["off_tok"] for r in det])
    return dict(n=len(rows), detected=len(det), missed=why,
                missed_b_tok_median=float(np.median([r["b_tok"] for r in und])) if und else None,
                exact=round(float((o == 0).mean()), 3),
                le2=round(float((o <= 2).mean()), 3), le5=round(float((o <= 5).mean()), 3),
                gt10=round(float((o > 10).mean()), 3),
                tok_median=float(np.median(o)), tok_p75=float(np.percentile(o, 75)), tok_p90=float(np.percentile(o, 90)),
                late_share=round(float((sg > 0).mean()), 3), early_share=round(float((sg < 0).mean()), 3),
                sec_median=round(float(np.median(s)), 2), sec_p75=round(float(np.percentile(s, 75)), 2),
                sec_p90=round(float(np.percentile(s, 90)), 2),
                le05s=round(float((s <= 0.5).mean()), 3), le1s=round(float((s <= 1.0).mean()), 3),
                utt_sec_median=round(float(np.median(u)), 2) if len(u) else None,
                utt_sec_p90=round(float(np.percentile(u, 90)), 2) if len(u) else None)


if __name__ == "__main__":
    corp, outdir = sys.argv[1], sys.argv[2]
    per = [score(corp, outdir, n) for n in sys.argv[3:]]
    agg = {}
    for lang in ("en", "zh"):
        rows = [c for p in per if p["lang"] == lang for c in p["changes"]]
        agg[lang] = dict(all=summarize(rows),
                         overlap=summarize([r for r in rows if r["overlap"]]),
                         clean=summarize([r for r in rows if not r["overlap"]]))
    for p in per:
        p["summary"] = summarize(p["changes"])
    json.dump(dict(aggregate=agg, meetings=[{k: v for k, v in p.items() if k != "changes"} for p in per],
                   changes={p["name"]: p["changes"] for p in per}), sys.stdout, ensure_ascii=False, indent=1)
