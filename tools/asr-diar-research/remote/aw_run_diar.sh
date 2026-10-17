#!/bin/bash
# ai-workstation copy of tools/asr-diar-research/run_diar.sh: run_diar.sh OUTDIR "geo args" [P] [threads]
set -u
OUT=$1; GEO=${2:-}; P=${3:-5}; T=${4:-2}
N=~/asr-research/diar/nemo; A=/dev/shm/luigi_asr/f32; BIN=${BIN:-~/asr-research/diar/bin/diar_only}; TFL=${TFL:-$N/sortformer_int8_f190.tflite}
export LD_LIBRARY_PATH=$N
mkdir -p $OUT
for m in ${MEET:-L_R003S02C02 L_R004S06C01 M_R003S02C01 M_R003S05C01 S_R003S03C01 S_R004S01C01 ES2004b ES2004d IS1009c TS3003c}; do
  [ -f $OUT/$m.probs.f32 ] && continue
  g=$(python3 -c "import json;print(json.load(open(\"$HOME/asr-research/app_out/$m.asr.json\"))[\"gain\"]*${GAINF:-1})")
  echo "$BIN $N/diar_host.bin $N/pre_encode_512x1024.f32 $TFL $A/$m.f32 $g $OUT/$m $T $GEO > $OUT/$m.stats 2>&1"
done | xargs -P $P -I{} sh -c "{}"
echo done
