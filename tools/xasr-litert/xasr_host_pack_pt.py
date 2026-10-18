#!/usr/bin/env python3
"""xasr_host.bin (layout of tools/nemo-host/xasr_host_pack.py) from an icefall checkpoint + sherpa tokens.txt.
Usage: xasr_host_pack_pt.py ckpt.pt tokens.txt out.bin"""
import struct, sys, numpy as np, torch
sd = torch.load(sys.argv[1], map_location="cpu")["model"]
vocab = []
for l in open(sys.argv[2], encoding="utf-8").read().split("\n"):
    if not l: continue
    s, i = l.rsplit(" ", 1) if " " in l.strip() else (" ", l.strip()); assert int(i) == len(vocab); vocab.append(s.encode())
V = len(vocab); unk = vocab.index(b"<unk>") if b"<unk>" in vocab else -1
g = lambda k: np.ascontiguousarray(sd[k].float().numpy().reshape(-1))
emb, conv, pw, pb, ow, ob = (g(k) for k in ("decoder.embedding.weight", "decoder.conv.weight", "joiner.decoder_proj.weight",
                                            "joiner.decoder_proj.bias", "joiner.output_linear.weight", "joiner.output_linear.bias"))
D, J = 512, 512; assert emb.size == V * D and ow.size == V * J
with open(sys.argv[3], "wb") as f:
    f.write(b"XAH1"); f.write(struct.pack("<7i", V, 0, unk, D, J, 2, 80)); f.write(struct.pack("<I", V))
    for s in vocab: f.write(struct.pack("<I", len(s))); f.write(s)
    for a in (emb, conv, pw, pb): f.write(struct.pack("<Q", a.size)); f.write(a.tobytes())
    h = ow.astype(np.float16); f.write(struct.pack("<Q", h.size)); f.write(h.tobytes())
    f.write(struct.pack("<Q", ob.size)); f.write(ob.tobytes())
print(sys.argv[3], V, "unk", unk)
