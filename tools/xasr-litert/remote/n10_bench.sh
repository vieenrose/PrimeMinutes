#!/bin/bash
# Note10+: LiteRT CPU timing of the offline (T=2000) / streaming 1920 / streaming 480 X-ASR graphs, /data/local/tmp only.
A="adb -s RF8M834KRGA"; R=/data/local/tmp/luigi_xbench; S=/tmp/luigi_xasr
$A shell svc power stayon true </dev/null
$A shell mkdir -p $R </dev/null
$A push $S/xoff_bench $S/libLiteRt.so $S/xoff.tflite $S/s480.tflite $R/ </dev/null >/dev/null
$A shell cp /data/local/tmp/luigi_xasr_keep/xasr_stream.tflite $R/s1920.tflite </dev/null 2>/dev/null || $A push $S/xasr_stream.tflite $R/s1920.tflite </dev/null >/dev/null
for m in xoff s1920 s480; do for t in 2 4; do sleep 90; echo "== $m threads $t $($A shell 'dumpsys battery | grep -a temperature' </dev/null | tr -d '\r')"
  n=20; [ $m = s480 ] && n=100; [ $m = s1920 ] && n=50
  $A shell "cd $R && LD_LIBRARY_PATH=. taskset f0 ./xoff_bench $m.tflite $t $n" </dev/null; done; done
echo "== xoff len 500 (5 s in the T=2000 graph)"; $A shell "cd $R && LD_LIBRARY_PATH=. taskset f0 ./xoff_bench xoff.tflite 2 10 500" </dev/null
$A shell rm -rf $R </dev/null
$A shell svc power stayon false </dev/null
echo BENCHDONE
