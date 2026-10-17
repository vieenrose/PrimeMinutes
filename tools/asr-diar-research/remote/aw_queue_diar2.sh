#!/bin/bash
# input-perturbation replicas of the base run: gain x0.99 / x1.01 / x0.95 (same model, same geometry)
cd ~/asr-research/diar; G="128 40 50 12 60"
until [ -f out/diarq.done ]; do sleep 20; done
for f in 0.99 1.01 0.95; do GAINF=$f ./run_diar.sh out/pert_g$f "$G" 10 2; done
echo DIARQ2 DONE > out/diarq2.done
