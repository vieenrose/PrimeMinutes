#!/bin/bash
# xe_run.sh TAG "xoff_eval args" [P]   (from ~/voxsum-models/asr-research) -> ps/raw/TAG + score line
set -u; TAG=$1; ARGS=$2; P=${3:-4}
X=/home/luigi/voxsum-models/xoff-import/tools; R=/home/luigi/VoxSumDroid/.claude/worktrees/xoff-tools
MEET="L_R003S02C02 L_R004S06C01 M_R003S02C01 M_R003S05C01 S_R003S03C01 S_R004S01C01 ES2004b ES2004d IS1009c TS3003c"
mkdir -p ps/raw/$TAG ps/logs ps/scores
printf "%s\n" $MEET | xargs -P $P -I{} sh -c "/home/luigi/voxsum-models/litert-export-venv/bin/python -I $X/xoff_eval.py --audio-dir wav16 --app-dir meet/norm/app --out ps/raw/$TAG $ARGS {} >> ps/logs/$TAG.log 2>&1"
bash $R/tools/asr-diar-research/ps_score.sh $TAG
