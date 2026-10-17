#!/bin/bash
# training-machine: stage sherpa-onnx termux static binaries + candidate models for a Note10+ RTF probe
set -u
D=/tmp/luigi_asrprobe; mkdir -p $D; cd $D
R=https://github.com/k2-fsa/sherpa-onnx/releases/download
[ -d bin ] || { curl -sL $R/v1.13.8/sherpa-onnx-v1.13.8-android-aarch64-termux-static.tar.bz2 | tar xj && mv sherpa-onnx-v1.13.8-android-aarch64-termux-static bin0 && echo bin; ls -R bin0 | head -30; }
B=$R/asr-models
for n in sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05 sherpa-onnx-x-asr-1920ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05 sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03 sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25 sherpa-onnx-paraformer-zh-int8-2025-10-07 sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17 sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25; do
  [ -d $n ] || { curl -sL $B/$n.tar.bz2 | tar xj && echo "ok $n"; }
done
du -sh $D
