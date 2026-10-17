#!/usr/bin/env bash
# Build diar_only against the app's LiteRT diarizer sources (same flags as tools/nemo-eval/build_host_noggml.sh).
#   LITERT_SO=<linux x86_64 libLiteRt.so 2.2.0> [MAXF=380] tools/asr-diar-research/build_diar_only.sh
# MAXF != 190 builds build/diar_only_f$MAXF from a copy of diar_litert.{h,cc} with kMaxFrames = MAXF, to run a
# Sortformer graph exported at a larger window (export_sortformer.py --frames MAXF), e.g. the card's spkcache 264.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); E=$ROOT/app/src/main/cpp/nemo; B=$ROOT/tools/asr-diar-research/build
: "${LITERT_SO:?set LITERT_SO}"; MAXF=${MAXF:-190}; mkdir -p "$B"
INC=$E; OUT=$B/diar_only; LIT=$E/diar_litert.cc
if [ "$MAXF" != 190 ]; then
  P=$B/patch_f$MAXF; rm -rf "$P"; mkdir -p "$P"; cp "$E/diar_litert.h" "$P"/
  sed -i "s/kMaxFrames = 190;/kMaxFrames = $MAXF;/" "$P/diar_litert.h"; grep -q "kMaxFrames = $MAXF;" "$P/diar_litert.h"
  cp "$E/diar_litert.cc" "$P/"; LIT=$P/diar_litert.cc; INC=$P; OUT=$B/diar_only_f$MAXF
fi
g++ -O3 -march=native -std=c++17 -w -DNEMO_WITH_LITERT -DNEMO_LITERT_ENV_API -DNEMO_NO_GGML -I"$INC" -I"$E" -I"$ROOT/app/src/main/cpp/mfa/third_party" \
  "$ROOT/tools/asr-diar-research/diar_only.cpp" "$E"/litert_graph.cc "$LIT" "$E"/diar_pre_encode.cc \
  -o "$OUT" "$LITERT_SO" -Wl,-rpath,"$(dirname "$LITERT_SO")" -lpthread -ldl -lm
echo "built $OUT"
