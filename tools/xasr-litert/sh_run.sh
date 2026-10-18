#!/bin/bash
# sherpa-onnx pseudo_stream.py baseline for any model dir: sh_run.sh TAG DIR "args" [P]
set -u; TAG=$1; D=$2; ARGS=$3; P=${4:-3}; R=/home/luigi/VoxSumDroid/.claude/worktrees/xoff-tools/tools/asr-diar-research
MEET="L_R003S02C02 L_R004S06C01 M_R003S02C01 M_R003S05C01 S_R003S03C01 S_R004S01C01 ES2004b ES2004d IS1009c TS3003c"
mkdir -p ps/raw/$TAG ps/logs ps/scores
printf "%s\n" $MEET | xargs -P $P -I{} sh -c "venv/bin/python -I $R/pseudo_stream.py --dir $D --audio-dir wav16 --app-dir meet/norm/app --out ps/raw/$TAG $ARGS {} >> ps/logs/$TAG.log 2>&1"
bash $R/ps_score.sh $TAG
