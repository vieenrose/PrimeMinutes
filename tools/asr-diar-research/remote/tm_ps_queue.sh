#!/bin/bash
# training-machine: run pseudo_stream.py configs (lines "TAG|ARGS" of $1) over the 10 meetings, P jobs at once
# (1 thread each). /tmp/luigi_ps holds venv/, wav16/, app/ (meet/norm/app), models/, pseudo_stream.py.
#   bash tm_ps_queue.sh grid.txt [P]   -> /tmp/luigi_ps/raw/TAG/<name>.asr.json
cd /tmp/luigi_ps || exit 1
P=${2:-16}
MEET="L_R003S02C02 L_R004S06C01 M_R003S02C01 M_R003S05C01 S_R003S03C01 S_R004S01C01 ES2004b ES2004d IS1009c TS3003c"
XOFF=models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03
mkdir -p raw logs
while IFS='|' read -r tag args; do
  [ -z "$tag" ] && continue
  for m in $MEET; do echo "$tag|$args|$m"; done
done < "$1" | xargs -P $P -I{} bash -c 'IFS="|" read -r tag args m <<< "{}"; mkdir -p raw/$tag; venv/bin/python -I pseudo_stream.py --dir '$XOFF' --vad-model models/silero_vad.onnx --audio-dir wav16 --app-dir app --out raw/$tag $args $m >> logs/$tag.log 2>&1'
echo QDONE
