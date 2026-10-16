#!/usr/bin/env bash
# Diarizer turns for DER (nemo_eval_noggml <name>.turns.json): the same engine and geometry as the app, fed the
# audio with the constant gain GainNormalizer applied in the harness run (<name>.asr.json "gain").
#   run_turns.sh <audio f32 dir> <harness out dir> <turns out dir> <nemo models dir> NAME...
set -euo pipefail
A=$1 H=$2 T=$3 M=$4; shift 4
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
for n in "$@"; do
  g=$(/usr/bin/python3 -E -P -c "import json,sys;print(json.load(open(sys.argv[1]))['gain'])" "$H/$n.asr.json")
  # one constant gain, hard-clipped like GainNormalizer (the s16 conversion clips)
  ffmpeg -v error -y -f f32le -ar 16000 -ac 1 -i "$A/$n.f32" -af "volume=$g" -c:a pcm_s16le "$T/$n.wav"
  LC_ALL=C "$ROOT/tools/nemo-eval/build/nemo_eval_noggml" "$M/xasr_host.bin" "$M/diar_host.bin" "$T/$n.wav" "$T/$n.json" 4 --max-seg 10 \
    --backend litert_host --xasr-tflite "$M/xasr_stream_q8_octav.tflite" --diar-tflite "$M/sortformer_int8_f190.tflite" \
    --diar-pre "$M/pre_encode_512x1024.f32" 2>&1 | grep -a "^\[stats\]" > "$T/$n.stats" || true
  rm -f "$T/$n.wav"
done
