#!/usr/bin/env python3
"""Score the app's transcripts (QualityEvalHarness asr: <name>.asr.json) against AISHELL-4 / AMI references.

    /usr/bin/python3 -E -P score_asr.py <corpora dir> <harness out dir> [nemo_eval turns dir] > asr_scores.json

Per meeting:
  er        long-form, speaker-agnostic error rate: reference words (AMI) or characters (AISHELL-4) in
            time order against the whole transcript in time order (CER for zh, WER for en)
  cp_er     concatenated-minimum-permutation error rate (cpWER/cpCER): each reference speaker's text
            against one transcript speaker's text, speakers matched to minimize errors; extra or missing
            speakers count as insertions / deletions. Attribution errors show up here.
  der_*     diarization error rate on the transcript segments (what the app shows) and, if given, on the
            diarizer's own turns (nemo_eval_noggml <name>.turns.json); overlap included, optimal mapping,
            with a 0.25 s collar (md-eval convention) and without (pyannote's benchmark convention)
  spk       reference speakers vs speakers in the transcript (and those holding >= 2 % of its time)
  rtf       harness wall time / audio (the import path: gain, silence skipping, engine, 4 threads)
Normalization: lower case, punctuation and AISHELL-4 markup (<sil>, <%>, ...) removed; English fillers
(um, uh, mm, hmm, mm-hmm, uh-huh, ah, eh) removed from both sides; numbers are NOT normalized.
"""
import json, os, re, subprocess, sys, tempfile, xml.etree.ElementTree as ET
from itertools import groupby
import numpy as np
from scipy.optimize import linear_sum_assignment

HERE = os.path.dirname(os.path.abspath(__file__))
EDIT = os.path.join(HERE, "build", "editdist")
FILLERS = {"um", "uh", "mm", "hmm", "mm-hmm", "uh-huh", "ah", "eh", "uhm", "umm", "mhm", "hm"}
CJK = re.compile(r"[㐀-鿿豈-﫿]")

# ------------------------------------------------------------------------------ text normalization
def tokens(text, lang):
    t = re.sub(r"<[^>]*>", " ", text).lower()
    if lang == "zh":
        out = []
        for m in re.finditer(r"[㐀-鿿豈-﫿]|[a-z0-9']+", t):
            w = m.group(0)
            if CJK.match(w) or w.strip("'") and w not in FILLERS:
                out.append(w.strip("'") if not CJK.match(w) else w)
        return [w for w in out if w]
    t = t.replace("’", "'")
    words = re.findall(r"[a-z0-9]+(?:['-][a-z0-9]+)*", t)
    return [w for w in words if w not in FILLERS]

_vocab = {}
def ids(ws):
    return [_vocab.setdefault(w, len(_vocab)) for w in ws]

def edit(ref, hyp):
    """(N, S, D, I) between two token lists."""
    if not ref: return (0, 0, 0, len(hyp))
    if not hyp: return (len(ref), 0, len(ref), 0)
    with tempfile.NamedTemporaryFile("w", delete=False) as a, tempfile.NamedTemporaryFile("w", delete=False) as b:
        a.write("\n".join(map(str, ids(ref)))); b.write("\n".join(map(str, ids(hyp))))
    try:
        n, s, d, i = map(int, subprocess.check_output([EDIT, a.name, b.name]).split())
    finally:
        os.unlink(a.name); os.unlink(b.name)
    return (n, s, d, i)

# ------------------------------------------------------------------------------ references
def textgrid(path):
    """AISHELL-4 TextGrid -> [(speaker, start, end, text)]"""
    segs, spk, xmin, xmax = [], None, None, None
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line.startswith("name ="): spk = line.split("=", 1)[1].strip().strip('"')
        elif line.startswith("xmin ="): xmin = float(line.split("=")[1])
        elif line.startswith("xmax ="): xmax = float(line.split("=")[1])
        elif line.startswith("text ="):
            txt = line.split("=", 1)[1].strip().strip('"')
            if txt.strip(): segs.append((spk, xmin, xmax, txt))
    return sorted(segs, key=lambda s: s[1])

def ami_words(annot, meeting):
    """AMI words.xml of all speakers -> [(speaker, start, end, word)] in time order."""
    out = []
    for f in sorted(os.listdir(os.path.join(annot, "words"))):
        if not f.startswith(meeting + ".") or not f.endswith(".words.xml"): continue
        spk = f.split(".")[1]
        for w in ET.parse(os.path.join(annot, "words", f)).getroot():
            if not w.tag.endswith("w") or w.get("punc") == "true" or w.get("starttime") is None: continue
            out.append((spk, float(w.get("starttime")), float(w.get("endtime") or w.get("starttime")), w.text or ""))
    return sorted(out, key=lambda x: x[1])

def rttm(path):
    segs = []
    for line in open(path):
        p = line.split()
        if len(p) >= 8 and p[0] == "SPEAKER":
            segs.append((p[7], float(p[3]), float(p[3]) + float(p[4])))
    return segs

# ------------------------------------------------------------------------------ DER
def der(ref, hyp, collar, frame=0.01):
    end = max([e for _, _, e in ref] + [e for _, _, e in hyp] + [0])
    n = int(end / frame) + 2
    rs = sorted({s for s, _, _ in ref}); hs = sorted({s for s, _, _ in hyp})
    R = np.zeros((len(rs), n), bool); H = np.zeros((max(1, len(hs)), n), bool)
    for s, a, b in ref: R[rs.index(s), int(a / frame):int(b / frame)] = True
    for s, a, b in hyp: H[hs.index(s), int(a / frame):int(b / frame)] = True
    keep = np.ones(n, bool)
    if collar > 0:
        c = int(collar / frame)
        for _, a, b in ref:
            for t in (a, b):
                i = int(t / frame); keep[max(0, i - c):i + c] = False
    R, H = R[:, keep], H[:, keep]
    nref, nhyp = R.sum(0), H.sum(0)
    co = R.astype(np.int32) @ H.T.astype(np.int32)
    ri, hi = linear_sum_assignment(-co)
    correct = np.zeros(R.shape[1], np.int32)
    for r, h in zip(ri, hi): correct += (R[r] & H[h])
    total = nref.sum()
    miss = np.maximum(nref - nhyp, 0).sum(); fa = np.maximum(nhyp - nref, 0).sum()
    conf = (np.minimum(nref, nhyp) - correct).sum()
    return dict(der=float((miss + fa + conf) / total), miss=float(miss / total), fa=float(fa / total), conf=float(conf / total))

# ------------------------------------------------------------------------------ main
def score(name, lang, ref_segs, ref_rttm, hyp_json, turns):
    h = json.load(open(hyp_json))
    utts = h["utterances"]
    ref_tok = [t for s in ref_segs for t in tokens(s[3], lang)]
    hyp_tok = [t for u in sorted(utts, key=lambda u: u["start"]) for t in tokens(u["text"], lang)]
    n, s, d, i = edit(ref_tok, hyp_tok)
    out = dict(meeting=name, lang=lang, audio_sec=h["audio_sec"], rtf=h["wall_sec"] / h["audio_sec"], gain=h["gain"],
               skipped_sec=h["skipped_sec"], ref_tokens=n, hyp_tokens=len(hyp_tok),
               er=(s + d + i) / n, sub=s / n, dele=d / n, ins=i / n)
    # cpER
    rspk = sorted({s_[0] for s_ in ref_segs})
    hspk = sorted({u["speaker"] for u in utts if u["speaker"] is not None})
    rtext = {k: [t for s_ in ref_segs if s_[0] == k for t in tokens(s_[3], lang)] for k in rspk}
    htext = {k: [t for u in sorted(utts, key=lambda u: u["start"]) if u["speaker"] == k for t in tokens(u["text"], lang)] for k in hspk}
    unl = [t for u in utts if u["speaker"] is None for t in tokens(u["text"], lang)]
    cost = np.zeros((len(rspk), max(1, len(hspk))))
    for a, rk in enumerate(rspk):
        for b, hk in enumerate(hspk):
            nn, ss, dd, ii = edit(rtext[rk], htext[hk]); cost[a, b] = ss + dd + ii - len(rtext[rk]) - len(htext[hk])
    ri, hi = linear_sum_assignment(cost) if hspk else ([], [])
    errs = 0; used_r, used_h = set(), set()
    for a, b in zip(ri, hi):
        nn, ss, dd, ii = edit(rtext[rspk[a]], htext[hspk[b]]); errs += ss + dd + ii; used_r.add(a); used_h.add(b)
    errs += sum(len(rtext[rspk[a]]) for a in range(len(rspk)) if a not in used_r)
    errs += sum(len(htext[hspk[b]]) for b in range(len(hspk)) if b not in used_h) + len(unl)
    out["cp_er"] = errs / n
    # DER
    ref = rttm(ref_rttm)
    hyp = [(u["speaker"], u["start"], u["end"]) for u in utts if u["speaker"] is not None]
    for c in (0.25, 0.0):
        tag = "c25" if c else "c0"
        out[f"der_transcript_{tag}"] = der(ref, hyp, c)
        if turns and os.path.exists(turns):
            tr = [(t["spk"], t["start"], t["end"]) for t in json.load(open(turns))]
            out[f"der_turns_{tag}"] = der(ref, tr, c)
    # speakers
    tot = {}
    for u in utts:
        if u["speaker"] is not None: tot[u["speaker"]] = tot.get(u["speaker"], 0) + u["end"] - u["start"]
    allt = sum(tot.values()) or 1
    out["spk_ref"] = len({s_ for s_, _, _ in ref})
    out["spk_hyp"] = len(tot)
    out["spk_hyp_2pct"] = sum(1 for v in tot.values() if v / allt >= 0.02)
    return out

if __name__ == "__main__":
    corp, hyp_dir = sys.argv[1], sys.argv[2]
    turns_dir = sys.argv[3] if len(sys.argv) > 3 else None
    res = []
    for f in sorted(os.listdir(hyp_dir)):
        if not f.endswith(".asr.json"): continue
        name = f[:-9]
        turns = os.path.join(turns_dir, name + ".turns.json") if turns_dir else None
        tg = os.path.join(corp, "aishell4", name + ".TextGrid")
        if os.path.exists(tg):
            res.append(score(name, "zh", textgrid(tg), os.path.join(corp, "aishell4", name + ".rttm"), os.path.join(hyp_dir, f), turns))
        else:
            ws = ami_words(os.path.join(corp, "ami-annot", "x"), name)
            res.append(score(name, "en", [(w[0], w[1], w[2], w[3]) for w in ws], os.path.join(corp, "ami-rttm", name + ".rttm"),
                             os.path.join(hyp_dir, f), turns))
        print(json.dumps({k: (round(v, 4) if isinstance(v, float) else v) for k, v in res[-1].items() if not isinstance(v, dict)}), file=sys.stderr)
    json.dump(res, sys.stdout, indent=1)
