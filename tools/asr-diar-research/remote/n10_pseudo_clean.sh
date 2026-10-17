#!/bin/bash
# Remove /data/local/tmp/luigi_ps from the Note10+ (under flock /tmp/n10.lock).
A="adb -s RF8M834KRGA"; D=/data/local/tmp/luigi_ps
$A shell "rm -f $D/out/*.json $D/segs/*.segs $D/xoff/* $D/*.f32 $D/*.tflite $D/*.tflite.wcache $D/*.bin $D/*.so $D/pseudo_bench_a64; rmdir $D/out $D/segs $D/xoff $D; ls /data/local/tmp" </dev/null
$A shell svc power stayon false </dev/null
