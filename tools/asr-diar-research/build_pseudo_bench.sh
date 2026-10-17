#!/usr/bin/env bash
# Build pseudo_bench for arm64 Android: the app's ggml-free nemo engine sources (same defines and -O2 as
# app/src/main/cpp/CMakeLists.txt) + the sherpa-onnx C API (v1.13.8 android jniLibs, libonnxruntime.so).
#   NEMO_SRC=<checkout>/app/src/main/cpp LITERT_SO=<android arm64 libLiteRt.so> SHERPA=<dir with jniLibs/ include/> \
#     tools/asr-diar-research/build_pseudo_bench.sh   -> tools/asr-diar-research/build/pseudo_bench_a64
set -euo pipefail
H=$(cd "$(dirname "$0")" && pwd); C=${NEMO_SRC:?}; E=$C/nemo
NDK=${ANDROID_NDK:-$HOME/Android/Sdk/ndk/29.0.14206865}; TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
: "${LITERT_SO:?}"; : "${SHERPA:?}"; L=$SHERPA/jniLibs/arm64-v8a
mkdir -p "$H/build"
"$TC/bin/clang++" --target=aarch64-linux-android29 -O2 -std=c++17 -w -DNEMO_HAVE_TOKEN_TIMES -DNEMO_WITH_LITERT -DNEMO_LITERT_ENV_API -DNEMO_NO_GGML \
  -I"$E" -I"$E/hostxasr" -I"$C/mfa/third_party" -I"$SHERPA/include" \
  "$H/pseudo_bench.cpp" "$E"/engine.cpp "$E"/fusion.cpp "$E"/litert_graph.cc "$E"/diar_litert.cc "$E"/diar_pre_encode.cc \
  "$E"/xasr_litert.cc "$E"/xasr_host.cc "$E"/kaldi_fbank.cpp "$E"/diar_host.cc \
  -o "$H/build/pseudo_bench_a64" "$LITERT_SO" "$L/libsherpa-onnx-c-api.so" "$L/libonnxruntime.so" \
  -static-libstdc++ -Wl,-rpath,'$ORIGIN' -llog -ldl -lm -landroid
echo "built $H/build/pseudo_bench_a64"
