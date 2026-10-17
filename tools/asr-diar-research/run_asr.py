#!/usr/bin/env python3
"""Run a sherpa-onnx ASR candidate over the eval meetings and write harness-style <name>.asr.json files
(score_asr.py and the reader harness read them unchanged).

Segmentation (--seg):
  stream  online recognizer over the whole meeting, 100 ms pushes, sherpa endpointing splits utterances
  app     two-pass: re-decode each utterance span of the app's own transcript (work/out/<name>.asr.json),
          spans longer than --max-seg cut at the quietest 100 ms frame between max-seg/2 and max-seg;
          the app's speaker labels are kept, so cpER/DER and the reader see the app's diarization
  vad     Silero VAD segments (max --max-seg s); speakers from the app timeline by majority overlap

Audio: work/audio/<name>.f32 (16 kHz mono float), times the app's GainNormalizer gain (read from the app's
asr.json, clamped to +-1), as the app's import path feeds the engine (the SilenceSkipper is not applied).
Timing: wall and process CPU seconds of recognition only (model load excluded). With --threads 1 the CPU
time per audio second is a load-insensitive cost ("core-RTF").

    venv/bin/python -I run_asr.py --kind sense_voice --dir MODEL --audio-dir A --app-dir APP --out OUT \
        --seg app --threads 1 NAME...
"""
import argparse, glob, json, os, sys, time
import numpy as np
import sherpa_onnx as so

SR = 16000


def pick(d, pat, prefer_int8=True):
    c = sorted(glob.glob(os.path.join(d, pat)))
    if prefer_int8:
        i8 = [x for x in c if "int8" in os.path.basename(x)]
        if i8:
            c = i8
    else:
        c = [x for x in c if "int8" not in os.path.basename(x)] or c
    if not c:
        raise FileNotFoundError(os.path.join(d, pat))
    return c[0]


def offline(kind, d, threads, a):
    tok = os.path.join(d, "tokens.txt")
    if kind == "sense_voice":
        return so.OfflineRecognizer.from_sense_voice(model=pick(d, "model*.onnx"), tokens=tok, num_threads=threads,
                                                     use_itn=a.itn, language=a.lang or "auto")
    if kind == "paraformer":
        return so.OfflineRecognizer.from_paraformer(paraformer=pick(d, "model*.onnx"), tokens=tok, num_threads=threads)
    if kind == "transducer":
        return so.OfflineRecognizer.from_transducer(encoder=pick(d, "encoder*.onnx"), decoder=pick(d, "decoder*.onnx", False),
                                                    joiner=pick(d, "joiner*.onnx"), tokens=tok, num_threads=threads,
                                                    decoding_method="greedy_search", model_type=a.model_type or "")
    if kind == "fire_red_asr":
        return so.OfflineRecognizer.from_fire_red_asr(encoder=pick(d, "encoder*.onnx"), decoder=pick(d, "decoder*.onnx"),
                                                      tokens=tok, num_threads=threads)
    if kind == "fire_red_asr_ctc":
        return so.OfflineRecognizer.from_fire_red_asr_ctc(model=pick(d, "model*.onnx"), tokens=tok, num_threads=threads)
    if kind == "dolphin_ctc":
        return so.OfflineRecognizer.from_dolphin_ctc(model=pick(d, "model*.onnx"), tokens=tok, num_threads=threads)
    if kind == "zipformer_ctc":
        return so.OfflineRecognizer.from_zipformer_ctc(model=pick(d, "model*.onnx"), tokens=tok, num_threads=threads)
    if kind == "nemo_ctc":
        return so.OfflineRecognizer.from_nemo_ctc(model=pick(d, "model*.onnx"), tokens=tok, num_threads=threads)
    if kind == "whisper":
        return so.OfflineRecognizer.from_whisper(encoder=pick(d, "*encoder*.onnx"), decoder=pick(d, "*decoder*.onnx"),
                                                 tokens=pick(d, "*tokens.txt", False), num_threads=threads,
                                                 language=a.lang or "", task="transcribe")
    if kind in ("qwen3_asr", "funasr_nano"):
        kw = json.loads(open(a.kwargs[1:]).read() if a.kwargs.startswith("@") else (a.kwargs or "{}"))
        kw = {k: (os.path.join(d, v) if isinstance(v, str) and not v.startswith("/") and os.path.exists(os.path.join(d, v)) else v)
              for k, v in kw.items()}
        fn = getattr(so.OfflineRecognizer, "from_" + kind)
        return fn(num_threads=threads, **kw)
    raise SystemExit("unknown kind " + kind)


def online(kind, d, threads, a):
    tok = os.path.join(d, "tokens.txt")
    if kind == "online_transducer":
        return so.OnlineRecognizer.from_transducer(tokens=tok, encoder=pick(d, "encoder*.onnx"), decoder=pick(d, "decoder*.onnx", False),
                                                   joiner=pick(d, "joiner*.onnx"), num_threads=threads, sample_rate=SR,
                                                   feature_dim=80, decoding_method="greedy_search", enable_endpoint_detection=True,
                                                   rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8,
                                                   rule3_min_utterance_length=a.max_seg, model_type=a.model_type or "")
    if kind == "online_paraformer":
        return so.OnlineRecognizer.from_paraformer(tokens=tok, encoder=pick(d, "encoder*.onnx"), decoder=pick(d, "decoder*.onnx"),
                                                   num_threads=threads, sample_rate=SR, feature_dim=80,
                                                   enable_endpoint_detection=True, rule1_min_trailing_silence=2.4,
                                                   rule2_min_trailing_silence=0.8, rule3_min_utterance_length=a.max_seg)
    if kind == "online_ctc":
        return so.OnlineRecognizer.from_zipformer2_ctc(tokens=tok, model=pick(d, "*.onnx"), num_threads=threads, sample_rate=SR,
                                                       feature_dim=80, enable_endpoint_detection=True,
                                                       rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8,
                                                       rule3_min_utterance_length=a.max_seg)
    raise SystemExit("unknown kind " + kind)


def app_timeline(app_utts):
    return [(u["start"], u["end"], u["speaker"]) for u in app_utts if u["speaker"] is not None]


def majority_speaker(tl, s, e):
    best, bo = None, 0.0
    for a, b, k in tl:
        o = min(b, e) - max(a, s)
        if o > bo:
            best, bo = k, o
    if best is None and tl:  # nearest
        best = min(tl, key=lambda x: min(abs(x[0] - e), abs(x[1] - s)))[2]
    return best


def split_long(audio, s, e, max_seg):
    """Cut [s, e) into pieces <= max_seg at the quietest 100 ms frame of each piece's second half."""
    out = []
    while e - s > max_seg:
        lo, hi = s + max_seg / 2, s + max_seg
        seg = audio[int(lo * SR):int(hi * SR)]
        nfr = max(1, len(seg) // 1600)
        en = [float(np.mean(seg[i * 1600:(i + 1) * 1600] ** 2)) for i in range(nfr)]
        cut = lo + (int(np.argmin(en)) + 0.5) * 0.1
        out.append((s, cut)); s = cut
    out.append((s, e))
    return out


def vad_segments(audio, max_seg, vad_model):
    cfg = so.VadModelConfig()
    cfg.silero_vad.model = vad_model
    cfg.silero_vad.min_silence_duration = 0.3
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = max_seg
    cfg.silero_vad.threshold = 0.4
    cfg.sample_rate = SR
    vad = so.VoiceActivityDetector(cfg, buffer_size_in_seconds=max_seg + 30)
    segs, w = [], 512
    for i in range(0, len(audio), w):
        vad.accept_waveform(audio[i:i + w])
        while not vad.empty():
            f = vad.front; segs.append((f.start / SR, (f.start + len(f.samples)) / SR)); vad.pop()
    vad.flush()
    while not vad.empty():
        f = vad.front; segs.append((f.start / SR, (f.start + len(f.samples)) / SR)); vad.pop()
    return segs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", required=True); ap.add_argument("--dir", required=True)
    ap.add_argument("--audio-dir", required=True); ap.add_argument("--app-dir", required=True)
    ap.add_argument("--out", required=True); ap.add_argument("--seg", default="app")
    ap.add_argument("--threads", type=int, default=1); ap.add_argument("--max-seg", type=float, default=20.0)
    ap.add_argument("--limit-sec", type=float, default=0.0); ap.add_argument("--itn", action="store_true")
    ap.add_argument("--lang", default=""); ap.add_argument("--model-type", default="")
    ap.add_argument("--kwargs", default=""); ap.add_argument("--vad", default="")
    ap.add_argument("--pad", type=float, default=0.2, help="seconds of context added on both sides of an app span")
    ap.add_argument("names", nargs="+")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    is_online = a.kind.startswith("online_")
    t0 = time.perf_counter()
    rec = online(a.kind, a.dir, a.threads, a) if is_online else offline(a.kind, a.dir, a.threads, a)
    load_sec = time.perf_counter() - t0
    for name in a.names:
        dst = os.path.join(a.out, name + ".asr.json")
        if os.path.exists(dst):
            continue
        app = json.load(open(os.path.join(a.app_dir, name + ".asr.json")))
        f32 = os.path.join(a.audio_dir, name + ".f32")
        if os.path.exists(f32):
            audio = np.fromfile(f32, dtype=np.float32)
        else:  # 16 kHz mono FLAC/WAV of the same audio (tools/asr-diar-research/fetch_eval_audio.sh)
            import soundfile as sf
            audio, sr = sf.read(os.path.join(a.audio_dir, name + ".flac"), dtype="float32")
            assert sr == SR and audio.ndim == 1, (sr, audio.shape)
        if a.limit_sec > 0:
            audio = audio[:int(a.limit_sec * SR)]
        audio = np.clip(audio * float(app.get("gain", 1.0)), -1.0, 1.0).astype(np.float32)
        dur = len(audio) / SR
        tl = app_timeline(app["utterances"])
        utts = []
        c0, w0 = time.process_time(), time.perf_counter()
        if is_online:
            st = rec.create_stream(); seg_start = 0.0; pos = 0; step = SR // 10
            def flush(end_t):
                txt = rec.get_result(st).strip()
                if txt:
                    utts.append(dict(text=txt, start=round(seg_start, 2), end=round(end_t, 2)))
            while pos < len(audio):
                st.accept_waveform(SR, audio[pos:pos + step]); pos += step
                while rec.is_ready(st):
                    rec.decode_stream(st)
                if rec.is_endpoint(st):
                    flush(pos / SR); rec.reset(st); seg_start = pos / SR
            st.accept_waveform(SR, np.zeros(int(2.5 * SR), np.float32)); st.input_finished()
            while rec.is_ready(st):
                rec.decode_stream(st)
            flush(len(audio) / SR)
        else:
            if a.seg == "app":
                spans = []
                for u in app["utterances"]:
                    if a.limit_sec > 0 and u["start"] >= a.limit_sec:
                        continue
                    for s, e in split_long(audio, u["start"], min(u["end"], dur), a.max_seg):
                        spans.append((s, e, u["speaker"]))
            else:
                spans = [(s, e, None) for s, e in vad_segments(audio, a.max_seg, a.vad)]
            for s, e, spk in spans:
                lo, hi = max(0, int((s - a.pad) * SR)), min(len(audio), int((e + a.pad) * SR))
                if hi - lo < SR // 10:
                    continue
                st = rec.create_stream(); st.accept_waveform(SR, audio[lo:hi]); rec.decode_stream(st)
                txt = st.result.text.strip()
                if txt:
                    utts.append(dict(text=txt, start=round(s, 2), end=round(e, 2), speaker=spk))
        cpu, wall = time.process_time() - c0, time.perf_counter() - w0
        for i, u in enumerate(utts):
            u["index"] = i
            if "speaker" not in u or (u["speaker"] is None and a.seg != "app"):
                u["speaker"] = majority_speaker(tl, u["start"], u["end"])
        json.dump(dict(name=name, kind=a.kind, model=os.path.basename(a.dir.rstrip("/")), seg="stream" if is_online else a.seg,
                       audio_sec=dur, wall_sec=wall, cpu_sec=cpu, load_sec=load_sec, threads=a.threads, gain=app.get("gain", 1.0),
                       skipped_sec=0, loadavg=os.getloadavg()[0], utterances=utts),
                  open(dst, "w"), ensure_ascii=False, indent=0)
        print(f"{name}: {len(utts)} utts, wall RTF {wall / dur:.3f}, cpu RTF {cpu / dur:.3f}, load {os.getloadavg()[0]:.1f}", flush=True)


if __name__ == "__main__":
    main()
