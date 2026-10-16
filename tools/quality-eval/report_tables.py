#!/usr/bin/env python3
"""Markdown tables for docs/quality-eval-2026-10.md from the score files.
    /usr/bin/python3 -E -P report_tables.py asr <asr_scores.json>
    /usr/bin/python3 -E -P report_tables.py notes <notes_scores.json>...
    /usr/bin/python3 -E -P report_tables.py ask <ask_scores.json>..."""
import json, sys

def pct(x): return "–" if x is None else f"{100 * x:.1f}"

def asr(path):
    rows = json.load(open(path))
    print("| Meeting | Lang | Min | Gain | ER (CER/WER) % | sub/del/ins % | cpER % | DER transcript c0.25 % | DER turns c0.25 % | DER turns c0 % | miss/FA/conf (turns c0.25) % | Speakers ref → app (≥2 %) |")
    print("|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---|")
    for lang in ("zh", "en"):
        sel = [r for r in rows if r["lang"] == lang]
        for r in sel:
            t = r["der_turns_c25"]
            print(f"| {r['meeting']} | {lang} | {r['audio_sec'] / 60:.0f} | {r['gain']:.1f} | {pct(r['er'])} | {pct(r['sub'])}/{pct(r['dele'])}/{pct(r['ins'])} | {pct(r['cp_er'])} | "
                  f"{pct(r['der_transcript_c25']['der'])} | {pct(t['der'])} | {pct(r['der_turns_c0']['der'])} | {pct(t['miss'])}/{pct(t['fa'])}/{pct(t['conf'])} | "
                  f"{r['spk_ref']} → {r['spk_hyp']} ({r['spk_hyp_2pct']}) |")
        # token-weighted error rates, time-weighted DER
        n = sum(r["ref_tokens"] for r in sel); a = sum(r["audio_sec"] for r in sel)
        er = sum(r["er"] * r["ref_tokens"] for r in sel) / n; cp = sum(r["cp_er"] * r["ref_tokens"] for r in sel) / n
        d = lambda k: sum(r[k]["der"] * r["audio_sec"] for r in sel) / a
        exact = sum(r["spk_ref"] == r["spk_hyp"] for r in sel); exact2 = sum(r["spk_ref"] == r["spk_hyp_2pct"] for r in sel)
        print(f"| **{'AISHELL-4' if lang == 'zh' else 'AMI'} ({len(sel)})** | | {a / 60:.0f} | | **{pct(er)}** | | **{pct(cp)}** | {pct(d('der_transcript_c25'))} | **{pct(d('der_turns_c25'))}** | {pct(d('der_turns_c0'))} | | exact {exact}/{len(sel)} ({exact2}/{len(sel)}) |")

def notes(paths):
    rows = [r for p in paths for r in json.load(open(p))]
    print("| Meeting | Transcript | Model | Coverage % | Decision+action recall % | Notes | Summary chars | Reader s (host) |")
    print("|---|---|---|---:|---:|---:|---:|---:|")
    for r in sorted(rows, key=lambda r: (r["lang"], r["meeting"], r["source"], r["model"])):
        print(f"| {r['meeting']} | {r['source']} | {r['model'].split('-')[1].upper()} | {pct(r['coverage'])} | {pct(r['decisions'])} | {r['notes']} | {r['summary_chars']} | {r['seconds']:.0f} |")
    print()
    print("| Corpus | Transcript | Model | Meetings | Coverage % (mean) | Decision+action recall % (pooled) |")
    print("|---|---|---|---:|---:|---:|")
    keys = sorted({(r["lang"], r["source"], r["model"]) for r in rows})
    for lang, src, model in keys:
        sel = [r for r in rows if (r["lang"], r["source"], r["model"]) == (lang, src, model)]
        cov = sum(r["coverage"] for r in sel) / len(sel)
        nd = sum(r["n_dec"] for r in sel); hd = sum((r["decisions"] or 0) * r["n_dec"] for r in sel)
        print(f"| {'AISHELL-4' if lang == 'zh' else 'AMI'} | {src} | {model.split('-')[1].upper()} | {len(sel)} | {pct(cov)} | {pct(hd / nd if nd else None)} |")

def ask(paths):
    print("| Run | Subset | n | Correct (keywords) % | Correct (judge) % | Partial (judge) % | Passage retrieved % | Answers citing % | Valid cites % | Right-source cites % | Latency p50 / p90 s | First token p50 s |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|")
    for p in paths:
        s = json.load(open(p))["summary"]
        for k in ("all", "zh", "en", "unanswerable"):
            v = s[k]
            if not v: continue
            ft = "–" if v["first_token_p50"] is None else f"{v['first_token_p50']:.1f}"
            print(f"| {p.split('/')[-1]} | {k} | {v['n']} | {pct(v['correct_kw'])} | {pct(v.get('judge_correct'))} | {pct(v.get('judge_partial'))} | {pct(v['retrieved'])} | "
                  f"{pct(v['answers_with_cite'])} | {pct(v['cite_valid'])} | {pct(v['cite_right'])} | {v['latency_p50']:.1f} / {v['latency_p90']:.1f} | {ft} |")

if __name__ == "__main__":
    {"asr": lambda: asr(sys.argv[2]), "notes": lambda: notes(sys.argv[2:]), "ask": lambda: ask(sys.argv[2:])}[sys.argv[1]]()
