#!/usr/bin/env python3
"""Pseudo-streaming with the X-ASR OFFLINE transducer: cut the meeting into utterances the way a live app could
(each one decoded on its own as soon as it ends), decode every utterance with sherpa-onnx, write harness-style
<name>.asr.json files for score_asr.py. Research only (docs/research/xasr-offline-pseudostream.md).

Segmentation (--seg):
  app     the app's own transcript lines (speaker change, or >= 10 s at a sentence end, hard cut at 20 s);
          known live only once the diarizer has settled them (live_settle_s, 15 s)
  merge   app lines joined across speakers while the gap between them is < --merge-gap s, then cut at --max-cut
          (what an ASR-only endpointer sees: speaker changes are not utterance ends)
  fixed   no endpointer: the whole recording cut every max-cut/2..max-cut s at the quietest 100 ms frame
  vad     Silero VAD (--vad-model, --min-silence, --vad-thr), segments cut at --max-cut by the VAD itself
--seg-pad P widens every segment by P s of real audio on both sides, never past the middle of the gap to its neighbour.
Every segment longer than --max-cut is cut at its quietest 100 ms frame between max-cut/2 and max-cut.

Decoding of one segment [s, e):
  --ctx C        add C s of real audio on both sides, then keep only tokens whose timestamp is in [s - tol, e + tol)
                 (tol = --ctx-tol); 0 = no context (the research report's xoff_app0)
  --tail-zero T  append T s of digital silence after the segment (the streaming path pads kWindow*10+1000 ms at
                 the end of the stream; offline decoding has no such flush)
  --blank-penalty, --method (greedy_search | modified_beam_search)
Audio: <audio-dir>/<name>.wav, 16 kHz mono, ALREADY multiplied by the app's GainNormalizer gain (wav16/).
Speakers: app segments keep the app's speaker; other segments take the app speaker with the largest overlap.
Per segment the host decode wall time is stored (dec_ms) for the latency model.

    venv/bin/python -I pseudo_stream.py --dir XOFF --audio-dir wav16 --app-dir meet/norm/app --out OUT [opts] NAME...
"""
import argparse, glob, json, os, time
import numpy as np
import soundfile as sf
import sherpa_onnx as so

SR = 16000


def pick(d, pat, int8=True):
    c = sorted(glob.glob(os.path.join(d, pat)))
    i8 = [x for x in c if "int8" in os.path.basename(x)]
    return (i8 or c)[0] if int8 else ([x for x in c if "int8" not in os.path.basename(x)] or c)[0]


def quiet_cut(audio, s, e, max_cut):
    out = []
    while e - s > max_cut:
        lo, hi = s + max_cut / 2, s + max_cut
        seg = audio[int(lo * SR):int(hi * SR)]
        nfr = max(1, len(seg) // 1600)
        en = [float(np.mean(seg[i * 1600:(i + 1) * 1600] ** 2)) for i in range(nfr)]
        cut = lo + (int(np.argmin(en)) + 0.5) * 0.1
        out.append((s, cut)); s = cut
    out.append((s, e))
    return out


def vad_segments(audio, a):
    cfg = so.VadModelConfig()
    cfg.silero_vad.model = a.vad_model
    cfg.silero_vad.min_silence_duration = a.min_silence
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = a.max_cut
    cfg.silero_vad.threshold = a.vad_thr
    cfg.sample_rate = SR
    vad = so.VoiceActivityDetector(cfg, buffer_size_in_seconds=a.max_cut + 60)
    segs = []
    def drain():
        while not vad.empty():
            f = vad.front; segs.append((f.start / SR, (f.start + len(f.samples)) / SR)); vad.pop()
    for i in range(0, len(audio), 512):
        vad.accept_waveform(audio[i:i + 512]); drain()
    vad.flush(); drain()
    return segs


def majority(tl, s, e):
    best, bo = None, 0.0
    for a, b, k in tl:
        o = min(b, e) - max(a, s)
        if o > bo:
            best, bo = k, o
    if best is None and tl:
        best = min(tl, key=lambda x: min(abs(x[0] - e), abs(x[1] - s)))[2]
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True); ap.add_argument("--audio-dir", required=True)
    ap.add_argument("--app-dir", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--seg", default="app", choices=["app", "merge", "vad", "fixed"])
    ap.add_argument("--max-cut", type=float, default=1e9); ap.add_argument("--merge-gap", type=float, default=0.3)
    ap.add_argument("--vad-model", default=""); ap.add_argument("--min-silence", type=float, default=0.5)
    ap.add_argument("--vad-thr", type=float, default=0.5)
    ap.add_argument("--seg-pad", type=float, default=0.0, help="widen segments by this much real audio (capped at mid-gap)")
    ap.add_argument("--ctx", type=float, default=0.0); ap.add_argument("--ctx-tol", type=float, default=0.0)
    ap.add_argument("--tail-zero", type=float, default=0.0); ap.add_argument("--head-zero", type=float, default=0.0)
    ap.add_argument("--blank-penalty", type=float, default=0.0); ap.add_argument("--method", default="greedy_search")
    ap.add_argument("--threads", type=int, default=1); ap.add_argument("--apply-gain", action="store_true")
    ap.add_argument("--dump-segs", default="", help="write the spans as 'start end' lines to DUMP/<name>.segs, no decoding")
    ap.add_argument("names", nargs="+")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = a.dir
    rec = so.OfflineRecognizer.from_transducer(encoder=pick(d, "encoder*.onnx"), decoder=pick(d, "decoder*.onnx", False),
                                               joiner=pick(d, "joiner*.onnx"), tokens=os.path.join(d, "tokens.txt"),
                                               num_threads=a.threads, decoding_method=a.method, blank_penalty=a.blank_penalty)
    for name in a.names:
        dst = os.path.join(a.out, name + ".asr.json")
        if os.path.exists(dst) and not a.dump_segs:
            continue
        app = json.load(open(os.path.join(a.app_dir, name + ".asr.json")))
        audio, sr = sf.read(os.path.join(a.audio_dir, name + ".wav"), dtype="float32")
        assert sr == SR and audio.ndim == 1
        if a.apply_gain:  # wav16/ already holds raw x app gain (clipped); --apply-gain only for raw audio
            audio = np.clip(audio * float(app.get("gain", 1.0)), -1.0, 1.0).astype(np.float32)
        dur = len(audio) / SR
        tl = [(u["start"], u["end"], u["speaker"]) for u in app["utterances"] if u["speaker"] is not None]
        if a.seg == "vad":
            base = vad_segments(audio, a)
        elif a.seg == "fixed":
            base = [(0.0, dur)]   # no endpointer at all: the whole audio, cut by quiet_cut below
        else:
            base = [(u["start"], min(u["end"], dur)) for u in app["utterances"] if u["end"] > u["start"]]
            if a.seg == "merge":
                m = []
                for s, e in base:
                    if m and s - m[-1][1] < a.merge_gap:
                        m[-1] = (m[-1][0], max(m[-1][1], e))
                    else:
                        m.append((s, e))
                base = m
        if a.seg_pad > 0:  # widen each segment by seg_pad, never past the middle of the gap to its neighbour
            padded = []
            for i, (s, e) in enumerate(base):
                lo = (base[i - 1][1] + s) / 2 if i else 0.0
                hi = (e + base[i + 1][0]) / 2 if i + 1 < len(base) else dur
                padded.append((max(lo, s - a.seg_pad), min(hi, e + a.seg_pad)))
            base = padded
        spans = []
        for s, e in base:
            spans += quiet_cut(audio, s, e, a.max_cut)
        if a.dump_segs:
            os.makedirs(a.dump_segs, exist_ok=True)
            with open(os.path.join(a.dump_segs, name + ".segs"), "w") as f:
                for s, e in spans:
                    f.write(f"{s:.3f} {e:.3f}\n")
            continue
        utts, c0, w0 = [], time.process_time(), time.perf_counter()
        for s, e in spans:
            lo, hi = max(0, int((s - a.ctx) * SR)), min(len(audio), int((e + a.ctx) * SR))
            if hi - lo < SR // 10:
                continue
            x = audio[lo:hi]
            if a.tail_zero > 0 or a.head_zero > 0:
                x = np.concatenate([np.zeros(int(a.head_zero * SR), np.float32), x, np.zeros(int(a.tail_zero * SR), np.float32)])
            t0 = time.perf_counter()
            st = rec.create_stream(); st.accept_waveform(SR, x); rec.decode_stream(st)
            dec_ms = 1000 * (time.perf_counter() - t0)
            r = st.result
            if a.ctx > 0:
                off = lo / SR - a.head_zero
                keep = [tok for tok, ts in zip(r.tokens, r.timestamps) if s - a.ctx_tol <= off + ts < e + a.ctx_tol]
                txt = "".join(keep).replace("▁", " ").strip()
                txt = " ".join(txt.split())
            else:
                txt = r.text.strip()
            if txt:
                spk = majority(tl, s, e)
                off = lo / SR - a.head_zero
                toks = [[tok, round(off + ts, 3)] for tok, ts in zip(r.tokens, r.timestamps)]
                utts.append(dict(text=txt, start=round(s, 3), end=round(e, 3), speaker=spk, dec_ms=round(dec_ms, 1), toks=toks))
        cpu, wall = time.process_time() - c0, time.perf_counter() - w0
        for i, u in enumerate(utts):
            u["index"] = i
        json.dump(dict(name=name, kind="xoff_pseudo", seg=a.seg, args=vars(a), audio_sec=dur, wall_sec=wall, cpu_sec=cpu,
                       threads=a.threads, gain=app.get("gain", 1.0), skipped_sec=0, n_spans=len(spans),
                       span_sec=[round(e - s, 3) for s, e in spans], utterances=utts),
                  open(dst, "w"), ensure_ascii=False, indent=0)
        print(f"{name}: {len(spans)} spans, {len(utts)} utts, cpu RTF {cpu / dur:.4f}", flush=True)


if __name__ == "__main__":
    main()
