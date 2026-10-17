#!/bin/bash
# rerun streaming candidates on the clip sets with the 2.5 s tail pad (tags *p)
cd ~/asr-research; M=models
for s in zhtwbench parl ascend; do
 echo "--kind online_transducer --dir $M/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05 --tag x480p --manifest zhtw/$s.jsonl"
 echo "--kind online_transducer --dir $M/sherpa-onnx-x-asr-1920ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05 --tag x1920p --manifest zhtw/$s.jsonl"
 echo "--kind online_transducer --dir $M/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11 --tag nem35p --manifest zhtw/$s.jsonl"
done | xargs -P 9 -I{} sh -c "venv/bin/python -I run_clips.py {} --out clipout >> logs/queue2b.log 2>&1"
echo QUEUE2B DONE >> logs/queue2b.log
