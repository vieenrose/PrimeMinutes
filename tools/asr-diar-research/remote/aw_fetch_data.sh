#!/bin/bash
# ai-workstation: fetch the 10 eval meetings as 16 kHz mono FLAC (AISHELL-4 channel 0, AMI Mix-Headset)
set -u
W=~/asr-research; mkdir -p $W/audio $W/logs; cd $W/audio
for m in ES2004b ES2004d IS1009c TS3003c; do
  [ -f $m.flac ] && continue
  curl -sL --retry 20 --retry-all-errors -C - --speed-limit 20000 --speed-time 60 -o /dev/shm/luigi_$m.wav https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/$m/audio/$m.Mix-Headset.wav && \
  ffmpeg -loglevel error -y -i /dev/shm/luigi_$m.wav -ac 1 -ar 16000 -c:a flac $m.flac; rm -f /dev/shm/luigi_$m.wav; echo "ami $m $(ls -la $m.flac)"
done
mkdir -p /dev/shm/luigi_a4
curl -sL --speed-limit 20000 --speed-time 120 https://www.openslr.org/resources/111/test.tar.gz | tar xz -C /dev/shm/luigi_a4 --wildcards '*L_R003S02C02*' '*L_R004S06C01*' '*M_R003S02C01*' '*M_R003S05C01*' '*S_R003S03C01*' '*S_R004S01C01*'
find /dev/shm/luigi_a4 -type f | head -30
for f in $(find /dev/shm/luigi_a4 -name "*.flac"); do
  m=$(basename $f .flac)
  ffmpeg -loglevel error -y -i $f -af "pan=mono|c0=c0" -ar 16000 -c:a flac $m.flac && echo "a4 $m $(ls -la $m.flac)"
done
mkdir -p ../refs; find /dev/shm/luigi_a4 \( -name "*.TextGrid" -o -name "*.rttm" \) -exec cp {} ../refs/ \;
rm -rf /dev/shm/luigi_a4
echo DONE
