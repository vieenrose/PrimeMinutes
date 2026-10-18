import sys; sys.path.insert(0, "/home/luigi/voxsum-models/xoff-import")
import numpy as np, torch, onnxruntime as ort, xoff_torch as XT
import os; M = os.environ.get("XM", "/home/luigi/voxsum-models/asr-research/models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03")
s = ort.InferenceSession(f"{M}/encoder-epoch-99-avg-1.int8.onnx", providers=["CPUExecutionProvider"])
enc = XT.OffEnc(XT.load()).eval(); rng = np.random.default_rng(0)
for T in (100, 500, 2000):
    x = (rng.standard_normal((1, T, 80)) * 3 - 8).astype(np.float32)
    o = s.run(None, {"x": x, "x_lens": np.array([T], dtype=np.int64 if "int64" in s.get_inputs()[1].type else np.int32)})[0]
    with torch.no_grad(): t = enc(torch.from_numpy(x), torch.tensor([T]))[0].numpy()
    print(T, o.shape, t.shape, "max|d|", np.abs(o - t).max(), "rel", np.abs(o - t).mean() / np.abs(o).mean(), "corr", np.corrcoef(o.ravel(), t.ravel())[0, 1])
