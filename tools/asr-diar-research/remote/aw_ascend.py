# ai-workstation: ASCEND test (zh-en code-switch, CC-BY-SA-4.0) -> 300 sampled clips with >= 2 s, mixed-language first
import io, json, os, random, subprocess, urllib.request, pyarrow.parquet as pq
random.seed(20261017)
raw = "/dev/shm/luigi_ascend_test.parquet"
if not os.path.exists(raw):
    urllib.request.urlretrieve("https://huggingface.co/api/datasets/CAiRE/ASCEND/parquet/main/test/0.parquet", raw)
t = pq.read_table(raw); print(t.schema)
rows = t.to_pylist()
mixed = [r for r in rows if r.get("language") == "mixed" and r.get("duration", 3) >= 2]
other = [r for r in rows if r.get("language") != "mixed" and r.get("duration", 3) >= 2]
sel = random.sample(mixed, min(200, len(mixed))) + random.sample(other, min(100, len(other)))
out = os.path.expanduser("~/asr-research/zhtw/ascend"); os.makedirs(out, exist_ok=True)
man = open(os.path.expanduser("~/asr-research/zhtw/ascend.jsonl"), "w")
for i, r in enumerate(sel):
    cid = "asc%03d" % i; a = r["audio"]
    src = "/dev/shm/luigi_" + cid
    open(src, "wb").write(a["bytes"])
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-ar", "16000", "-c:a", "flac", os.path.join(out, cid + ".flac")], check=True)
    os.remove(src)
    man.write(json.dumps(dict(id=cid, audio=os.path.join(out, cid + ".flac"), text=r["transcription"], lang=r.get("language"), dur=r.get("duration")), ensure_ascii=False) + "\n")
os.remove(raw); print("clips", len(sel), "mixed", sum(1 for r in sel if r.get("language") == "mixed"))
