import json, sys, time
sys.path.insert(0, "/tmp/claude-1000/-home-luigi-VoxSumDroid/4acfab47-ccab-4e6f-900c-5df73615b1c2/scratchpad/emb"); sys.argv = sys.argv[:1]
import numpy as np
import embed_lib as E
T = "/tmp/claude-1000/-home-luigi-VoxSumDroid/4acfab47-ccab-4e6f-900c-5df73615b1c2/scratchpad/emb/t6/"
items = json.load(open(T + "texts.json")); texts = [i[2] for i in items]
out = []; t0 = time.time(); lens = []
for t in texts:
    v, n = E.embed(t); lens.append(n); out.append(v / np.linalg.norm(v))
print("lite", len(out), round(time.time() - t0, 1), "s; tokens max", max(lens), "mean", round(sum(lens) / len(lens), 1))
np.save(T + "lite768.npy", np.stack(out).astype(np.float32))
