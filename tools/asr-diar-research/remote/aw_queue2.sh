#!/bin/bash
# clip sets x candidates, 1 thread per process, up to 8 processes
cd ~/asr-research; M=models; mkdir -p clipout logs
V="--vad $M/silero_vad.onnx"
jobs_list() {
for s in zhtwbench parl ascend; do
 echo "--kind online_transducer --dir $M/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05 --tag x480 --manifest zhtw/$s.jsonl"
 echo "--kind online_transducer --dir $M/sherpa-onnx-x-asr-1920ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05 --tag x1920 --manifest zhtw/$s.jsonl"
 echo "--kind transducer --dir $M/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03 --tag xoff --manifest zhtw/$s.jsonl $V"
 echo "--kind sense_voice --dir $M/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 --tag sv --manifest zhtw/$s.jsonl $V"
 echo "--kind paraformer --dir $M/sherpa-onnx-paraformer-zh-int8-2025-10-07 --tag para --manifest zhtw/$s.jsonl $V"
 echo "--kind fire_red_asr_ctc --dir $M/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25 --tag frctc --manifest zhtw/$s.jsonl $V"
 echo "--kind dolphin_ctc --dir $M/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02 --tag dolphin --manifest zhtw/$s.jsonl $V"
 echo "--kind online_transducer --dir $M/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11 --tag nem35 --manifest zhtw/$s.jsonl"
done
}
jobs_list | xargs -P 8 -I{} sh -c 'venv/bin/python -I run_clips.py {} --out clipout >> logs/queue2.log 2>&1'
echo QUEUE2 DONE >> logs/queue2.log
