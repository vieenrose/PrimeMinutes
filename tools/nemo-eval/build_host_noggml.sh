#!/usr/bin/env bash
# Build nemo_eval against the engine the app ships (NEMO_NO_GGML: LiteRT graphs + C++ host parts, no ggml),
# with the same sources and defines as app/src/main/cpp/CMakeLists.txt.
#   LITERT_SO=<linux x86_64 libLiteRt.so, 2.2.0 like the app's AAR> tools/nemo-eval/build_host_noggml.sh
#   -> tools/nemo-eval/build/nemo_eval_noggml
# Run: nemo_eval_noggml xasr_host.bin diar_host.bin in.wav out.json 4 --max-seg 10 --backend litert_host \
#        --xasr-tflite xasr_stream_q8_octav.tflite --diar-tflite sortformer_int8_f190.tflite --diar-pre pre_encode_512x1024.f32
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
E=$ROOT/app/src/main/cpp/nemo; B=$ROOT/tools/nemo-eval/build
: "${LITERT_SO:?set LITERT_SO to a linux x86_64 libLiteRt.so}"
mkdir -p "$B"
g++ ${NEMO_CXXFLAGS:--O3 -march=native} -std=c++17 -w -DNEMO_HAVE_TOKEN_TIMES -DNEMO_WITH_LITERT -DNEMO_LITERT_ENV_API -DNEMO_NO_GGML \
  -I"$E" -I"$E/hostxasr" -I"$ROOT/app/src/main/cpp/mfa/third_party" \
  "$E"/engine.cpp "$E"/fusion.cpp "$E"/litert_graph.cc "$E"/diar_litert.cc "$E"/diar_pre_encode.cc \
  "$E"/xasr_litert.cc "$E"/xasr_host.cc "$E"/kaldi_fbank.cpp "$E"/diar_host.cc \
  "$ROOT/tools/nemo-eval/nemo_eval.cpp" -o "$B/nemo_eval_noggml" \
  "$LITERT_SO" -Wl,-rpath,"$(dirname "$LITERT_SO")" -lpthread -ldl -lm
echo "built $B/nemo_eval_noggml"
