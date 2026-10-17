#!/bin/bash
cd ~/asr-research/diar; X=/dev/shm/luigi_asr/exp; G="128 40 50 12 60"; G264="264 40 50 12 60"
./run_diar.sh out/aw_base_t2 "$G" 10 2
./run_diar.sh out/aw_base_t4 "$G" 6 4
TFL=$X/sf_int8_f190.tflite ./run_diar.sh out/exp_int8_f190 "$G" 10 2
TFL=$X/sf_f32_f190.tflite ./run_diar.sh out/exp_f32_f190 "$G" 10 2
BIN=bin/diar_only_f380 TFL=$X/sf_int8_f380.tflite ./run_diar.sh out/exp_int8_c264 "$G264" 10 2
BIN=bin/diar_only_f380 TFL=$X/sf_f32_f380.tflite ./run_diar.sh out/exp_f32_c264 "$G264" 10 2
echo DIARQ DONE > out/diarq.done
