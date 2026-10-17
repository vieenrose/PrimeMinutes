#!/bin/bash
# wait for the 10 meetings, then run the first candidate batch
cd ~/asr-research
until [ $(ls audio/*.flac 2>/dev/null | wc -l) -ge 10 ]; do sleep 30; done
M=models
./bench_asr.sh x480   "--kind online_transducer --dir $M/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05" 6
./bench_asr.sh xoff_app "--kind transducer --dir $M/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03 --seg app" 6
./bench_asr.sh sv_app "--kind sense_voice --dir $M/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 --seg app" 6
./bench_asr.sh frctc_app "--kind fire_red_asr_ctc --dir $M/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25 --seg app" 6
./bench_asr.sh x1920  "--kind online_transducer --dir $M/sherpa-onnx-x-asr-1920ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05" 6
./bench_asr.sh para_app "--kind paraformer --dir $M/sherpa-onnx-paraformer-zh-int8-2025-10-07 --seg app" 6
./bench_asr.sh dolphin_app "--kind dolphin_ctc --dir $M/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02 --seg app" 6
./bench_asr.sh xoff_vad "--kind transducer --dir $M/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03 --seg vad --vad models/silero_vad.onnx" 6
./bench_asr.sh nem35 "--kind online_transducer --dir $M/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11" 6
echo QUEUE1 DONE > logs/queue1.done
