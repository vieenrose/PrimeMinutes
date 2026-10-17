#!/usr/bin/env python3
"""Run a sherpa-onnx ASR candidate over a clip set (manifest jsonl: {"id", "audio": path to 16 kHz mono
wav/flac, "text"}) and write <out>/<set>.<tag>.jsonl with {"id", "hyp", "dur", "cpu", "wall"}.
Offline models decode clips <= --max-seg s whole and longer clips in Silero VAD segments; online models
stream the clip (100 ms pushes, endpointing). Same constructors as run_asr.py.

    venv/bin/python -I run_clips.py --kind sense_voice --dir MODEL --manifest M.jsonl --out OUT --tag sv [--vad V]
"""
import argparse, json, os, sys, time
import numpy as np, soundfile as sf
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_asr as R


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", required=True); ap.add_argument("--dir", required=True)
    ap.add_argument("--manifest", required=True); ap.add_argument("--out", required=True); ap.add_argument("--tag", required=True)
    ap.add_argument("--threads", type=int, default=1); ap.add_argument("--max-seg", type=float, default=25.0)
    ap.add_argument("--vad", default=""); ap.add_argument("--itn", action="store_true"); ap.add_argument("--lang", default="")
    ap.add_argument("--model-type", default=""); ap.add_argument("--kwargs", default="")
    a = ap.parse_args()
    online = a.kind.startswith("online_")
    rec = R.online(a.kind, a.dir, a.threads, a) if online else R.offline(a.kind, a.dir, a.threads, a)
    os.makedirs(a.out, exist_ok=True)
    setname = os.path.basename(a.manifest).rsplit(".", 1)[0]
    dst = os.path.join(a.out, f"{setname}.{a.tag}.jsonl")
    done = set()
    if os.path.exists(dst):
        done = {json.loads(l)["id"] for l in open(dst)}
    fo = open(dst, "a")
    for line in open(a.manifest):
        it = json.loads(line)
        if it["id"] in done:
            continue
        audio, sr = sf.read(it["audio"], dtype="float32")
        if audio.ndim > 1:
            audio = audio.mean(1)
        assert sr == R.SR, sr
        dur = len(audio) / sr
        c0, w0 = time.process_time(), time.perf_counter()
        texts = []
        if online:
            st = rec.create_stream(); pos = 0; step = sr // 10
            while pos < len(audio):
                st.accept_waveform(sr, audio[pos:pos + step]); pos += step
                while rec.is_ready(st):
                    rec.decode_stream(st)
                if rec.is_endpoint(st):
                    texts.append(rec.get_result(st)); rec.reset(st)
            st.accept_waveform(sr, np.zeros(int(2.5 * sr), np.float32)); st.input_finished()
            while rec.is_ready(st):
                rec.decode_stream(st)
            texts.append(rec.get_result(st))
        else:
            segs = [(0.0, dur)] if dur <= a.max_seg else R.vad_segments(audio, a.max_seg, a.vad)
            for s, e in segs:
                lo, hi = max(0, int((s - 0.2) * sr)), min(len(audio), int((e + 0.2) * sr))
                st = rec.create_stream(); st.accept_waveform(sr, audio[lo:hi]); rec.decode_stream(st)
                texts.append(st.result.text)
        cpu, wall = time.process_time() - c0, time.perf_counter() - w0
        fo.write(json.dumps(dict(id=it["id"], hyp=" ".join(t.strip() for t in texts if t.strip()), dur=dur, cpu=cpu, wall=wall),
                            ensure_ascii=False) + "\n"); fo.flush()
    print(f"{setname} {a.tag} done", flush=True)


if __name__ == "__main__":
    main()
