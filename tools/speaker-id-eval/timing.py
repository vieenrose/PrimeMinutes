#!/usr/bin/env python3
"""Per-segment cost of each speaker model, 1 thread (host). Usage: timing.py audio.wav model.onnx ..."""
import sys, time
import numpy as np
import soundfile as sf
import sherpa_onnx

x, sr = sf.read(sys.argv[1], dtype="float32")
for path in sys.argv[2:]:
    ex = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=path, num_threads=1))
    row = []
    for secs in (2, 4, 8):
        ts = []
        for k in range(12):
            a = (30 + 17 * k) * sr
            st = ex.create_stream(); st.accept_waveform(sr, x[a:a + secs * sr]); st.input_finished()
            t = time.perf_counter(); ex.compute(st); ts.append(time.perf_counter() - t)
        row.append(f"{secs}s: {1000 * np.median(ts[2:]):.0f} ms")
    print(path.rsplit("/", 1)[-1], " | ".join(row), flush=True)
