import sys, time
sys.path.insert(0, "/tmp/claude-1000/-home-luigi-VoxSumDroid/4acfab47-ccab-4e6f-900c-5df73615b1c2/scratchpad/gaenv/lib/python3.14/site-packages")
import sentencepiece as spm, numpy as np
from ai_edge_litert.interpreter import Interpreter
D = "/home/luigi/voxsum-models/embeddinggemma/"
sp = spm.SentencePieceProcessor(model_file=D + "sections/Section2_SP_Tokenizer.bin")
emb = Interpreter(model_path=D + "embedder.tflite", num_threads=1).get_signature_runner("embedder_1x1")
enc_it = Interpreter(model_path=D + "encoder.tflite", num_threads=2)
sigs = enc_it.get_signature_list(); print("encoder sigs", {k: v for k, v in sigs.items()})
enc = {n: enc_it.get_signature_runner("encoder_1x%d" % n) for n in (128, 256, 512)}

def embed(text, N=128, add_bos=True, add_eos=True):
    ids = sp.encode(text)
    if add_bos: ids = [sp.bos_id()] + ids
    if add_eos: ids = ids + [sp.eos_id()]
    ids = ids[:N]
    x = np.zeros((1, N, 512), np.float32); m = np.zeros((1, N), np.float32)
    for i, t in enumerate(ids):
        x[0, i] = emb(token_ids=np.array([[t]], np.int32))["embeddings"][0, 0]; m[0, i] = 1
    out = enc[N](embeddings=x, input_mask=m)
    k, v = next(iter(out.items())); return v[0], len(ids)

def cos(a, b): return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))

q = "task: search result | query: 會議的預算是多少"
docs = ["title: none | text: Alice: 我們下週再討論預算。",
        "title: none | text: Bob: 這個專案的經費大概是三百萬。",
        "title: none | text: Alice: 今天天氣很好，我們去散步吧。",
        "title: none | text: Bob: The budget for the project is about three million."]
t0 = time.time(); vq, n = embed(q); print("q tokens", n, "norm", np.linalg.norm(vq), "time", round(time.time() - t0, 2), vq[:4])
for d in docs:
    t0 = time.time(); vd, n = embed(d); print(round(cos(vq, vd), 3), n, round(time.time() - t0, 2), d[20:50])
# bos/eos variants
for bos, eos in ((False, True), (True, False), (False, False)):
    vq2, _ = embed(q, add_bos=bos, add_eos=eos); print("variant bos", bos, "eos", eos, "cos to default", round(cos(vq, vq2), 4), [round(cos(vq2, embed(d, add_bos=bos, add_eos=eos)[0]), 3) for d in docs])
# 256 bucket vs 128 on the same text
v256, _ = embed(q, N=256); print("128 vs 256 same text cos", round(cos(vq, v256), 5))
