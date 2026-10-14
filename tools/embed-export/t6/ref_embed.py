import json, sys, time, numpy as np, torch
from sentence_transformers import SentenceTransformer
T = "/tmp/claude-1000/-home-luigi-VoxSumDroid/4acfab47-ccab-4e6f-900c-5df73615b1c2/scratchpad/emb/t6/"
items = json.load(open(T + "texts.json")); texts = [i[2] for i in items]
m = SentenceTransformer("/home/luigi/voxsum-models/embeddinggemma/ref", device="cpu", model_kwargs={"torch_dtype": torch.float32})
tok = m.tokenizer; print("ids sample", tok(texts[0])["input_ids"][:6], tok(texts[0])["input_ids"][-2:])
t0 = time.time(); v = m.encode(texts, batch_size=8, normalize_embeddings=True, convert_to_numpy=True); print("ref", v.shape, round(time.time() - t0, 1), "s")
np.save(T + "ref768.npy", v.astype(np.float32))
