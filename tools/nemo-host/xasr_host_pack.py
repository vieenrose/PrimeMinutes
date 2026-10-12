#!/usr/bin/env python3
"""Pack the host-side X-ASR weights (decoder, joiner, vocabulary) from the Q8_0 GGUF into xasr_host.bin,
the only file the ggml-free engine reads besides the LiteRT graph.

Same numbers the ggml engine uses: tensors are dequantised the way ggml's to_f32 does, and the joiner output
matrix is rounded to fp16 (round to nearest even, ggml_fp32_to_fp16) as on AArch64.

Layout (little endian): "XAH1", i32 vocab blank unk D J Kc F, u32 n_vocab strings (u32 len + bytes),
then f32 dec_emb[V,D], dec_conv, dec_proj_w[J,D], dec_proj_b[J], f16 join_out_w[V,J], f32 join_out_b[V].
Usage: xasr_host_pack.py x-asr-zh-en-q8_0.gguf xasr_host.bin
"""
import struct, sys
import numpy as np
from gguf import GGUFReader
from gguf.quants import dequantize

def main(src, dst):
    r = GGUFReader(src)
    kv = {f.name: f for f in r.fields.values()}
    def u32(k, d):
        return int(kv[k].parts[kv[k].data[0]][0]) if k in kv else d
    def tensor(name):
        t = next(t for t in r.tensors if t.name == name)
        return np.ascontiguousarray(dequantize(t.data, t.tensor_type).astype(np.float32).reshape(-1))
    toks = kv["tokenizer.ggml.tokens"]
    vocab = [bytes(toks.parts[i]) for i in toks.data]
    V = u32("xasr.vocab_size", 0); assert V == len(vocab), (V, len(vocab))
    unk = u32("xasr.unk_id", 0xFFFFFFFF); unk = -1 if unk == 0xFFFFFFFF else unk
    hdr = [V, u32("xasr.blank_id", 0), unk, u32("xasr.decoder_dim", 512), u32("xasr.joiner_dim", 512),
           u32("xasr.context_size", 2), u32("xasr.feature_dim", 80)]
    D, J = hdr[3], hdr[4]
    emb, conv = tensor("dec.emb.weight"), tensor("dec.conv.weight")
    pw, pb = tensor("join.decoder_proj.weight"), tensor("join.decoder_proj.bias")
    ow, ob = tensor("join.output_linear.weight"), tensor("join.output_linear.bias")
    assert emb.size == V * D and pw.size == J * D and ow.size == V * J and ob.size == V
    with open(dst, "wb") as f:
        f.write(b"XAH1"); f.write(struct.pack("<7i", *hdr)); f.write(struct.pack("<I", len(vocab)))
        for s in vocab: f.write(struct.pack("<I", len(s))); f.write(s)
        for a in (emb, conv, pw, pb): f.write(struct.pack("<Q", a.size)); f.write(a.tobytes())
        h = ow.astype(np.float16); f.write(struct.pack("<Q", h.size)); f.write(h.tobytes())
        f.write(struct.pack("<Q", ob.size)); f.write(ob.tobytes())
    print(dst, "V", V, "D", D, "J", J, "conv", conv.size)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
