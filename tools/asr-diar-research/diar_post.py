#!/usr/bin/env python3
"""Diarization scoring and offline variants over diar_only outputs (<name>.probs.f32 [frames, 8] at 10 ms).

  score   DER (score_asr.der: own 10 ms frame scorer, overlap scored, optimal mapping) at collar 0.25 and 0,
          miss/FA/confusion, speaker count (all / >= 2 % of speech), for turns JSON files
  decode  re-decode the probabilities with another threshold / pad / min-duration (the app's decode rule:
          per speaker, frames >= threshold, runs padded by pad frames on both sides, kept if >= min frames)
  recluster  post-meeting re-clustering: speaker embeddings (sherpa-onnx speaker embedding extractor, e.g.
          CAM++) of each diarizer speaker's confident single-speaker frames, agglomerative merge of diarizer
          speakers whose centroid cosine >= --merge (fixes over-split; merges are applied to the turns)

    python3 -I diar_post.py score --corp CORPORA --turns DIR [--names ...]
    python3 -I diar_post.py decode --probs DIR --out DIR --thr 0.5 --pad 10 --min 0
    venv/bin/python -I diar_post.py recluster --probs DIR --audio-dir A --gain-dir APP --emb MODEL --merge 0.6 --out DIR
"""
import argparse, glob, json, os, sys
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "quality-eval"))
import score_asr as S  # der(), rttm()

FR = 0.01
MEETINGS = ["L_R003S02C02", "L_R004S06C01", "M_R003S02C01", "M_R003S05C01", "S_R003S03C01", "S_R004S01C01",
            "ES2004b", "ES2004d", "IS1009c", "TS3003c"]


def ref_rttm(corp, name):
    p = os.path.join(corp, "aishell4", name + ".rttm")
    return p if os.path.exists(p) else os.path.join(corp, "ami-rttm", name + ".rttm")


def decode(P, thr, pad, mn):
    T = P.shape[0]; turns = []
    for sp in range(P.shape[1]):
        act = P[:, sp] >= thr
        if not act.any():
            continue
        d = np.diff(np.concatenate([[0], act.astype(np.int8), [0]]))
        for s, e in zip(np.where(d == 1)[0], np.where(d == -1)[0]):
            ps, pe = max(0, s - pad), min(T, e + pad)
            if pe - ps >= mn:
                turns.append(dict(spk="speaker_%d" % sp, start=round(ps * FR, 3), end=round(pe * FR, 3)))
    return sorted(turns, key=lambda t: t["start"])


def score_turns(corp, name, turns):
    ref = S.rttm(ref_rttm(corp, name))
    hyp = [(t["spk"], t["start"], t["end"]) for t in turns]
    out = dict(meeting=name)
    for c, tag in ((0.25, "c25"), (0.0, "c0")):
        out[tag] = S.der(ref, hyp, c)
    tot = {}
    for t in turns:
        tot[t["spk"]] = tot.get(t["spk"], 0) + t["end"] - t["start"]
    allt = sum(tot.values()) or 1
    out["spk_ref"] = len({s for s, _, _ in ref}); out["spk_hyp"] = len(tot)
    out["spk_hyp_2pct"] = sum(1 for v in tot.values() if v / allt >= 0.02)
    return out


def summarize(rows):
    def agg(sel, tag):
        # time-weighted: DER components are fractions of reference speech; weight by meeting reference time
        w = np.array([r["ref_sec"] for r in sel]); 
        return {k: float(np.sum([r[tag][k] for r in sel] * w) / w.sum()) for k in ("der", "miss", "fa", "conf")}
    res = {}
    for lang, pre in (("zh", ("L_", "M_", "S_")), ("en", ("ES", "IS", "TS"))):
        sel = [r for r in rows if r["meeting"].startswith(pre)]
        if sel:
            res[lang] = dict(c25=agg(sel, "c25"), c0=agg(sel, "c0"), n=len(sel),
                             spk_exact=sum(r["spk_hyp"] == r["spk_ref"] for r in sel),
                             spk_exact_2pct=sum(r["spk_hyp_2pct"] == r["spk_ref"] for r in sel))
    return res


def brief(sm, tag):
    parts = []
    for lang, v in sm.items():
        parts.append("%s c25 %5.1f (miss %4.1f fa %4.1f conf %4.1f) c0 %5.1f spk %d/%d" % (
            lang, 100 * v["c25"]["der"], 100 * v["c25"]["miss"], 100 * v["c25"]["fa"], 100 * v["c25"]["conf"],
            100 * v["c0"]["der"], v["spk_exact_2pct"], v["n"]))
    return "%-28s %s" % (os.path.basename(tag.rstrip("/")), " | ".join(parts))


def cmd_sweep(a):
    """decode grid in memory and score each point (no files)"""
    cache = {n: (load_probs(a.probs, n), S.rttm(ref_rttm(a.corp, n)), ref_sec(a.corp, n)) for n in a.names
             if os.path.exists(os.path.join(a.probs, n + ".probs.f32"))}
    for thr in a.thrs:
        for pad in a.pads:
            for mn in a.mins:
                rows = []
                for n, (P, ref, rs) in cache.items():
                    r = score_turns(a.corp, n, decode(P, thr, pad, mn)); r["ref_sec"] = rs; rows.append(r)
                print(brief(summarize(rows), "thr%.2f pad%d min%d" % (thr, pad, mn)), flush=True)


def ref_sec(corp, name):
    ref = S.rttm(ref_rttm(corp, name))
    end = max(e for _, _, e in ref); m = np.zeros(int(end / FR) + 2, bool)
    for _, a, b in ref:
        m[int(a / FR):int(b / FR)] = True
    return m.sum() * FR


def cmd_score(a):
    rows = []
    for name in a.names:
        f = os.path.join(a.turns, name + ".turns.json")
        if not os.path.exists(f):
            continue
        r = score_turns(a.corp, name, json.load(open(f))); r["ref_sec"] = ref_sec(a.corp, name); rows.append(r)
    out = dict(rows=rows, summary=summarize(rows))
    print(brief(out["summary"], a.turns) if a.brief else json.dumps(out["summary"], indent=1))
    if a.json:
        json.dump(out, open(a.json, "w"), indent=1)


def load_probs(d, name):
    return np.fromfile(os.path.join(d, name + ".probs.f32"), dtype=np.float32).reshape(-1, 8)


def cmd_decode(a):
    os.makedirs(a.out, exist_ok=True)
    for name in a.names:
        if not os.path.exists(os.path.join(a.probs, name + ".probs.f32")):
            continue
        json.dump(decode(load_probs(a.probs, name), a.thr, a.pad, a.min), open(os.path.join(a.out, name + ".turns.json"), "w"))


def merge_speakers(emb, thr_m):
    """agglomerative merge of diarizer speakers on centroid cosine of their embeddings (average linkage on pieces)"""
    emb = {k: v.copy() for k, v in emb.items()}
    groups = {sp: [sp] for sp in emb}
    cent = {sp: emb[sp].mean(0) for sp in emb}
    merges = []
    while len(groups) > 1:
        keys = list(groups); best, bp = -1, None
        for i in range(len(keys)):
            for j in range(i + 1, len(keys)):
                ci, cj = cent[keys[i]], cent[keys[j]]
                c = float(ci @ cj / (np.linalg.norm(ci) * np.linalg.norm(cj) + 1e-9))
                if c > best:
                    best, bp = c, (keys[i], keys[j])
        if best < thr_m:
            break
        i, j = bp
        groups[i] += groups.pop(j); emb[i] = np.concatenate([emb[i], emb.pop(j)]); cent[i] = emb[i].mean(0); cent.pop(j)
        merges.append((i, j, round(best, 3)))
    lab = {}
    for g, members in groups.items():
        for m_ in members:
            lab[m_] = g
    return merges, lab


def cmd_recluster(a):
    import sherpa_onnx as so
    cfg = so.SpeakerEmbeddingExtractorConfig(model=a.emb, num_threads=a.threads)
    ext = so.SpeakerEmbeddingExtractor(cfg)
    os.makedirs(a.out, exist_ok=True)
    for name in a.names:
        pf = os.path.join(a.probs, name + ".probs.f32")
        if not os.path.exists(pf):
            continue
        P = load_probs(a.probs, name)
        gain = json.load(open(os.path.join(a.gain_dir, name + ".asr.json")))["gain"]
        audio = np.clip(np.fromfile(os.path.join(a.audio_dir, name + ".f32"), dtype=np.float32) * gain, -1, 1)
        turns = decode(P, a.thr, a.pad, a.min)
        spks = sorted({int(t["spk"].split("_")[1]) for t in turns})
        # confident single-speaker frames per diarizer speaker, cut into <= 3 s pieces of >= 1 s
        act = P >= a.thr; single = act.sum(1) == 1
        emb = {}
        for sp in spks:
            m = act[:, sp] & single & (P[:, sp] >= a.conf)
            d = np.diff(np.concatenate([[0], m.astype(np.int8), [0]]))
            pieces = []
            for s, e in zip(np.where(d == 1)[0], np.where(d == -1)[0]):
                for x in range(s, e, 300):
                    y = min(e, x + 300)
                    if y - x >= 100:
                        pieces.append((x, y))
            vs = []
            if len(pieces) > a.max_pieces:  # spread over the whole meeting, not its first minutes
                pieces = [pieces[i] for i in np.linspace(0, len(pieces) - 1, a.max_pieces).round().astype(int)]
            for x, y in pieces:
                st = ext.create_stream(); st.accept_waveform(16000, audio[x * 160:y * 160]); st.input_finished()
                v = np.array(ext.compute(st)); vs.append(v / (np.linalg.norm(v) + 1e-9))
            if vs:
                emb[sp] = np.array(vs)
        np.savez(os.path.join(a.out, name + ".emb.npz"), **{str(k): v for k, v in emb.items()})
        for thr_m in a.merge:
            merges, lab = merge_speakers(emb, thr_m)
            P2 = np.zeros_like(P)
            for sp in range(8):
                P2[:, lab.get(sp, sp)] = np.maximum(P2[:, lab.get(sp, sp)], P[:, sp])
            od = os.path.join(a.out, "m%.2f" % thr_m); os.makedirs(od, exist_ok=True)
            json.dump(decode(P2, a.thr, a.pad, a.min), open(os.path.join(od, name + ".turns.json"), "w"))
            print(name, "merge", thr_m, merges, flush=True)


def cmd_reassign(a):
    """post-meeting re-assignment: speaker centroids from the diarizer's confident single-speaker pieces
    (embeddings saved by recluster), then every decoded turn, cut into <= 3 s pieces, is re-labelled to
    the nearest centroid when it beats the diarizer's own label by more than --margin cosine."""
    import sherpa_onnx as so
    ext = so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(model=a.emb, num_threads=a.threads))
    os.makedirs(a.out, exist_ok=True)
    for name in a.names:
        ef = os.path.join(a.emb_dir, name + ".emb.npz")
        if not os.path.exists(ef):
            continue
        E = {int(k): v for k, v in np.load(ef).items()}
        merges, lab = merge_speakers(E, a.merge[0]) if a.merge else ([], {k: k for k in E})
        P = load_probs(a.probs, name)
        P2 = np.zeros_like(P)
        for sp in range(8):
            P2[:, lab.get(sp, sp)] = np.maximum(P2[:, lab.get(sp, sp)], P[:, sp])
        groups = {}
        for sp, g in lab.items():
            groups.setdefault(g, []).append(E[sp])
        C = {g: np.concatenate(v).mean(0) for g, v in groups.items()}
        C = {g: c / (np.linalg.norm(c) + 1e-9) for g, c in C.items()}
        gain = json.load(open(os.path.join(a.gain_dir, name + ".asr.json")))["gain"]
        audio = np.clip(np.fromfile(os.path.join(a.audio_dir, name + ".f32"), dtype=np.float32) * gain, -1, 1)
        turns = decode(P2, a.thr, a.pad, a.min); out = []; moved = 0.0
        for t in turns:
            sp = int(t["spk"].split("_")[1]); s0, e0 = t["start"], t["end"]
            x = s0
            while x < e0:
                y = min(e0, x + 3.0)
                if e0 - y < 1.0:
                    y = e0
                piece = dict(t, start=round(x, 2), end=round(y, 2))
                if y - x >= 1.0 and sp in C:
                    st = ext.create_stream(); st.accept_waveform(16000, audio[int(x * 16000):int(y * 16000)]); st.input_finished()
                    v = np.array(ext.compute(st)); v /= np.linalg.norm(v) + 1e-9
                    sc = {g: float(v @ c) for g, c in C.items()}
                    best = max(sc, key=sc.get)
                    if best != sp and sc[best] - sc[sp] > a.margin:
                        piece["spk"] = "speaker_%d" % best; moved += y - x
                out.append(piece); x = y
        json.dump(out, open(os.path.join(a.out, name + ".turns.json"), "w"))
        print(name, "moved %.0f s" % moved, flush=True)


def main():
    ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("score"); s.add_argument("--corp", required=True); s.add_argument("--turns", required=True); s.add_argument("--json")
    s.add_argument("--brief", action="store_true")
    w = sub.add_parser("sweep"); w.add_argument("--corp", required=True); w.add_argument("--probs", required=True)
    w.add_argument("--thrs", type=float, nargs="+", default=[0.3]); w.add_argument("--pads", type=int, nargs="+", default=[45])
    w.add_argument("--mins", type=int, nargs="+", default=[0])
    d = sub.add_parser("decode"); d.add_argument("--probs", required=True); d.add_argument("--out", required=True)
    r = sub.add_parser("recluster"); r.add_argument("--probs", required=True); r.add_argument("--out", required=True)
    r.add_argument("--audio-dir", required=True); r.add_argument("--gain-dir", required=True); r.add_argument("--emb", required=True)
    r.add_argument("--merge", type=float, nargs="+", default=[0.6]); r.add_argument("--conf", type=float, default=0.8)
    r.add_argument("--max-pieces", type=int, default=60); r.add_argument("--threads", type=int, default=2)
    q = sub.add_parser("reassign"); q.add_argument("--probs", required=True); q.add_argument("--out", required=True)
    q.add_argument("--emb-dir", required=True); q.add_argument("--audio-dir", required=True); q.add_argument("--gain-dir", required=True)
    q.add_argument("--emb", required=True); q.add_argument("--merge", type=float, nargs="*", default=[0.7])
    q.add_argument("--margin", type=float, default=0.1); q.add_argument("--threads", type=int, default=2)
    for p in (d, r, q):
        p.add_argument("--thr", type=float, default=0.3); p.add_argument("--pad", type=int, default=45); p.add_argument("--min", type=int, default=0)
    for p in (s, d, r, w, q):
        p.add_argument("--names", nargs="+", default=MEETINGS)
    a = ap.parse_args()
    {"score": cmd_score, "decode": cmd_decode, "recluster": cmd_recluster, "sweep": cmd_sweep, "reassign": cmd_reassign}[a.cmd](a)


if __name__ == "__main__":
    main()
