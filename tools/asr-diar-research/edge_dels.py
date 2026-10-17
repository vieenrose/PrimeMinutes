#!/usr/bin/env python3
"""Where do the deletions of a segmented transcript sit? AMI (en) or AISHELL-4 (zh) reference tokens carry times
(AMI: word times; AISHELL-4: segment times, characters spread evenly). Each reference token goes to the hypothesis
segment that holds its midpoint (or to "gap" when none does); every segment's reference tokens are aligned to its
own text (Levenshtein with backtrace) and every deleted token is classed by where it sits in the segment:
head (< EDGE s after start), tail (< EDGE s before end), mid, or gap (outside every segment). Diagnostic only:
the long-form error rate of score_asr.py stays the metric.
    venv/bin/python -I edge_dels.py CORPORA en|zh DIR [DIR ...]
"""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "quality-eval"))
import score_asr as S

EDGE = 0.5
EN = ["ES2004b", "ES2004d", "IS1009c", "TS3003c"]
ZH = ["L_R003S02C02", "L_R004S06C01", "M_R003S02C01", "M_R003S05C01", "S_R003S03C01", "S_R004S01C01"]


def align_dels(ref, hyp):
    """indices of ref tokens deleted in a minimum edit alignment, plus (S, I)."""
    n, m = len(ref), len(hyp)
    D = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1): D[i][0] = i
    for j in range(m + 1): D[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            D[i][j] = min(D[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]), D[i - 1][j] + 1, D[i][j - 1] + 1)
    i, j, dels, sub, ins = n, m, [], 0, 0
    while i > 0 or j > 0:
        if i > 0 and j > 0 and D[i][j] == D[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            sub += ref[i - 1] != hyp[j - 1]; i -= 1; j -= 1
        elif i > 0 and D[i][j] == D[i - 1][j] + 1:
            dels.append(i - 1); i -= 1
        else:
            ins += 1; j -= 1
    return dels, sub, ins


def ref_tokens(corp, lang, name):
    out = []
    if lang == "en":
        for spk, s, e, w in S.ami_words(os.path.join(corp, "ami-annot", "x"), name):
            for t in S.tokens(w, "en"):
                out.append(((s + e) / 2, t))
    else:
        for spk, s, e, txt in S.textgrid(os.path.join(corp, "aishell4", name + ".TextGrid")):
            ts = S.tokens(txt, "zh")
            for k, t in enumerate(ts):
                out.append((s + (k + 0.5) * (e - s) / max(1, len(ts)), t))
    return sorted(out)


def main():
    corp, lang, dirs = sys.argv[1], sys.argv[2], sys.argv[3:]
    names = EN if lang == "en" else ZH
    refs = {n: ref_tokens(corp, lang, n) for n in names}
    for d in dirs:
        c = dict(ref=0, gap=0, head=0, tail=0, mid=0, sub=0, ins=0)
        for n in names:
            segs = sorted((u["start"], u["end"], S.tokens(u["text"], lang)) for u in json.load(open(os.path.join(d, n + ".asr.json")))["utterances"])
            bucket = [[] for _ in segs]
            k = 0
            for t, w in refs[n]:
                c["ref"] += 1
                while k < len(segs) and segs[k][1] <= t: k += 1
                if k < len(segs) and segs[k][0] <= t < segs[k][1]: bucket[k].append((t, w))
                else: c["gap"] += 1
            for (s, e, hyp), rw in zip(segs, bucket):
                dels, sub, ins = align_dels([w for _, w in rw], hyp)
                c["sub"] += sub; c["ins"] += ins
                for i in dels:
                    t = rw[i][0]
                    c["tail" if e - t < EDGE else "head" if t - s < EDGE else "mid"] += 1
        N = c["ref"]
        print(f"{os.path.basename(d.rstrip('/')):22} {lang} del: gap {100*c['gap']/N:4.1f} head {100*c['head']/N:4.1f} "
              f"tail {100*c['tail']/N:4.1f} mid {100*c['mid']/N:4.1f} | sub {100*c['sub']/N:4.1f} ins {100*c['ins']/N:4.1f}")


if __name__ == "__main__":
    main()
