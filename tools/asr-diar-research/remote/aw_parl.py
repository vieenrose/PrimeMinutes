# ai-workstation (cu13 venv for pyarrow): sample OpenFormosa/parliament clips into a zh-TW test set
import glob, json, os, random, subprocess, pyarrow.parquet as pq
random.seed(20261017)
src = sorted(glob.glob(os.path.expanduser("~/.cache/huggingface/hub/datasets--OpenFormosa--parliament/snapshots/*/data/*.parquet")))
shards = random.sample(src, 8)
out = os.path.expanduser("~/asr-research/zhtw/parl"); os.makedirs(out, exist_ok=True)
man = open(os.path.expanduser("~/asr-research/zhtw/parl.jsonl"), "w")
n = 0
for sh in shards:
    t = pq.read_table(sh, columns=["audio", "text", "duration", "session_date", "source_url", "source_start_s"])
    rows = [r for r in t.to_pylist() if r["duration"] and r["duration"] >= 5]
    for r in random.sample(rows, min(25, len(rows))):
        cid = "parl%03d" % n; n += 1
        raw = os.path.join("/dev/shm", "luigi_" + cid + ".ogg")
        open(raw, "wb").write(r["audio"]["bytes"])
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", raw, "-ac", "1", "-ar", "16000", "-c:a", "flac", os.path.join(out, cid + ".flac")], check=True)
        os.remove(raw)
        man.write(json.dumps(dict(id=cid, audio=os.path.join(out, cid + ".flac"), text=r["text"].replace(" ", ""), dur=r["duration"],
                                  date=r["session_date"], shard=os.path.basename(sh), url=r["source_url"], start=r["source_start_s"]), ensure_ascii=False) + "\n")
print("clips", n)
# zhtw-bench (FormosaSpeech dev + CV19 zh-TW test; fs4 excluded: defective reference)
m = open(os.path.expanduser("~/asr-research/zhtw/zhtwbench.jsonl"), "w")
for c in ["fs1", "fs2", "fs3", "fs5", "fs6", "fs7", "cv1", "cv2", "cv3"]:
    d = os.path.expanduser("~/zhtw-bench/clips/")
    m.write(json.dumps(dict(id=c, audio=d + c + ".wav", text=open(d + c + ".ref.txt").read().strip()), ensure_ascii=False) + "\n")
