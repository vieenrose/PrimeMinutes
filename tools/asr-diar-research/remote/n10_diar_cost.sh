#!/bin/bash
# Note10+ (via training-machine, under flock /tmp/n10.lock): on-device cost of the diarizer variants and of a
# CAM++ re-assignment pass. /data/local/tmp/luigi_diar only; the apps are not touched.
#   flock -w 1800 /tmp/n10.lock bash n10_diar_cost.sh
set -u
A="adb -s RF8M834KRGA"; S=/tmp/luigi_diarstage; D=/data/local/tmp/luigi_diar
$A shell svc power stayon true </dev/null
$A shell mkdir -p $D </dev/null
for f in diar_only_a64_f190 diar_only_a64_f380 spk_bench_a64 libLiteRt.so sf_int8_f190.tflite sf_int8_f380.tflite diar_host.bin pre_encode_512x1024.f32 campplus_zh_en_400f_fp32.tflite luigi_ES2004d_600.f32 luigi_L_600.f32; do
  $A push $S/$f $D/ </dev/null >/dev/null || echo "push fail $f"
done
$A shell "chmod 755 $D/*_a64*" </dev/null
temp() { $A shell 'dumpsys battery | grep -a temperature' </dev/null | tr -d '\r'; }
cool() { sleep 90; echo "start $(temp)"; }  # fixed rest between runs; the battery temperature is logged
G="128 40 50 12 60"; G264="264 40 50 12 60"
for m in luigi_ES2004d_600 luigi_L_600; do
  for v in "base f190 $G 0.3 45 0" "t05p20 f190 $G 0.5 20 0" "c264 f380 $G264 0.5 20 0"; do
    set -- $v; tag=$1; ff=$2; shift 2
    cool
    echo "== $m $tag"
    $A shell "cd $D && LD_LIBRARY_PATH=$D taskset f0 ./diar_only_a64_$ff diar_host.bin pre_encode_512x1024.f32 sf_int8_$ff.tflite $m.f32 1.0 out_${m}_$tag 2 $* 2>&1 | tail -1" </dev/null
    temp
  done
  for th in 1 2; do cool; echo "== $m cam++ threads $th"; $A shell "cd $D && LD_LIBRARY_PATH=$D taskset f0 ./spk_bench_a64 campplus_zh_en_400f_fp32.tflite $m.f32 $th 2>&1 | tail -1" </dev/null; temp; done
done
$A shell "rm -f $D/*.f32 $D/*.tflite $D/*.bin $D/*.so $D/*_a64* $D/out_*; rmdir $D" </dev/null
$A shell svc power stayon false </dev/null
echo N10DONE
