#!/usr/bin/env python3
"""pending_progress.json (Prime Minutes test app, transcript after the engine finished) -> <name>.asr.json.
  pp_to_asr.py IN.json OUT.asr.json NAME AUDIO_SEC"""
import json, sys
d = json.load(open(sys.argv[1]))
json.dump(dict(name=sys.argv[3], audio_sec=float(sys.argv[4]), gain=1.0, skipped_sec=0, fingerprint=d.get("fingerprint"),
               utterances=[dict(index=k, text=u["text"], start=u["start"], end=u["end"], speaker=u.get("speaker")) for k, u in enumerate(d["utterances"])]),
          open(sys.argv[2], "w"), ensure_ascii=False, indent=0)
print(sys.argv[3], len(d["utterances"]), d.get("fingerprint"))
