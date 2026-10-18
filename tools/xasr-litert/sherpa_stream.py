#!/usr/bin/env python3
"""sherpa-onnx OnlineRecognizer (streaming X-ASR) over a whole meeting as ONE stream, like the app; tokens grouped into
~10 s lines (cut at the first token gap >= 0.5 s after 10 s, hard 20 s) for score_asr.py. Parity reference for the LiteRT
export.  sherpa_stream.py DIR OUT NAME...   (wav16/<name>.wav, endpointing off, greedy, blank penalty 0)"""
import json, os, sys, time, glob, re
import numpy as np, soundfile as sf, sherpa_onnx as so
D, OUT = sys.argv[1], sys.argv[2]; os.makedirs(OUT, exist_ok=True)
pick = lambda p: sorted(glob.glob(os.path.join(D, p)))[0]
rec = so.OnlineRecognizer.from_transducer(tokens=f"{D}/tokens.txt", encoder=pick("encoder*.onnx"), decoder=pick("decoder*.onnx"),
                                          joiner=pick("joiner*.onnx"), num_threads=2, decoding_method="greedy_search", enable_endpoint_detection=False)
for name in sys.argv[3:]:
    a, sr = sf.read(f"wav16/{name}.wav", dtype="float32"); t0 = time.perf_counter()
    st = rec.create_stream()
    for i in range(0, len(a), 16000): st.accept_waveform(sr, a[i:i + 16000]); 
    st.accept_waveform(sr, np.zeros(int(2.5 * sr), np.float32)); st.input_finished()
    while rec.is_ready(st): rec.decode_stream(st)
    r = rec.get_result_all(st); toks, ts = r.tokens, r.timestamps
    lines, cur = [], []
    for k, (tok, t) in enumerate(zip(toks, ts)):
        if cur and ((t - cur[0][1] >= 10 and t - cur[-1][1] >= 0.5) or t - cur[0][1] >= 20): lines.append(cur); cur = []
        cur.append((tok, t))
    if cur: lines.append(cur)
    utts = []
    for i, l in enumerate(lines):
        txt = " ".join("".join(x for x, _ in l).replace("▁", " ").split())
        txt = re.sub(r"(?<=[㐀-鿿]) (?=[㐀-鿿])", "", txt)
        utts.append(dict(index=i, text=txt, start=l[0][1], end=l[-1][1] + 0.3, speaker=0))
    json.dump(dict(name=name, kind="sherpa_stream", audio_sec=len(a) / sr, wall_sec=time.perf_counter() - t0, gain=1.0, skipped_sec=0,
                   utterances=utts), open(f"{OUT}/{name}.asr.json", "w"), ensure_ascii=False, indent=0)
    print(name, len(utts), flush=True)
