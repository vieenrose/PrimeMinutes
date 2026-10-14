import sys, struct, os
sys.path.insert(0, sys.argv[3]) if len(sys.argv) > 3 else None
from ai_edge_litert.internal import litertlm_header_schema_py_generated as H
from ai_edge_litert.internal import litertlm_core as C
p, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
b = open(p, 'rb').read()
# header: "LITERTLM" + u32 major, minor, patch, then u64 header_end offset? read as in core
print("magic", b[:8], struct.unpack('<III', b[8:20]))
hdr_end = struct.unpack('<Q', b[24:32])[0]
print("header_end", hdr_end)
hb = b[32:hdr_end]
# find flatbuffer root: try offsets
root = H.LiteRTLMMetaData.GetRootAs(hb, 0)
n = root.SectionMetadataLength() if hasattr(root,'SectionMetadataLength') else None
print("sections", n)
sm = root.SectionMetadata()
for i in range(sm.ObjectsLength()):
    o = sm.Objects(i)
    beg, end, dt = o.BeginOffset(), o.EndOffset(), o.DataType()
    name = {8:"HF_Tokenizer_Zlib"}.get(dt) or C.any_section_data_type_to_string(dt)
    items = {}
    for k in range(o.ItemsLength()):
        it = o.Items(k); key = it.Key().decode()
        v = it.Value(); vt = it.ValueType()
        tbl = v
        items[key] = (vt, )
        # try decode string value
        try:
            from flatbuffers import table as T
            s = H.StringValue(); s.Init(v.Bytes, v.Pos); items[key] = s.Value().decode()
        except Exception as e: items[key] = f"?{vt}"
    print(i, name, beg, end, end-beg, items)
    open(f"{out}/Section{i+1}_{name}.bin", 'wb').write(b[beg:end])
