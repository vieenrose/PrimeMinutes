#!/usr/bin/env python3
"""Export 3D-Speaker CAM++ zh-en common-advanced to a fixed-shape LiteRT graph for the app.

Source: ModelScope iic/speech_campplus_sv_zh_en_16k-common_advanced (Apache License 2.0),
checkpoint campplus_cn_en_common.pt (sha256 92f29b94...0199), model code speakerlab/models/campplus
from github.com/modelscope/3D-Speaker (Apache-2.0) on PYTHONPATH (--speakerlab).

Graph contract (what nemo::LiteRtGraph feeds it):
  input  0: float32 [1, FRAMES, 80]  Kaldi fbank (torchaudio.compliance.kaldi.fbank defaults: povey window,
            preemph 0.97, dither 0, snip_edges, low 20 Hz, high = Nyquist, log power, samples in [-1, 1]),
            MEAN-NORMALISED over the window's frames per mel bin by the caller (3D-Speaker / sherpa "global-mean").
  output 0: float32 [1, 192]  speaker embedding (NOT normalised; the caller L2-normalises).
FRAMES = 400 (25 ms / 10 ms, snip_edges: 64,240 samples = 4.015 s of 16 kHz audio). With a fixed length, the
CAM layers' segment pooling (ceil-mode avg_pool, segments of 100 frames after the stride-2 TDNN) is rewritten as
static slices: 400 frames -> 200 -> exactly two segments, so the result equals the dynamic model's.

Usage (venv with torch + litert_torch):
  python -I export_litert.py --speakerlab DIR --ckpt campplus_cn_en_common.pt --out campplus_zh_en_400f.tflite [--fp16]
"""
import argparse, sys

FRAMES = 400


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--speakerlab", required=True, help="dir containing the speakerlab package")
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--fp16", action="store_true", help="fp16 weights (dynamic dequant to fp32 at load)")
    ap.add_argument("--frames", type=int, default=FRAMES)
    a = ap.parse_args()
    sys.path.insert(0, a.speakerlab)
    import torch
    import torch.nn.functional as F
    from speakerlab.models.campplus import layers
    from speakerlab.models.campplus.DTDNN import CAMPPlus

    def seg_pooling_static(self, x, seg_len=100, stype="avg"):
        T = x.shape[-1]                      # a Python int under export: static slices
        segs = []
        for s in range(0, T, seg_len):
            e = min(T, s + seg_len)
            m = x[..., s:e].mean(-1, keepdim=True)
            segs.append(m.expand(*x.shape[:-1], e - s))
        return torch.cat(segs, dim=-1)
    layers.CAMLayer.seg_pooling = seg_pooling_static

    model = CAMPPlus(feat_dim=80, embedding_size=192, memory_efficient=False)
    sd = torch.load(a.ckpt, map_location="cpu")
    model.load_state_dict(sd)
    model.eval()

    x = torch.randn(1, a.frames, 80)
    import litert_torch
    q = None
    if a.fp16:
        from litert_torch.generative.quantize import quant_recipes
        q = quant_recipes.full_fp16_recipe()
    edge = litert_torch.convert(model, (x,), quant_config=q) if q else litert_torch.convert(model, (x,))
    edge.export(a.out)
    print("wrote", a.out)


if __name__ == "__main__":
    main()
