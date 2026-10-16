#!/usr/bin/env bash
# Build the app's two native libraries for the host JVM, so the app's own Kotlin code (import
# pipeline stages, ASR engine wrapper, reader, Ask) runs on Linux (tools/quality-eval/README.md):
#   libprimeminutes-mfa.so   app/src/main/cpp/mfa: the forked LiteRT-LM CPU engine, the embedder, their JNI
#   libprimeminutes-nemo.so  app/src/main/cpp/nemo: the ggml-free ASR + diarization engine (NEMO_NO_GGML), JNI
#   LITERT_SO=<linux x86_64 libLiteRt.so 2.2.0, like the app's AAR> tools/quality-eval/build_host_jni.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${LITERT_SO:?set LITERT_SO to a linux x86_64 libLiteRt.so}"
B=$ROOT/tools/quality-eval/build; SP=$ROOT/native/sentencepiece; M=$ROOT/app/src/main/cpp/mfa
JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
mkdir -p "$B"
cmake -S "$SP" -B "$B/sentencepiece" -DCMAKE_BUILD_TYPE=Release -DSPM_ENABLE_SHARED=OFF -DSPM_ENABLE_TCMALLOC=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_CXX_FLAGS="-include cstdint" >/dev/null
cmake --build "$B/sentencepiece" --target sentencepiece-static -j"$(nproc)" >/dev/null
g++ -O3 -std=c++17 -include cstdint -shared -fPIC -fexceptions -fopenmp -march=native \
  -I"$ROOT/tools/quality-eval/host-shim" -I"$M" -I"$M/third_party" -I"$SP/src" \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  "$M"/mfa_jni.cpp "$M"/mfa_engine.cc "$M"/i8_attn.cc "$M"/embedder.cc "$M"/embedder_jni.cpp \
  -o "$B/libprimeminutes-mfa.so" "$B/sentencepiece/src/libsentencepiece.a" \
  "$LITERT_SO" -Wl,-rpath,"$(dirname "$LITERT_SO")" -lpthread -ldl -lm
E=$ROOT/app/src/main/cpp/nemo
g++ ${NEMO_CXXFLAGS:--O3 -march=native} -std=c++17 -w -shared -fPIC -DNEMO_HAVE_TOKEN_TIMES -DNEMO_WITH_LITERT -DNEMO_LITERT_ENV_API -DNEMO_NO_GGML \
  -I"$ROOT/tools/quality-eval/host-shim" -I"$E" -I"$E/hostxasr" -I"$M/third_party" \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  "$E"/nemo_jni.cpp "$E"/engine.cpp "$E"/fusion.cpp "$E"/litert_graph.cc "$E"/diar_litert.cc "$E"/diar_pre_encode.cc \
  "$E"/xasr_litert.cc "$E"/xasr_host.cc "$E"/kaldi_fbank.cpp "$E"/diar_host.cc \
  -o "$B/libprimeminutes-nemo.so" "$LITERT_SO" -Wl,-rpath,"$(dirname "$LITERT_SO")" -lpthread -ldl -lm
echo "built $B/libprimeminutes-mfa.so $B/libprimeminutes-nemo.so"
