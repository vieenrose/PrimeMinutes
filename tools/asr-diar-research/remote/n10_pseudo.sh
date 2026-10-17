#!/bin/bash
# Note10+ (via training-machine, under flock /tmp/n10.lock): cost of X-ASR offline pseudo-streaming (pseudo_bench).
# /data/local/tmp/luigi_ps only. The app engine's models are COPIED (read-only, run-as) from the test app
# studio.primeminutes.androidtest files/models/nemo; nothing of the app is modified.
#   flock /tmp/n10.lock bash n10_pseudo.sh [RUNSET]
set -u
A="adb -s RF8M834KRGA"; S=/tmp/luigi_n10ps; X=/tmp/luigi_ps/models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03
D=/data/local/tmp/luigi_ps; RUNSET=${1:-all}
$A shell svc power stayon true </dev/null
$A shell mkdir -p $D/xoff $D/segs $D/out </dev/null
for f in pseudo_bench_a64 libLiteRt.so libonnxruntime.so libsherpa-onnx-c-api.so L_R004S06C01_1200.f32 ES2004d_1200.f32 weiya.f32; do
  $A push $S/$f $D/ </dev/null >/dev/null || echo "push fail $f"; done
for f in vad/L_R004S06C01.segs vad/ES2004d.segs vad/weiya.segs app/L_R004S06C01.segs app/ES2004d.segs; do
  $A push $S/$f $D/segs/$(dirname $f)_$(basename $f) </dev/null >/dev/null || echo "push fail $f"; done
for f in encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt; do
  $A push $X/$f $D/xoff/ </dev/null >/dev/null || echo "push fail $f"; done
for f in xasr_host.bin diar_host.bin xasr_stream_q8_octav.tflite sortformer_int8_f190.tflite pre_encode_512x1024.f32; do
  [ "$($A shell "ls $D/$f 2>/dev/null" </dev/null | tr -d '\r')" = "$D/$f" ] || \
    $A shell "run-as studio.primeminutes.androidtest cat files/models/nemo/$f > $D/$f" </dev/null
done
$A shell "chmod 755 $D/pseudo_bench_a64; ls -la $D $D/xoff; sha256sum $D/*.tflite | cut -c1-20" </dev/null
temp() { $A shell 'dumpsys battery | grep -a temperature' </dev/null | tr -d '\r'; }
ENG="--xasr-host xasr_host.bin --xasr-tflite xasr_stream_q8_octav.tflite --diar-host diar_host.bin --diar-tflite sortformer_int8_f190.tflite --diar-pre pre_encode_512x1024.f32 --ethreads 2"
run() {  # tag args...
  local tag=$1; shift
  sleep 90; echo "== $tag start $(temp) $(date +%T)"
  $A shell "cd $D && LD_LIBRARY_PATH=$D taskset f0 ./pseudo_bench_a64 --out out/$tag.json $* 2>&1 | grep -a -v -E '^INFO|^WARNING' | tail -2" </dev/null
  echo "end $tag $(temp) $(date +%T)"
}
ZH="--audio L_R004S06C01_1200.f32"; EN="--audio ES2004d_1200.f32"; WY="--audio weiya.f32"
XO="--xoff xoff --xthreads 2"
case $RUNSET in all|unpaced)
run warm  $WY --segs segs/vad_weiya.segs $XO --paced 0 --limit-sec 30 --engine full $ENG
run lat_zh $ZH --segs segs/vad_L_R004S06C01.segs $XO --paced 0
run lat_en $EN --segs segs/vad_ES2004d.segs $XO --paced 0
run lat_wy $WY --segs segs/vad_weiya.segs $XO --paced 0
run lat_zh_app $ZH --segs segs/app_L_R004S06C01.segs $XO --paced 0
run mem_full $ZH --segs segs/vad_L_R004S06C01.segs --paced 0 --engine full $ENG
run mem_asr  $ZH --segs segs/vad_L_R004S06C01.segs --paced 0 --engine asr $ENG
run mem_diar $ZH --segs segs/vad_L_R004S06C01.segs --paced 0 --engine diar $ENG
;; esac
case $RUNSET in all|paced)
run B_zh $ZH --segs segs/vad_L_R004S06C01.segs $XO --paced 1 --engine full $ENG
run A_zh $ZH --segs segs/vad_L_R004S06C01.segs $XO --paced 1 --engine diar $ENG
run B_en $EN --segs segs/vad_ES2004d.segs $XO --paced 1 --engine full $ENG --limit-sec 600
;; esac
mkdir -p $S/out; $A pull $D/out/. $S/out/ </dev/null >/dev/null
$A shell svc power stayon false </dev/null
echo N10DONE $(date +%T)
