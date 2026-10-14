"""Fold a LiteRT-LM 1.7 'TFLiteWeights' section back into its tflite graph → self-contained .tflite."""
import sys, time
sys.path.insert(0, "/tmp/claude-1000/-home-luigi-VoxSumDroid/4acfab47-ccab-4e6f-900c-5df73615b1c2/scratchpad/gaenv/lib/python3.14/site-packages")
import flatbuffers
from flatbuffers import table as fbt, number_types as N
from ai_edge_litert import schema_py_generated as S

model_path, weights_path, out_path = sys.argv[1:4]
b = open(model_path, 'rb').read(); w = open(weights_path, 'rb').read()
m = S.Model.GetRootAs(b, 0)
mt = m._tab
def vec(tab, slot):
    o = tab.Offset(slot)
    if not o: return []
    n = tab.VectorLen(o); base = tab.Vector(o)
    return [tab.Indirect(base + i * 4) for i in range(n)]
def u32(tab, slot, d=0):
    o = tab.Offset(slot); return tab.Get(N.Uint32Flags, o + tab.Pos) if o else d
def u64(tab, slot, d=0):
    o = tab.Offset(slot); return tab.Get(N.Uint64Flags, o + tab.Pos) if o else d
def s(tab, slot):
    o = tab.Offset(slot); return tab.String(o + tab.Pos).decode() if o else ""
groups = [s(fbt.Table(mt.Bytes, p), 4) for p in vec(mt, 20)]
ext = {}
for p in vec(mt, 22):
    t = fbt.Table(mt.Bytes, p)
    ext[u32(t, 4)] = (u32(t, 6), u64(t, 8), u64(t, 10), s(t, 12))
print("groups", groups, "external_buffers", len(ext), "weights bytes", len(w))
print("packings", {v[3] for v in ext.values()}, "max end", max(o + l for _, o, l, _ in ext.values()))
# (subgraph, tensor) -> external id; each distinct id gets its own new buffer
refs = {}
for si, sp in enumerate(vec(mt, 8)):
    sg = fbt.Table(mt.Bytes, sp)
    for ti, tp in enumerate(vec(sg, 4)):
        t = fbt.Table(mt.Bytes, tp)
        eid_off = t.Offset(24)
        if not eid_off: continue
        refs[(si, ti)] = t.Get(N.Uint32Flags, eid_off + t.Pos)
ids = sorted(set(refs.values()))
print("tensor refs", len(refs), "distinct external ids", len(ids), "bytes", sum(ext[i][2] for i in ids))
t0 = time.time()
mo = S.ModelT.InitFromObj(m)
newbuf = {}  # (offset, length) -> buffer index: the 6 signatures share one copy of each weight
for eid in ids:
    g, off, ln, pk = ext[eid]
    assert groups[g] == "tflite_weights" and pk == "unpacked", (groups[g], pk)
    if (off, ln) in newbuf: continue
    bt = S.BufferT(); bt.data = bytearray(w[off:off + ln]); assert len(bt.data) == ln
    newbuf[(off, ln)] = len(mo.buffers); mo.buffers.append(bt)
print("distinct weight slices", len(newbuf), "bytes", sum(k[1] for k in newbuf))
for (si, ti), eid in refs.items():
    mo.subgraphs[si].tensors[ti].buffer = newbuf[ext[eid][1:3]]
    mo.subgraphs[si].tensors[ti].externalBuffer = 0
mo.externalBuffers = None; mo.externalBufferGroups = None
print("unpacked in", round(time.time() - t0, 1), "s; packing…")
fb = flatbuffers.Builder(len(b) + len(w) + (1 << 20))
fb.Finish(mo.Pack(fb), b"TFL3")
open(out_path, 'wb').write(fb.Output())
print("wrote", out_path, len(fb.Output()), "bytes in", round(time.time() - t0, 1), "s")
