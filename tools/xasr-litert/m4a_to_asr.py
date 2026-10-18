#!/usr/bin/env python3
"""Prime Minutes session.m4a -> <name>.asr.json (utterances) for score_asr.py: the meeting JSON embedded in the file.
  m4a_to_asr.py IN.m4a OUT.asr.json NAME AUDIO_SEC"""
import json, sys
import base64, gzip, re
b = open(sys.argv[1], "rb").read()
i = b.find(b"PRIMEMINUTES\x00"); j = b.find(b"data", i) + 12
m = re.match(rb"[A-Za-z0-9+/=]+", b[j:])
d = json.loads(gzip.decompress(base64.b64decode(m.group(0))))
json.dump(dict(name=sys.argv[3], audio_sec=float(sys.argv[4]), gain=1.0, skipped_sec=0, asr_model=d.get("asr_model"),
               utterances=[dict(index=k, text=u["text"], start=u["start"], end=u["end"], speaker=u.get("speaker")) for k, u in enumerate(d["utterances"])]),
          open(sys.argv[2], "w"), ensure_ascii=False, indent=0)
print(sys.argv[3], len(d["utterances"]), d.get("asr_model"))
