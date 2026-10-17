#!/bin/bash
# Run one ASR candidate over the eval meetings, one process per meeting (1 thread each), P in parallel.
#   bench_asr.sh TAG "run_asr.py args" [P] [meetings...]
# Outputs out/TAG/<name>.asr.json; logs/TAG.log. Run from ~/asr-research (venv/, audio/, app_out/, models/).
set -u
TAG=$1; ARGS=$2; P=${3:-5}; shift 3 || shift $#
MEET=${*:-"L_R003S02C02 L_R004S06C01 M_R003S02C01 M_R003S05C01 S_R003S03C01 S_R004S01C01 ES2004b ES2004d IS1009c TS3003c"}
mkdir -p out/$TAG logs
printf "%s\n" $MEET | xargs -P $P -I{} sh -c "venv/bin/python -I run_asr.py $ARGS --audio-dir audio --app-dir app_out --out out/$TAG --threads 1 {} >> logs/$TAG.log 2>&1"
echo "done $TAG $(ls out/$TAG | wc -l)" >> logs/$TAG.log
