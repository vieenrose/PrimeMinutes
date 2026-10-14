import sys, time
# venv: python with ai-edge-litert, sentencepiece, numpy
import sentencepiece as spm, numpy as np
from ai_edge_litert.interpreter import Interpreter
D = __import__("os").environ.get("EMBED_DIR", "/home/luigi/voxsum-models/embeddinggemma/")
sp = spm.SentencePieceProcessor(model_file=D + "sections/Section2_SP_Tokenizer.bin")
emb = Interpreter(model_path=D + "embedder.tflite", num_threads=1).get_signature_runner("embedder_1x1")
enc_it = Interpreter(model_path=D + "encoder.tflite", num_threads=2)
enc = {n: enc_it.get_signature_runner("encoder_1x%d" % n) for n in (128, 256, 512, 1024)}

def embed(text, N=None, add_bos=True, add_eos=True):
    ids = sp.encode(text)
    if N is None: N = next(n for n in (128, 256, 512, 1024) if len(ids) + 2 <= n)
    if add_bos: ids = [sp.bos_id()] + ids
    if add_eos: ids = ids + [sp.eos_id()]
    ids = ids[:N]
    x = np.zeros((1, N, 512), np.float32); m = np.zeros((1, N), np.float32)
    for i, t in enumerate(ids):
        x[0, i] = emb(token_ids=np.array([[t]], np.int32))["embeddings"][0, 0]; m[0, i] = 1
    out = enc[N](embeddings=x, input_mask=m)
    k, v = next(iter(out.items())); return v[0], len(ids)

