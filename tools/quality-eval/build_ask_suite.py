#!/usr/bin/env python3
"""Assemble the versioned Ask suite (app/src/androidTest/assets/ask_suite/ask_suite_v1.json) from the
per-meeting references (refs/<NAME>.json questions) plus a few questions no meeting answers.
    /usr/bin/python3 -E -P build_ask_suite.py <refs dir> <out json>"""
import json, os, sys
refs, out = sys.argv[1], sys.argv[2]
MEETINGS = ["L_R003S02C02", "L_R004S06C01", "M_R003S02C01", "M_R003S05C01", "S_R003S03C01", "S_R004S01C01",
            "ES2004b", "ES2004d", "IS1009c", "TS3003c"]
qs = []
for m in MEETINGS:
    r = json.load(open(os.path.join(refs, m + ".json")))
    for q in r["questions"]:
        qs.append(dict(id=q["id"], meeting=m, lang=q["lang"], ui="zh-TW" if q["lang"] == "zh" else "en", q=q["q"],
                       answer=q["answer"], span=q["span"], all=q["all"]))
NONE = [
    ("none-zh1", "zh", "在會議裡，公司明年的員工旅遊決定去哪裡？"),
    ("none-zh2", "zh", "會議中提到的新辦公室每個月租金是多少？"),
    ("none-en1", "en", "What budget was approved for the marketing team's trip to Berlin?"),
    ("none-en2", "en", "Which programming language did the team choose for the backend server?"),
]
for i, lang, q in NONE:
    qs.append(dict(id=i, meeting=None, lang=lang, ui="zh-TW" if lang == "zh" else "en", q=q, answer=None, span=None, all=[]))
suite = dict(version=1, created="2026-10-16",
             corpora="AISHELL-4 test (L_R003S02C02 L_R004S06C01 M_R003S02C01 M_R003S05C01 S_R003S03C01 S_R004S01C01, channel 0), "
                     "AMI Mix-Headset (ES2004b ES2004d IS1009c TS3003c)",
             method="questions, expected answers, source spans (seconds in the source audio) and keyword groups written by Claude "
                    "(Opus) from the gold transcripts (tools/quality-eval/SPEC.md); zh questions in Traditional Chinese, keywords "
                    "in Simplified (answers are converted to Simplified before matching); meeting=null: no meeting answers it, "
                    "the expected behaviour is to say so",
             questions=qs)
json.dump(suite, open(out, "w"), ensure_ascii=False, indent=1)
print(len(qs), "questions")
