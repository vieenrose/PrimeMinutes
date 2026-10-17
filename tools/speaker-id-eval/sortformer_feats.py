#!/usr/bin/env python3
"""Per-frame hidden states of the Nemotron-3 Sortformer diarizer (the app's diarizer), for the
"reuse the diarizer" option of docs/design/speaker-tags.md.

Mel frontend + 8-frame stacking + pre_encode.proj + the 31-layer encoder, in torch, from the
.nemo checkpoint (model_weights.ckpt). Plain non-overlapping windows (no speaker cache): this
probes whether the encoder's frames carry a speaker identity that survives across meetings.

Writes <out>/<name>.npz with, per 80 ms frame: pre (512, = what the speaker cache stores),
mid (512, layer 16 output), fin (512, final_norm), proj (192, encoder_proj), probs (8).

Usage (needs torch + numpy + soundfile, e.g. the litert-export venv):
  python -I sortformer_feats.py --ckpt model_weights.ckpt --out feats/close a.wav b.wav ...
"""
import argparse, math, os, sys
import numpy as np
import soundfile as sf
import torch
import torch.nn.functional as F

D, H, HD, L = 512, 8, 64, 31


def load(ckpt):
    sd = torch.load(ckpt, map_location="cpu", weights_only=False)
    sd = sd.get("state_dict", sd)
    return {k: torch.as_tensor(v).float() for k, v in sd.items()}


def mel(sd, x):
    # NeMo AudioToMelSpectrogramPreprocessor, inference: no dither, preemph 0.97, hann(400), n_fft 512,
    # hop 160, centre zero pad, power, mel fb from the checkpoint, log(x + 2^-24), normalize NA.
    x = torch.as_tensor(x, dtype=torch.float32)
    x = torch.cat([x[:1], x[1:] - 0.97 * x[:-1]])
    win = sd["preprocessor.featurizer.window"]
    spec = torch.stft(x, n_fft=512, hop_length=160, win_length=400, window=win, center=True,
                      pad_mode="constant", return_complex=True)
    p = spec.abs() ** 2                                 # [257, T]
    m = sd["preprocessor.featurizer.fb"][0] @ p         # [128, T]
    return torch.log(m + 2 ** -24).t()                  # [T, 128]


def rope_tables(T):
    t = torch.arange(T, dtype=torch.float64)[:, None]
    d = torch.arange(HD // 2, dtype=torch.float64)[None, :]
    ang = (t * (1.0 / (10000.0 ** (2.0 * d / HD)))).float()
    return torch.cos(ang), torch.sin(ang)


def rope(t, c, s):
    a, b = t[..., :HD // 2], t[..., HD // 2:]
    return torch.cat([a * c - b * s, b * c + a * s], dim=-1)


def ln(x, sd, p):
    return F.layer_norm(x, (D,), sd[p + ".weight"], sd[p + ".bias"], 1e-5)


def encode(sd, emb):
    T = emb.shape[0]
    c, s = rope_tables(T)
    x = ln(emb, sd, "encoder.embed_norm")
    mid = None
    for i in range(L):
        p = f"encoder.layers.{i}."
        n1 = ln(x, sd, p + "norm1")
        q, k, v = (n1 @ sd[p + "attn.w_qkv.weight"].t()).split(D, dim=-1)
        q, k, v = (z.view(T, H, HD).transpose(0, 1) for z in (q, k, v))
        q, k = rope(q, c, s), rope(k, c, s)
        a = torch.softmax((q @ k.transpose(-1, -2)) / math.sqrt(HD), dim=-1) @ v
        x = x + a.transpose(0, 1).reshape(T, D) @ sd[p + "attn.out_proj.weight"].t() + sd[p + "attn.out_proj.bias"]
        f = F.gelu(ln(x, sd, p + "norm2") @ sd[p + "ffn.net.0.weight"].t() + sd[p + "ffn.net.0.bias"])
        x = x + f @ sd[p + "ffn.net.3.weight"].t() + sd[p + "ffn.net.3.bias"]
        if i == 15:
            mid = x.clone()
    fin = ln(x, sd, "encoder.final_norm")
    proj = fin @ sd["sortformer_modules.encoder_proj.weight"].t() + sd["sortformer_modules.encoder_proj.bias"]
    # Head without the x8 upsample (frame-level probs, for a sanity check only).
    h = F.relu(proj)
    h = F.relu(h @ sd["sortformer_modules.first_hidden_to_hidden.weight"].t() + sd["sortformer_modules.first_hidden_to_hidden.bias"])
    probs = torch.sigmoid(h @ sd["sortformer_modules.single_hidden_to_spks.weight"].t() + sd["sortformer_modules.single_hidden_to_spks.bias"])
    return mid, fin, proj, probs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--window", type=int, default=188, help="encoder frames per window (188 = 15.04 s)")
    ap.add_argument("wavs", nargs="+")
    a = ap.parse_args()
    torch.set_num_threads(4)
    sd = load(a.ckpt)
    os.makedirs(a.out, exist_ok=True)
    for w in a.wavs:
        x, sr = sf.read(w, dtype="float32")
        assert sr == 16000 and x.ndim == 1, w
        with torch.no_grad():
            m = mel(sd, x)
            T8 = m.shape[0] // 8
            st = m[: T8 * 8].reshape(T8, 8 * 128)
            pre = st @ sd["encoder.pre_encode.proj.weight"].t()
            outs = {k: [] for k in ("mid", "fin", "proj", "probs")}
            for s0 in range(0, T8, a.window):
                r = encode(sd, pre[s0:s0 + a.window])
                for k, v in zip(("mid", "fin", "proj", "probs"), r):
                    outs[k].append(v)
        name = os.path.basename(w).rsplit(".", 1)[0]
        np.savez(os.path.join(a.out, name + ".npz"), pre=pre.numpy(), **{k: torch.cat(v).numpy() for k, v in outs.items()})
        print(name, T8, "frames", file=sys.stderr)


if __name__ == "__main__":
    main()
