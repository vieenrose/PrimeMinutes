#!/bin/bash
# ps_score.sh for another raw root: ps_score_dir.sh ROOT TAG...  (ROOT/raw/TAG -> ROOT/norm/TAG -> ROOT/scores/TAG.json)
T=$(cd "$(dirname "$0")" && pwd); Q=$T/../quality-eval; R=$1; shift
mkdir -p $R/norm $R/scores
for TAG in "$@"; do
  [ "$(ls $R/raw/$TAG 2>/dev/null | wc -l)" -lt 10 ] && { echo "$TAG incomplete"; continue; }
  [ -s $R/scores/$TAG.json ] || {
    rm -rf $R/norm/$TAG; venv/bin/python -I $T/norm_asr.py $R/raw/$TAG $R/norm/$TAG
    venv/bin/python -I $Q/score_asr.py ~/voxsum-models/eval-corpora $R/norm/$TAG > $R/scores/$TAG.json 2>/dev/null; }
  python3 -I $T/sum_scores.py $R/scores/$TAG.json
done
