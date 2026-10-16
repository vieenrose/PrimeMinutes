#!/usr/bin/env python3
"""Score an Ask run (QualityEvalHarness ask: ask.<model>.json) against the suite (ask_suite_v1.json).
    /usr/bin/python3 -E -P score_ask.py <suite.json> <library.json> <ask.json> [judge.json] > ask_scores.json
Per question:
  correct_kw   every keyword group of the expected answer appears in the answer (zh: Simplified conversion);
               for a question no meeting answers: the answer says so (refusal phrases)
  retrieved    some excerpt handed to the model comes from the expected meeting and contains the answer's
               keywords (the passage was there to be read)
  cites        [[entryId@sec]] tags in the answer; cite_valid = the tag names an excerpt of this answer
               (AskEngine.cite: same meeting, nearest excerpt); cite_right = that excerpt is from the expected
               meeting and holds the answer's keywords (or overlaps the expected span when it does not)
  latency      total and first-token seconds (host JVM, indicative only)
judge.json (optional): {id: "correct"|"partial"|"wrong"} from a reading of the answers; reported beside the keyword rule."""
import json, re, sys

suite = {q["id"]: q for q in json.load(open(sys.argv[1]))["questions"]}
lib = json.load(open(sys.argv[2]))            # meeting -> entry id
runs = json.load(open(sys.argv[3]))
judge = json.load(open(sys.argv[4])) if len(sys.argv) > 4 else {}
ent2m = {v: k for k, v in lib.items()}
REFUSE = re.compile(r"没有提到|未提到|没有提及|未提及|没有相关|找不到|没有找到|无法回答|无法从|没有资讯|没有信息|没有记录|并未|not mention|no mention|not (?:in|found)|no information|isn't (?:mentioned|in)|doesn't (?:mention|say)|do not (?:mention|contain)|does not (?:mention|contain|say)|cannot find|can't find|no record", re.I)
TAG = re.compile(r"\[\[([^@\]]+)@(\d+)\]\]")

def has(text, groups, lang):
    t = text.lower()
    t = re.sub(r"(?<=\d)[ ,](?=\d)", "", t)
    return all(any(a.lower() in t for a in g) for g in groups)

rows = []
for r in runs:
    q = suite[r["id"]]
    lang = q["lang"]
    ans = r["answer_hans"] if lang == "zh" else r["answer"]
    body = TAG.sub("", ans)
    if q["meeting"] is None:
        ok = bool(REFUSE.search(body))
        retrieved = None
    else:
        ok = has(body, q["all"], lang)
        retrieved = any(ent2m.get(x["entry"]) == q["meeting"] and has(x["text"], q["all"], lang) for x in r["excerpts"])
    cites = TAG.findall(ans)
    valid = right = 0
    for eid, sec in cites:
        same = [x for x in r["excerpts"] if x["entry"] == eid]
        if not same: continue
        valid += 1
        x = min(same, key=lambda x: abs(x["sec"] - int(sec)))
        if q["meeting"] and ent2m.get(eid) == q["meeting"]:
            a, b = q["span"]
            if has(x["text"], q["all"], lang) or (x["at"] <= b + 5 and x["at"] >= a - 240):
                right += 1
    rows.append(dict(id=r["id"], lang=lang, answerable=q["meeting"] is not None, correct_kw=ok,
                     judge=judge.get(r["id"]), retrieved=retrieved, n_cites=len(cites), cites_valid=valid, cites_right=right,
                     total_sec=r["total_sec"], first_token_sec=r["first_token_sec"], phase=r["phase"],
                     excerpt_meetings=[ent2m.get(x["entry"]) for x in r["excerpts"]]))

def agg(sel):
    if not sel: return {}
    n = len(sel)
    ans = [x for x in sel if x["answerable"]]
    c = sum(x["n_cites"] for x in sel)
    lat = sorted(x["total_sec"] for x in sel); ft = sorted(x["first_token_sec"] for x in sel if x["first_token_sec"] >= 0)
    out = dict(n=n, correct_kw=sum(x["correct_kw"] for x in sel) / n,
               retrieved=(sum(bool(x["retrieved"]) for x in ans) / len(ans)) if ans else None,
               answers_with_cite=sum(1 for x in sel if x["n_cites"]) / n,
               cites=c, cite_valid=(sum(x["cites_valid"] for x in sel) / c) if c else None,
               cite_right=(sum(x["cites_right"] for x in ans) / max(1, sum(x["n_cites"] for x in ans))),
               latency_p50=lat[n // 2], latency_p90=lat[int(0.9 * (n - 1))],
               first_token_p50=ft[len(ft) // 2] if ft else None)
    if any(x["judge"] for x in sel):
        out["judge_correct"] = sum(x["judge"] == "correct" for x in sel) / n
        out["judge_partial"] = sum(x["judge"] == "partial" for x in sel) / n
    return out

summary = dict(all=agg(rows), zh=agg([x for x in rows if x["lang"] == "zh"]), en=agg([x for x in rows if x["lang"] == "en"]),
               unanswerable=agg([x for x in rows if not x["answerable"]]))
for k, v in summary.items(): print(k, json.dumps(v), file=sys.stderr)
json.dump(dict(summary=summary, rows=rows), sys.stdout, indent=1, ensure_ascii=False)
