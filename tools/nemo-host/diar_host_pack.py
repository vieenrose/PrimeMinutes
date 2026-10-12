#!/usr/bin/env python3
"""Packs the host-side Sortformer assets from the diarizer GGUF into diar_host.bin.
Format "DH01": magic, u32 n_mels, u32 n_bins, u32 emb_dim, f32 fb[n_mels*n_bins], f32 silence[emb_dim].
Usage: diar_host_pack.py <diar.gguf> <out.bin>"""
import struct, sys
import numpy as np
from gguf import GGUFReader

r = GGUFReader(sys.argv[1])
T = {t.name: t for t in r.tensors}

def bf16(t):
    u = np.asarray(t.data).view(np.uint16).astype(np.uint32) << 16
    return u.view(np.float32)

fb = np.asarray(T["preprocessor.fb"].data, dtype=np.float32).reshape(128, 257)
sil = bf16(T["sortformer_modules.learnable_sil_emb"]).reshape(512)
with open(sys.argv[2], "wb") as f:
    f.write(b"DH01")
    f.write(struct.pack("<III", 128, 257, 512))
    f.write(fb.tobytes())
    f.write(sil.tobytes())
print("fb", fb.shape, float(fb.sum()), "sil", sil[:4])
