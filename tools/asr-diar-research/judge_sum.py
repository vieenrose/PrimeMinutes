#!/usr/bin/env python3
"""Unblind and aggregate judge JSONs: per condition, key-point score (covered 1, partial 0.5), decisions/actions
found, errors by type (major/all), speaker claims wrong, usefulness. Averaged over meetings x judges.

    python3 -I judge_sum.py JUDGE_ROOT [--per-meeting]
JUDGE_ROOT holds s<seed>/<meeting>.judge.json and keys_s<seed>/<meeting>.json.
"""
import glob, json, os, sys
from collections import defaultdict

root = sys.argv[1]; per = "--per-meeting" in sys.argv
acc = defaultdict(lambda: defaultdict(float)); n = defaultdict(int); rows = []
for jf in sorted(glob.glob(os.path.join(root, "s*", "*.judge.json"))):
    seed = os.path.basename(os.path.dirname(jf)); m = os.path.basename(jf)[:-11]
    key = json.load(open(os.path.join(root, "keys_" + seed, m + ".json")))
    j = json.load(open(jf)); K = len(j["key_points"]) or 1
    nd, na = len(j.get("decisions", [])), len(j.get("actions", []))
    for L, o in j["outputs"].items():
        c = key[L]; a = acc[c]; n[c] += 1
        kp = sum({"covered": 1, "partial": 0.5}.get(v, 0) for v in o["kp"].values()) / K
        a["kp"] += kp; a["da_found"] += len(o.get("decisions_found", [])) + len(o.get("actions_found", []))
        a["da_total"] += nd + na
        for e in o.get("errors", []):
            a["err_" + e["type"]] += 1
            if e.get("severity") == "major":
                a["major_" + e["type"]] += 1; a["major"] += 1
            a["err"] += 1
        a["spk_claims"] += o.get("speaker_claims", 0); a["spk_wrong"] += o.get("speaker_claims_wrong", 0)
        a["use"] += o.get("usefulness", 0)
        rows.append((m, seed, c, kp, len(o.get("errors", [])), o.get("usefulness", 0)))
types = ["asr_term", "speaker", "fabrication", "distortion"]
print("%-10s %3s %6s %7s %6s %6s %s %9s %5s" % ("cond", "n", "kp%", "D+A", "err", "major", " ".join("%10s" % t for t in types), "spk w/c", "use"))
for c in sorted(acc, key=lambda c: ["app_app", "gold_app", "app_gold", "gold_gold"].index(c) if c in ("app_app", "gold_app", "app_gold", "gold_gold") else 9):
    a = acc[c]; k = n[c]
    print("%-10s %3d %6.1f %3.0f/%-3.0f %6.2f %6.2f %s %4.0f/%-4.0f %5.2f" % (c, k, 100 * a["kp"] / k, a["da_found"], a["da_total"],
          a["err"] / k, a["major"] / k, " ".join("%4.2f(%4.2f)" % (a["err_" + t] / k, a["major_" + t] / k) for t in types),
          a["spk_wrong"], a["spk_claims"], a["use"] / k))
if per:
    for r in rows:
        print("%-14s %s %-10s kp %5.1f err %2d use %d" % (r[0], r[1], r[2], 100 * r[3], r[4], r[5]))
