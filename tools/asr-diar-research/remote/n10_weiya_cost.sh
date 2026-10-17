#!/bin/bash
# Note10+ (via training-machine, under flock /tmp/n10.lock): diarizer cost of base / t05p20 / c264 on the 尾牙 demo (460 s).
# /data/local/tmp/luigi_t05 only; no app is touched.
set -u
A="adb -s RF8M834KRGA"; S=/tmp/luigi_t05stage; D=/data/local/tmp/luigi_t05
$A shell svc power stayon true </dev/null
$A shell mkdir -p $D </dev/null
for f in diar_only_a64_f190 diar_only_a64_f380 libLiteRt.so ship_f190.tflite sf_int8_f380.tflite diar_host.bin pre_encode_512x1024.f32 weiya.f32; do
  $A push $S/$f $D/ </dev/null >/dev/null || echo "push fail $f"
done
$A shell "chmod 755 $D/diar_only_a64_*" </dev/null
temp() { $A shell 'dumpsys battery | grep -a temperature' </dev/null | tr -d '\r'; }
G="128 40 50 12 60"; G264="264 40 50 12 60"
for rep in 1 2; do
for v in "base f190 ship_f190.tflite $G 0.3 45 0" "t05p20 f190 ship_f190.tflite $G 0.5 20 0" "c264 f380 sf_int8_f380.tflite $G264 0.5 20 0"; do
  set -- $v; tag=$1; ff=$2; tf=$3; shift 3
  sleep 90; echo "== rep$rep $tag start $(temp)"
  $A shell "cd $D && LD_LIBRARY_PATH=$D taskset f0 ./diar_only_a64_$ff diar_host.bin pre_encode_512x1024.f32 $tf weiya.f32 1.0 out_${tag}_$rep 2 $* 2>&1 | tail -1" </dev/null
  echo "end $(temp)"
done
done
mkdir -p $S/out; $A pull $D/out_base_1.turns.json $S/out/ </dev/null >/dev/null; $A pull $D/out_t05p20_1.turns.json $S/out/ </dev/null >/dev/null; $A pull $D/out_c264_1.turns.json $S/out/ </dev/null >/dev/null
$A shell "rm -f $D/*.f32 $D/*.tflite $D/*.tflite.* $D/*.bin $D/*.so $D/diar_only_a64_* $D/out_*; rmdir $D" </dev/null
$A shell svc power stayon false </dev/null
echo N10DONE
