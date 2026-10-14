import sys
sys.path.insert(0, "/tmp/claude-1000/-home-luigi-VoxSumDroid/4acfab47-ccab-4e6f-900c-5df73615b1c2/scratchpad/gaenv/lib/python3.14/site-packages")
import sentencepiece as spm, numpy as np
from ai_edge_litert.interpreter import Interpreter
D = "/home/luigi/voxsum-models/embeddinggemma/"
sp = spm.SentencePieceProcessor(model_file=D + "sections/Section2_SP_Tokenizer.bin")
print("vocab", sp.get_piece_size(), "bos", sp.bos_id(), "eos", sp.eos_id(), "pad", sp.pad_id())
for t in ["task: search result | query: 會議的預算是多少", "title: none | text: Alice: 我們下週再討論預算。"]:
    ids = sp.encode(t); print(len(ids), ids[:12], [sp.id_to_piece(i) for i in ids[:8]])
it = Interpreter(model_path=D + "embedder.tflite", num_threads=2)
print("signatures", list(it.get_signature_list().keys()))
sd = it.get_signature_list()["embedder_1x1"]; print(sd)
f = it.get_signature_runner("embedder_1x1")
out = f(token_ids=np.array([[ids[0]]], dtype=np.int32))
k, v = next(iter(out.items())); print(k, v.shape, v.dtype, v[0, 0, :6])
