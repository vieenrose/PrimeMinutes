#!/usr/bin/env python3
"""Keyword recall of the reader's output against the reference key points (refs/<NAME>.json), the
ReaderRegressionGate rule: a key point is covered when every keyword group has an alternative in the
output (title + summary + action items + notes; zh outputs in their Simplified conversion).
    /usr/bin/python3 -E -P score_notes.py <refs dir> <reader out dir>... > notes_scores.json
coverage = all key points; decisions = kinds decision + action (the "decision recall")."""
import json, os, re, sys

def hay(r, lang):
    h = r.get("hay_hans") if lang == "zh" else "\n".join([r.get("title") or "", r.get("summary") or "", r.get("actions") or "", r.get("notes") or ""])
    h = (h or "").lower()
    # digits grouped or spaced either way ("110,000", "11 萬") match the plain spelling (the gate's rule)
    h = re.sub(r"(?<=\d)[ ,](?=\d)", "", h)
    return re.sub(r"(?<=\d) (?=\D)|(?<=\D) (?=\d)", "", h)

def covered(item, h):
    return all(any(a.lower() in h for a in g) for g in item["all"])

refs = sys.argv[1]
rows = []
for d in sys.argv[2:]:
    for f in sorted(os.listdir(d)):
        m = re.match(r"(.+)\.reader\.(.+)\.json$", f)
        if not m: continue
        name, model = m[1], m[2]
        rp = os.path.join(refs, name + ".json")
        if not os.path.exists(rp): continue
        ref = json.load(open(rp)); r = json.load(open(os.path.join(d, f)))
        h = hay(r, ref["lang"])
        kp = ref["keypoints"]
        hit = [k["id"] for k in kp if covered(k, h)]
        dec = [k for k in kp if k["kind"] in ("decision", "action")]
        rows.append(dict(meeting=name, model=model, source=os.path.basename(os.path.normpath(d)), lang=ref["lang"],
                         coverage=len(hit) / len(kp), decisions=(sum(1 for k in dec if k["id"] in hit) / len(dec)) if dec else None,
                         n_kp=len(kp), n_dec=len(dec), missed=[k["id"] for k in kp if k["id"] not in hit],
                         notes=r.get("notes_count"), seconds=r.get("seconds"), summary_chars=len(r.get("summary") or ""),
                         title=r.get("title")))
        print(f"{name:14s} {model:40s} {rows[-1]['source']:10s} cov {rows[-1]['coverage']:.2f} dec {rows[-1]['decisions'] if dec else '-'} notes {r.get('notes_count')} {r.get('seconds', 0):.0f}s", file=sys.stderr)
json.dump(rows, sys.stdout, indent=1, ensure_ascii=False)
