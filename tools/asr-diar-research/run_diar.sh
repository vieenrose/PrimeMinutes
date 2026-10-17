#!/bin/bash
# diar_only over the 10 eval meetings: run_diar.sh OUTDIR "geometry/decode args" [P] — gain from the app's asr.json
set -u
OUT=$1; GEO=${2:-}; P=${3:-2}
N=~/voxsum-models/eval-corpora/nemo-models; W=~/voxsum-models/eval-corpora/work
BIN=$(dirname "$0")/build/diar_only
mkdir -p $OUT
for m in L_R003S02C02 L_R004S06C01 M_R003S02C01 M_R003S05C01 S_R003S03C01 S_R004S01C01 ES2004b ES2004d IS1009c TS3003c; do
  [ -f $OUT/$m.probs.f32 ] && continue
  g=$(python3 -c "import json;print(json.load(open('$W/out/$m.asr.json'))['gain'])")
  echo "$BIN $N/diar_host.bin $N/pre_encode_512x1024.f32 $N/sortformer_int8_f190.tflite $W/audio/$m.f32 $g $OUT/$m 4 $GEO > $OUT/$m.stats 2>&1"
done | xargs -P $P -I{} sh -c "{}"
