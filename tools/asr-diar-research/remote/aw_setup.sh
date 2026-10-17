#!/bin/bash
W=~/asr-research; cd $W
python3 -m venv venv && venv/bin/pip install -q sherpa-onnx onnxruntime soundfile numpy scipy opencc-python-reimplemented && venv/bin/python -c "import sherpa_onnx;print('sherpa', sherpa_onnx.__version__)"
mkdir -p models; cd models
B=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
for n in "$@"; do
  [ -d $n ] && continue
  mkdir -p dl_$n && curl -sL $B/$n.tar.bz2 | tar xj -C dl_$n && mv dl_$n/$n . && rmdir dl_$n && echo "ok $n $(du -sh $n | cut -f1)" || echo "FAIL $n"
done
[ -f silero_vad.onnx ] || curl -sL -o silero_vad.onnx $B/silero_vad.onnx
df -h ~ | tail -1
