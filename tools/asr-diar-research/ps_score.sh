#!/bin/bash
# Score pseudo-streaming outputs: ps/raw/TAG -> ps/norm/TAG -> ps/scores/TAG.json, one summary line per TAG.
#   ps_score.sh TAG...      (run from ~/voxsum-models/asr-research; skips TAGs with fewer than 10 meetings)
T=$(cd "$(dirname "$0")" && pwd); Q=$T/../quality-eval
for TAG in "$@"; do
  [ "$(ls ps/raw/$TAG 2>/dev/null | wc -l)" -lt 10 ] && { echo "$TAG incomplete"; continue; }
  [ -s ps/scores/$TAG.json ] || {
    rm -rf ps/norm/$TAG; venv/bin/python -I $T/norm_asr.py ps/raw/$TAG ps/norm/$TAG
    venv/bin/python -I $Q/score_asr.py ~/voxsum-models/eval-corpora ps/norm/$TAG > ps/scores/$TAG.json 2>/dev/null; }
  python3 -I $T/sum_scores.py ps/scores/$TAG.json
done
