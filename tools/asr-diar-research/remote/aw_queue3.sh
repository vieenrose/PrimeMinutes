#!/bin/bash
# heavy / late candidates: Qwen3-ASR-0.6B, Fun-ASR-Nano encoder (SenseVoice-format CTC), FireRedASR2-AED
cd ~/asr-research; M=models; V="--vad $M/silero_vad.onnx"
Q=$M/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
QK='{"conv_frontend":"conv_frontend.onnx","encoder":"encoder.int8.onnx","decoder":"decoder.int8.onnx","tokenizer":"tokenizer","max_new_tokens":256,"max_total_len":1024}'
until [ -d $M/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26 ]; do sleep 30; done
{
for s in zhtwbench parl ascend; do
 echo "--kind sense_voice --dir $M/sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17 --tag fnano --manifest zhtw/$s.jsonl $V"
 echo "--kind qwen3_asr --dir $Q --kwargs @qwen3_kw.json --tag qwen3 --manifest zhtw/$s.jsonl $V"
 echo "--kind fire_red_asr --dir $M/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26 --tag fraed --manifest zhtw/$s.jsonl $V"
done
} | xargs -P 6 -I{} sh -c "venv/bin/python -I run_clips.py {} --out clipout >> logs/queue3.log 2>&1"
until [ -f logs/queue1.done ]; do sleep 30; done
./bench_asr.sh fnano_app "--kind sense_voice --dir $M/sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17 --seg app" 8
./bench_asr.sh fraed_app "--kind fire_red_asr --dir $M/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26 --seg app" 10
./bench_asr.sh qwen3_app "--kind qwen3_asr --dir $Q --kwargs @qwen3_kw.json --seg app" 10
echo QUEUE3 DONE >> logs/queue3.log
