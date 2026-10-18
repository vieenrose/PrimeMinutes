"""Reconstruct the X-ASR offline (sherpa int8 2026-06-03) encoder as an icefall torch model: dequantize every MatMulInteger weight."""
import sys, re, numpy as np, torch, onnx
from onnx import numpy_helper as nh
sys.path.insert(0, "/home/luigi/src/nemo-x-asr-diarizer.cpp/tools/litert-export")
import xasr_stream_torch as X
import os; M = os.environ.get("XM", "/home/luigi/voxsum-models/asr-research/models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03")
ICE = "/home/luigi/xasr-reexport/icefall"

def load_graph(p):
    g = onnx.load(p).graph
    init = {i.name: nh.to_array(i) for i in g.initializer}
    for n in g.node:
        if n.op_type == "Constant":
            for a in n.attribute:
                if a.name == "value": init[n.output[0]] = nh.to_array(a.t)
    prod = {o: n for n in g.node for o in n.output}
    cons = {}
    for n in g.node:
        for i in n.input: cons.setdefault(i, []).append(n)
    return g, init, prod, cons

def deq_from_mmi(n, init):
    if n.op_type == "MatMul": return init[n.input[1]].astype(np.float32)
    q = init[n.input[1]].astype(np.float32); zp = init[n.input[3]].astype(np.float32) if len(n.input) > 3 and n.input[3] in init else 0.0
    sc = init[n.input[1].replace("_quantized", "_scale")]
    return (q - zp) * sc  # (in, out)

g, init, prod, cons = load_graph((f"{M}/encoder-epoch-99-avg-1.int8.onnx" if os.path.exists(f"{M}/encoder-epoch-99-avg-1.int8.onnx") else f"{M}/encoder-epoch-99-avg-1.onnx"))
model = X.build(ICE, "/home/luigi/xasr-reexport/hf/xasr_native.pt")  # architecture + placeholder weights
enc = model.encoder; enc.chunk_size = (-1,); enc.left_context_frames = (-1,)
sd = model.state_dict(); out = {}
names = set(sd)
for k, v in init.items():
    if k in names: out[k] = v
# map Linear weights through the bias Add: Add(bias) <- Mul <- Cast? <- MatMulInteger
mmis = [n for n in g.node if n.op_type == "MatMulInteger" or (n.op_type == "MatMul" and n.input[1] in init)]
def up(t, depth=6):
    n = prod.get(t)
    while n is not None and depth:
        if n.op_type == "MatMulInteger" or (n.op_type == "MatMul" and n.input[1] in init): return n
        n = prod.get(n.input[0]) if n.op_type in ("Cast", "Mul") else None; depth -= 1
    return None
used = set()
for n in g.node:
    if n.op_type not in ("Add",): continue
    b = [i for i in n.input if i in names and i.endswith(".bias")]
    if not b: continue
    other = [i for i in n.input if i != b[0]][0]
    m = up(other)
    if m is None: continue
    w = b[0][:-5] + ".weight"
    if w in names and w not in out:
        W = deq_from_mmi(m, init).T
        if W.shape == tuple(sd[w].shape): out[w] = W; used.add(m.name)
miss = [k for k in sd if k not in out]
print("mapped", len(out), "/", len(sd)); fam = {}
for k in miss: fam[re.sub(r"\.\d+\.", ".N.", k)] = fam.get(re.sub(r"\.\d+\.", ".N.", k), 0) + 1
for f, c in sorted(fam.items()): print(c, f, tuple(sd[[k for k in miss if re.sub(r'\.\d+\.', '.N.', k) == f][0]].shape))
left = [m for m in mmis if m.name not in used]; print("unused MatMulInteger", len(left))
torch.save({"out": {k: torch.tensor(np.asarray(v, dtype=np.float32)) for k, v in out.items()}, "left": [(m.name, m.input[1]) for m in left]}, "partial.pt")

# ---------- stage 2 ----------
order = {n.name: i for i, n in enumerate(g.node)}
def first_use(k): return min(order[n.name] for n in cons[k])
# linear_pos + encoder_proj from the remaining MatMulIntegers
lp = [k for k in sd if k.endswith("linear_pos.weight")]  # module order == graph order
rest = sorted(left, key=lambda m: order[m.name])
print([init[m.input[1]].shape for m in rest]); lpm = [m for m in rest if init[m.input[1]].shape[0] == 48 and init[m.input[1]].shape[1] in (16, 32)]
assert len(lpm) == len(lp), (len(lpm), len(lp))
for k, m in zip(lp, lpm): out[k] = deq_from_mmi(m, init).T
ep = [m for m in rest if init[m.input[1]].shape == (768, 512)]; assert len(ep) == 1
out["joiner.encoder_proj.weight"] = deq_from_mmi(ep[0], init).T
out["joiner.encoder_proj.bias"] = init["encoder_proj.bias"]
# chunkwise_conv_scale: pairs of (C,K) constants in graph order
cks = [k for k in sd if k.endswith("chunkwise_conv_scale")]
cc = sorted([k for k, v in init.items() if k.isdigit() and np.asarray(v).ndim == 2], key=int)
assert len(cc) == 2 * len(cks), (len(cc), len(cks))
for i, k in enumerate(cks):
    a, b = np.asarray(init[cc[2 * i]]), np.asarray(init[cc[2 * i + 1]])
    out[k] = np.stack([a, b]); assert out[k].shape == tuple(sd[k].shape), (k, out[k].shape)
# downsample softmax weights
dsw = sorted([k for k, v in init.items() if k.startswith("onnx::") and np.asarray(v).ndim == 3 and np.asarray(v).shape[1:] == (1, 1) and k in cons and cons[k][0].op_type == "Mul"], key=first_use)
dsn = [f"encoder.encoders.{i}.downsample.bias" for i in range(1, 6)] + ["encoder.downsample_output.bias"]
print("ds", [np.asarray(init[k]).shape for k in dsw])
for k, n in zip(dsw, dsn):
    w = np.asarray(init[k]).reshape(-1); assert w.shape == tuple(sd[n].shape); out[n] = np.log(w)
# decoder (fp32) / joiner (int8)
dg, di, dp, dc = load_graph(f"{M}/decoder-epoch-99-avg-1.onnx")
out["decoder.embedding.weight"] = di["decoder.embedding.weight"]; out["decoder.conv.weight"] = di["decoder.conv.weight"]
out["joiner.decoder_proj.bias"] = di["decoder_proj.bias"]
W = di["decoder_proj.weight"]; out["joiner.decoder_proj.weight"] = W if W.shape == (512, 512) and any(n.op_type=="Gemm" and any(a.name=="transB" and a.i==1 for a in n.attribute) for n in dg.node) else W.T
jg, ji, jp, jc = load_graph((f"{M}/joiner-epoch-99-avg-1.int8.onnx" if os.path.exists(f"{M}/joiner-epoch-99-avg-1.int8.onnx") else f"{M}/joiner-epoch-99-avg-1.onnx"))
jm = [n for n in jg.node if n.op_type == "MatMulInteger" or (n.op_type in ("MatMul", "Gemm") and n.input[1] in ji)]; print("joiner mmi", len(jm), [n.op_type for n in jg.node][:20])
W = deq_from_mmi(jm[0], ji) if jm[0].op_type != "Gemm" else ji[jm[0].input[1]]; out["joiner.output_linear.weight"] = W.T if W.shape == (512, 5000) else W
out["joiner.output_linear.bias"] = ji["output_linear.bias"]
miss = [k for k in sd if k not in out]; print("still missing", [k for k in miss if "bypass_scale" not in k or "bypass." in k])
new = {k: torch.tensor(np.asarray(out[k], dtype=np.float32)) if k in out else sd[k] for k in sd}
model.load_state_dict(new, strict=True)
torch.save({"model": model.state_dict()}, os.environ.get("XOUT", "xoff_native.pt")); print("saved")
