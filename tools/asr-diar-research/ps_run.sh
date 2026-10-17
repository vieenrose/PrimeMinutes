#!/bin/bash
# One pseudo-streaming config over the 10 corpus meetings (P processes, 1 thread each), then t2s/an2cn + score.
#   ps_run.sh TAG "pseudo_stream.py args" [P]       (run from ~/voxsum-models/asr-research)
# -> ps/raw/TAG, ps/norm/TAG, ps/scores/TAG.json; one summary line on stdout.
set -u
TAG=$1; ARGS=$2; P=${3:-5}
T=$(cd "$(dirname "$0")" && pwd); Q=$T/../quality-eval
MEET="L_R003S02C02 L_R004S06C01 M_R003S02C01 M_R003S05C01 S_R003S03C01 S_R004S01C01 ES2004b ES2004d IS1009c TS3003c"
XOFF=models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03
mkdir -p ps/raw/$TAG ps/norm ps/scores ps/logs
printf "%s\n" $MEET | xargs -P $P -I{} sh -c "venv/bin/python -I $T/pseudo_stream.py --dir $XOFF --audio-dir wav16 --app-dir meet/norm/app --out ps/raw/$TAG $ARGS {} >> ps/logs/$TAG.log 2>&1"
venv/bin/python -I $T/norm_asr.py ps/raw/$TAG ps/norm/$TAG
venv/bin/python -I $Q/score_asr.py ~/voxsum-models/eval-corpora ps/norm/$TAG > ps/scores/$TAG.json 2>>ps/logs/$TAG.log
python3 -I $T/sum_scores.py ps/scores/$TAG.json
