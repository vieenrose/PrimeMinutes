#!/usr/bin/env bash
# Host libprimeminutes-nemo.so variants from the diar-t05 worktree (origin/android), patched copies of app/src/main/cpp/nemo.
set -euo pipefail
ROOT=/home/luigi/VoxSumDroid/.claude/worktrees/diar-t05; W=/tmp/luigi_t05
LITERT_SO=/home/luigi/voxsum-models/eval-corpora/litert-whl/lib/libLiteRt.so
JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
M=$ROOT/app/src/main/cpp/mfa
for v in "$@"; do
  S=$W/src_$v; rm -rf $S; cp -r $ROOT/app/src/main/cpp/nemo $S; L=$W/lib_$v; mkdir -p $L
  case $v in
    base) ;;
    t05p20d) sed -i 's/float threshold = 0.3f;/float threshold = 0.5f;/; s/int pad_frames = 45;/int pad_frames = 20;/' $S/diar_host.h ;;
    t05p20) sed -i 's/float threshold = 0.3f;/float threshold = 0.5f;/; s/int pad_frames = 45;/int pad_frames = 20;/' $S/diar_host.h
            sed -i 's/{"speaker_threshold", "0.3"}, {"speaker_pad_frames", "45"}/{"speaker_threshold", "0.5"}, {"speaker_pad_frames", "20"}/' $S/engine.h ;;
    c264) sed -i 's/float threshold = 0.3f;/float threshold = 0.5f;/; s/int pad_frames = 45;/int pad_frames = 20;/; s/int spkcache_len = 128;/int spkcache_len = 264;/' $S/diar_host.h
          sed -i 's/{"speaker_threshold", "0.3"}, {"speaker_pad_frames", "45"}/{"speaker_threshold", "0.5"}, {"speaker_pad_frames", "20"}/' $S/engine.h
          sed -i 's/kMaxFrames = 190;/kMaxFrames = 380;/' $S/diar_litert.h ;;
    c264b) sed -i 's/int spkcache_len = 128;/int spkcache_len = 264;/' $S/diar_host.h; sed -i 's/kMaxFrames = 190;/kMaxFrames = 380;/' $S/diar_litert.h ;;
  esac
  diff -r $ROOT/app/src/main/cpp/nemo $S | grep -a '^[<>]' || true
  E=$S
  g++ -O3 -march=native -std=c++17 -w -shared -fPIC -DNEMO_HAVE_TOKEN_TIMES -DNEMO_WITH_LITERT -DNEMO_LITERT_ENV_API -DNEMO_NO_GGML \
    -I"$ROOT/tools/quality-eval/host-shim" -I"$E" -I"$E/hostxasr" -I"$M/third_party" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$E"/nemo_jni.cpp "$E"/engine.cpp "$E"/fusion.cpp "$E"/litert_graph.cc "$E"/diar_litert.cc "$E"/diar_pre_encode.cc \
    "$E"/xasr_litert.cc "$E"/xasr_host.cc "$E"/kaldi_fbank.cpp "$E"/diar_host.cc \
    -o "$L/libprimeminutes-nemo.so" "$LITERT_SO" -Wl,-rpath,"$(dirname "$LITERT_SO")" -lpthread -ldl -lm
  cp /home/luigi/VoxSumDroid/.claude/worktrees/asr-research/tools/quality-eval/build/libprimeminutes-mfa.so $L/
  echo "built $v"
done
