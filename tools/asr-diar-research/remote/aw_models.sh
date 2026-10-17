#!/bin/bash
# ai-workstation: fetch sherpa-onnx model tarballs into $DEST (default ~/asr-research/models), robust to stalls
DEST=${DEST:-~/asr-research/models}; mkdir -p $DEST; cd $DEST
B=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
for n in "$@"; do
  [ -d $n ] && continue
  for try in 1 2 3 4 5; do
    curl -sL --retry 20 --retry-all-errors -C - --speed-limit 20000 --speed-time 60 -o /dev/shm/luigi_$n.tar.bz2 $B/$n.tar.bz2 && break
  done
  mkdir -p dl_$n && tar xjf /dev/shm/luigi_$n.tar.bz2 -C dl_$n && mv dl_$n/$n . && rmdir dl_$n && echo "ok $n" || echo "FAIL $n"
  rm -f /dev/shm/luigi_$n.tar.bz2
done
