#!/bin/bash
# libvoxsum-nemo.a for iOS: engine + fusion + diarizer glue + the C bridge (nemo_c). Needs build_ios.sh first.
#   build_nemo_lib.sh <iphonesimulator|iphoneos> <x86_64|arm64>
set -e
SDK=${1:-iphonesimulator}; ARCH=${2:-x86_64}
ROOT=$(cd "$(dirname "$0")/../.." && pwd); N=$ROOT/native; E=$ROOT/app/src/main/cpp/nemo; I=$ROOT/ios/native
B=$ROOT/build-ios/$SDK-$ARCH; mkdir -p $B/nemo-obj
MIN=$([ $SDK = iphonesimulator ] && echo -mios-simulator-version-min=17.0 || echo -miphoneos-version-min=17.0)
# LiteRT bench path (backend != ggml): the C API headers are vendored with the iOS reader (mfa/third_party).
LRT_INC=$ROOT/ios/native/mfa/third_party
# NOGGML=1: no ggml anywhere. X-ASR and Sortformer host parts (xasr_host/diar_host) replace CrispASR and audio.cpp.
if [ -n "$NOGGML" ]; then SRCS="$E/xasr_host.cc $E/kaldi_fbank.cpp $E/diar_host.cc"; INC="-I$E/hostxasr -DNEMO_NO_GGML"; rm -f $B/nemo-obj/*.o
else SRCS="$E/diar_crispasr.cpp"; INC="-I$N/crispasr/src -I$N/audiocpp/include -I$N/crispasr-ggml/include -I$N/crispasr-ggml/src -I$N/crispasr-ggml/src/ggml-cpu"; fi
for f in $E/engine.cpp $E/fusion.cpp $SRCS $E/litert_graph.cc $E/diar_litert.cc $E/diar_pre_encode.cc $E/xasr_litert.cc $I/nemo_c.cpp; do
  xcrun --sdk $SDK clang++ -c -O2 -std=c++17 -w -DNEMO_HAVE_TOKEN_TIMES -include $I/apple_sched_shim.h -arch $ARCH \
    -isysroot $(xcrun --sdk $SDK --show-sdk-path) $MIN -I$E -I$I $INC -I$LRT_INC -DNEMO_WITH_LITERT -DNEMO_LITERT_ENV_API $f -o $B/nemo-obj/$(basename $f .cpp).o
done
rm -f $B/libvoxsum-nemo.a; xcrun libtool -static -o $B/libvoxsum-nemo.a $B/nemo-obj/*.o
echo "built $B/libvoxsum-nemo.a"
