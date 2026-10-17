#!/bin/bash
# exported-graph variants; the first meeting runs alone so the XNNPACK weight cache is built by one process
cd ~/asr-research/diar; X=/dev/shm/luigi_asr/exp; G="128 40 50 12 60"; G264="264 40 50 12 60"
until [ -f out/diarq2.done ]; do sleep 20; done
run() { local out=$1 geo=$2; shift 2; env MEET=IS1009c "$@" ./run_diar.sh $out "$geo" 1 2; env "$@" ./run_diar.sh $out "$geo" 10 2; }
run out/exp_int8_f190 "$G" TFL=$X/sf_int8_f190.tflite
run out/exp_f32_f190 "$G" TFL=$X/sf_f32_f190.tflite
run out/exp_int8_c264 "$G264" BIN=bin/diar_only_f380 TFL=$X/sf_int8_f380.tflite
run out/exp_f32_c264 "$G264" BIN=bin/diar_only_f380 TFL=$X/sf_f32_f380.tflite
echo DIARQ3 DONE > out/diarq3.done
