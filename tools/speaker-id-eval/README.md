# speaker-id-eval

Host-only feasibility experiment behind `docs/design/speaker-tags.md` §3: can a voice embedding
taken from one meeting rank the right person first in a later meeting? Not product code.

Data: AMI meeting series whose four participants recur in meetings a–d (ES2004, IS1009, TS3003,
EN2002), minutes 2:00–12:00 of each meeting (the window `tools/nemo-eval` uses).
- close talk: `Mix-Headset` excerpts (`~/voxsum-models/der12/<m>_ex.wav`);
- far field: `Array1-01` (one table microphone), the same window, fetched with HTTP ranges from the
  AMI mirror (`https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/<m>/audio/<m>.Array1-01.wav`,
  bytes `44 + 120*32000` to `+600*32000`), then given the app's import gain rule (GainNormalizer:
  speech level to −20 dBFS when it is under −27 dBFS).
- references: `~/voxsum-models/der12/<m>.rttm` (full-meeting AMI RTTM; windowed by `eval.py`).
- system segmentation: the host diarizer (`nemo-x-asr-diarizer --no-asr --turns-out`, q8 GGUF).

```bash
python3 -m venv venv && venv/bin/pip install sherpa-onnx soundfile numpy
# speaker models: sherpa-onnx release "speaker-recongition-models" (sic), e.g.
#   3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx, nemo_en_titanet_small.onnx,
#   wespeaker_en_voxceleb_resnet34_LM.onnx, wespeaker_en_voxceleb_CAM++.onnx,
#   3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx
venv/bin/python -I eval.py --audio 'der12/{m}_ex.wav' --rttm 'der12/{m}.rttm' \
    [--turns 'turns/close/{m}.json'] --onnx models/<model>.onnx --json out.json

# the "reuse the diarizer" option: Sortformer frame features (torch venv), then
python -I sortformer_feats.py --ckpt Nemotron-3-Diarization/model_weights.ckpt --out feats/close der12/*_ex.wav
venv/bin/python -I eval.py ... --sf feats/close:pre --sf feats/close:mid --sf feats/close:fin --sf feats/close:proj
```

`eval.py` prints, per model and per speech budget (seconds of clean speech per profile): EER over
cross-meeting profile pairs (all, and within a series only), top-1/top-3 identification against
every enrolled person (enrolled from meeting a; "progressive" = enrolled from all earlier meetings),
and the outcome at the EER threshold. `timing.py` measures the per-segment cost (1 thread).

`results/`: the raw logs of the 2026-10-17 run quoted in the design doc. The ERes2NetV2 runs were
stopped before they finished because the model is too slow; WeSpeaker CAM++ has a suspected
front-end mismatch.
