#!/bin/bash
# Note10+ session 3 (under flock /tmp/n10.lock): E2B reader (test app, MfaEngineDeviceTest#longWindowSpeed) alone and
# while mode B (streaming + diarizer + offline X-ASR, paced) runs in /data/local/tmp/luigi_ps; then cleanup.
set -u
A="adb -s RF8M834KRGA"; S=/tmp/luigi_n10ps; D=/data/local/tmp/luigi_ps; P=studio.primeminutes.androidtest
$A shell svc power stayon true </dev/null
$A shell "run-as $P ls -la files/mfa-e2b files 2>&1 | head -20" </dev/null
temp() { $A shell 'dumpsys battery | grep -a temperature' </dev/null | tr -d '\r'; }
reader() { $A logcat -c </dev/null; echo "reader $1 start $(date +%T) $(temp)"
  $A shell am instrument -w -r -e class studio.primeminutes.MfaEngineDeviceTest#longWindowSpeed $P.test/androidx.test.runner.AndroidJUnitRunner </dev/null | grep -a -E "INSTRUMENTATION_STATUS: (stream|stack)|OK|FAIL|Assum|skipp" | head -6
  $A logcat -d -s MfaEngineDeviceTest </dev/null | grep -a LONG | head -1; echo "reader $1 end $(date +%T)"; }
reader alone1
ENG="--xasr-host xasr_host.bin --xasr-tflite xasr_stream_q8_octav.tflite --diar-host diar_host.bin --diar-tflite sortformer_int8_f190.tflite --diar-pre pre_encode_512x1024.f32 --ethreads 2"
XO="--xoff xoff --xthreads 2 --blank-penalty 1.0"
sleep 90; echo "== B_zh_llm start $(temp) $(date +%T)"
$A shell "cd $D && (LD_LIBRARY_PATH=$D nohup taskset f0 ./pseudo_bench_a64 --out out/B_zh_llm.json --audio L_R004S06C01_1200.f32 --segs segs/merge_L_R004S06C01.segs $XO --avail-delay 1.0 --paced 1 --engine full $ENG --limit-sec 600 </dev/null >out/B_zh_llm.log 2>&1 &)" </dev/null
echo "launched $(date +%T)"; sleep 120; reader during1; sleep 60; reader during2; sleep 60; reader during3
until $A shell "grep -q 'done audio' $D/out/B_zh_llm.log" </dev/null; do sleep 20; done
$A shell "tail -1 $D/out/B_zh_llm.log" </dev/null; echo "end B_zh_llm $(temp) $(date +%T)"
mkdir -p $S/out3; $A pull $D/out/B_zh_llm.json $S/out3/ </dev/null >/dev/null
sleep 90; reader alone2
# cleanup of everything this study put on the device
$A shell "rm -f $D/out/* $D/segs/* $D/xoff/* $D/*.f32 $D/*.tflite $D/*.wcache $D/*.bin $D/*.so $D/pseudo_bench_a64; rmdir $D/out $D/segs $D/xoff $D; ls /data/local/tmp" </dev/null
$A shell svc power stayon false </dev/null
echo N10DONE3 $(date +%T)
