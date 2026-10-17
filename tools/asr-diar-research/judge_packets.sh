#!/bin/bash
# Build blind judge packets for the ceiling conditions of one meeting, two shuffles (seeds 1, 2).
#   judge_packets.sh MEETING [READER_DIR] [OUT_ROOT] [conds...]
set -eu
M=$1; R=${2:-$HOME/voxsum-models/asr-research/ceiling/reader_e2b}; O=${3:-$HOME/voxsum-models/asr-research/ceiling/judge}
shift $(( $# < 3 ? $# : 3 ))
CONDS=${*:-app_app gold_app app_gold gold_gold}
I=$HOME/voxsum-models/asr-research/ceiling/inputs; S=gemma4-e2b-meeting-agent-zh-mobile-v1
for seed in 1 2; do
  args=""
  for c in $CONDS; do
    u=$I/$M.$c.json; [ -f "$u" ] || u=$(ls $HOME/voxsum-models/asr-research/e2e/inputs/$M.$c.json)
    args="$args $c=$u:$R/$M.$c.reader.$S.json"
  done
  python3 -I $(dirname "$0")/judge_prep.py --meeting $M --gold $I/$M.gold_gold.json --out $O/s$seed --keys $O/keys_s$seed --seed $seed $args
done
