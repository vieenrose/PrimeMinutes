#!/bin/bash
# Note10+ session 2 (under flock /tmp/n10.lock): latency on the "merge" segmentation with blank penalty 1.0, and
# mode B (streaming + diarizer + offline) paced while the E2B reader runs its long-window test in the test app
# (MfaEngineDeviceTest#longWindowSpeed, read-only use of the test app's model; nothing is installed or deleted).
#   flock /tmp/n10.lock bash n10_pseudo2.sh      (n10_pseudo.sh staged /data/local/tmp/luigi_ps first)
set -u
A="adb -s RF8M834KRGA"; S=/tmp/luigi_n10ps; D=/data/local/tmp/luigi_ps; P=studio.primeminutes.androidtest
$A shell svc power stayon true </dev/null
for f in L_R004S06C01 ES2004d weiya; do $A push $S/merge/$f.segs $D/segs/merge_$f.segs </dev/null >/dev/null || echo "push fail $f"; done
$A shell "ls $D; pm list instrumentation | grep -a primeminutes" </dev/null
temp() { $A shell 'dumpsys battery | grep -a temperature' </dev/null | tr -d '\r'; }
ENG="--xasr-host xasr_host.bin --xasr-tflite xasr_stream_q8_octav.tflite --diar-host diar_host.bin --diar-tflite sortformer_int8_f190.tflite --diar-pre pre_encode_512x1024.f32 --ethreads 2"
XO="--xoff xoff --xthreads 2 --blank-penalty 1.0"
run() { local tag=$1; shift; sleep 90; echo "== $tag start $(temp) $(date +%T)"
  $A shell "cd $D && LD_LIBRARY_PATH=$D taskset f0 ./pseudo_bench_a64 --out out/$tag.json $* 2>&1 | grep -a -v -E '^INFO|^WARNING' | tail -2" </dev/null
  echo "end $tag $(temp) $(date +%T)"; }
reader() { $A logcat -c </dev/null; echo "reader $1 start $(date +%T)"
  $A shell am instrument -w -e class studio.primeminutes.MfaEngineDeviceTest#longWindowSpeed $P.test/androidx.test.runner.AndroidJUnitRunner </dev/null | tail -2
  $A logcat -d -s MfaEngineDeviceTest </dev/null | grep -a LONG | head -1; echo "reader $1 end $(date +%T)"; }
run lat_zh_m --audio L_R004S06C01_1200.f32 --segs segs/merge_L_R004S06C01.segs $XO --paced 0
run lat_en_m --audio ES2004d_1200.f32 --segs segs/merge_ES2004d.segs $XO --paced 0
run lat_wy_m --audio weiya.f32 --segs segs/merge_weiya.segs $XO --paced 0
sleep 90; echo "== reader alone $(temp)"; reader alone
sleep 90; echo "== B_zh_m_llm start $(temp) $(date +%T)"
$A shell "cd $D && LD_LIBRARY_PATH=$D taskset f0 ./pseudo_bench_a64 --out out/B_zh_m_llm.json --audio L_R004S06C01_1200.f32 --segs segs/merge_L_R004S06C01.segs $XO --avail-delay 1.0 --paced 1 --engine full $ENG > out/B_zh_m_llm.log 2>&1 &" </dev/null
sleep 240; reader during1; sleep 120; reader during2; sleep 120; reader during3
until $A shell "grep -q 'done audio' $D/out/B_zh_m_llm.log" </dev/null; do sleep 20; done
$A shell "tail -1 $D/out/B_zh_m_llm.log" </dev/null; echo "end B_zh_m_llm $(temp) $(date +%T)"
mkdir -p $S/out2; $A pull $D/out/. $S/out2/ </dev/null >/dev/null
$A shell svc power stayon false </dev/null
echo N10DONE2 $(date +%T)
