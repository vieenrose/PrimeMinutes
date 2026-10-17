#!/usr/bin/env python3
"""Copy a dir of <name>.asr.json with the Chinese meetings' text normalized for score_asr.py, so that models
writing traditional characters or Arabic numerals (ITN) are not charged for script/number format:
OpenCC t2s, then cn2an an2cn on digit runs (AISHELL-4 references spell numbers in characters).
English meetings are copied unchanged.

    venv/bin/python -I norm_asr.py IN_DIR OUT_DIR
"""
import json, os, re, sys
import cn2an, opencc

cc = opencc.OpenCC("t2s")


def norm(t):
    t = cc.convert(t)
    def num(m):
        try:
            return cn2an.an2cn(m.group(0))
        except Exception:
            return m.group(0)
    return re.sub(r"\d+(?:\.\d+)?", num, t)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    for f in sorted(os.listdir(src)):
        if not f.endswith(".asr.json"):
            continue
        d = json.load(open(os.path.join(src, f)))
        if f[:2] in ("L_", "M_", "S_"):
            for u in d["utterances"]:
                u["text"] = norm(u["text"])
        json.dump(d, open(os.path.join(dst, f), "w"), ensure_ascii=False)


if __name__ == "__main__":
    main()
