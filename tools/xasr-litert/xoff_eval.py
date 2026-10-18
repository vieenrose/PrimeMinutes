#!/usr/bin/env python3
"""Host decoder for the X-ASR offline transducer with a selectable encoder backend, same segmentation/output as
pseudo_stream.py (fixed / app / merge), to compare LiteRT exports against sherpa-onnx.
  --enc ort:<onnx> | torch | tfl:<tflite>   (tflite has a fixed T: features are zero-padded, x_lens = real length)
Decoder + joiner: torch (fp32 weights reconstructed from the sherpa ONNX), greedy, blank penalty, 1 symbol/frame.
Fbank: kaldi_native_fbank with sherpa-onnx defaults (80 bins, dither 0, snip_edges false, high_freq -400, samples in [-1,1])."""
import argparse, json, os, re, sys, time
import numpy as np, soundfile as sf, torch, kaldi_native_fbank as knf
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SR = 16000
TOK = os.environ.get("XTOK", "/home/luigi/voxsum-models/asr-research/models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03/tokens.txt")

def fbank(x):
    o = knf.FbankOptions(); o.frame_opts.dither = 0; o.frame_opts.snip_edges = False; o.frame_opts.samp_freq = SR
    o.mel_opts.num_bins = 80; o.mel_opts.high_freq = -400; o.mel_opts.low_freq = 20
    f = knf.OnlineFbank(o); f.accept_waveform(SR, x.tolist()); f.input_finished()
    return np.stack([f.get_frame(i) for i in range(f.num_frames_ready)]).astype(np.float32)

class Enc:
    def __init__(s, spec, threads):
        s.kind, _, p = spec.partition(":")
        if s.kind == "ort":
            import onnxruntime as ort
            so = ort.SessionOptions(); so.intra_op_num_threads = threads
            s.sess = ort.InferenceSession(p, so, providers=["CPUExecutionProvider"])
        elif s.kind == "torch":
            import xoff_torch as XT; s.m = XT.OffEnc(XT.load()).eval()
        else:
            from ai_edge_litert.interpreter import Interpreter
            s.it = Interpreter(model_path=p, num_threads=threads); s.sigs = {}
            for k in s.it.get_signature_list():
                r = s.it.get_signature_runner(k); T = int(k[3:]); s.sigs[T] = r
            s.Ts = sorted(s.sigs)
    def __call__(s, F):
        T = len(F)
        if s.kind == "ort":
            return s.sess.run(None, {"x": F[None], "x_lens": np.array([T], np.int64 if "int64" in s.sess.get_inputs()[1].type else np.int32)})[0][0]
        if s.kind == "torch":
            with torch.no_grad(): return s.m(torch.from_numpy(F[None]), torch.tensor([T]))[0][0].numpy()
        Tm = next(t for t in s.Ts if t >= T); X = np.zeros((1, Tm, 80), np.float32); X[0, :T] = F
        r = s.sigs[Tm]; o = r(args_0=X, args_1=np.array([T], np.int32))
        y = [v for v in o.values() if v.ndim == 3][0][0]
        n = ((T - 7) // 2 + 1) // 2
        return y[:n]

class DJ:
    def __init__(s):
        import xoff_torch as XT; m = XT.load(); s.dec, s.j = m.decoder, m.joiner
        s.tok = [l.rsplit(" ", 1)[0] if len(l.split()) > 1 else " " for l in open(TOK, encoding="utf-8").read().split("\n") if l]
    @torch.no_grad()
    def greedy(s, E, bp):
        ctx = [0, 0]; out = []
        d = s.j.decoder_proj(s.dec(torch.tensor([ctx]), need_pad=False))[:, 0]
        Et = torch.from_numpy(np.ascontiguousarray(E))
        for t in range(len(Et)):
            lg = s.j.output_linear(torch.tanh(Et[t:t + 1] + d))[0]
            if bp: lg[0] -= bp
            y = int(lg.argmax())
            if y != 0:
                out.append((y, t)); ctx = ctx[1:] + [y]
                d = s.j.decoder_proj(s.dec(torch.tensor([ctx]), need_pad=False))[:, 0]
        return out

def quiet_cut(audio, s, e, max_cut):
    out = []
    while e - s > max_cut:
        lo, hi = s + max_cut / 2, s + max_cut
        seg = audio[int(lo * SR):int(hi * SR)]; nfr = max(1, len(seg) // 1600)
        en = [float(np.mean(seg[i * 1600:(i + 1) * 1600] ** 2)) for i in range(nfr)]
        cut = lo + (int(np.argmin(en)) + 0.5) * 0.1; out.append((s, cut)); s = cut
    out.append((s, e)); return out

def majority(tl, s, e):
    best, bo = None, 0.0
    for a, b, k in tl:
        o = min(b, e) - max(a, s)
        if o > bo: best, bo = k, o
    if best is None and tl: best = min(tl, key=lambda x: min(abs(x[0] - e), abs(x[1] - s)))[2]
    return best

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--enc", required=True); ap.add_argument("--audio-dir", required=True); ap.add_argument("--app-dir", required=True)
    ap.add_argument("--out", required=True); ap.add_argument("--seg", default="fixed", choices=["fixed", "app"])
    ap.add_argument("--max-cut", type=float, default=20.0); ap.add_argument("--blank-penalty", type=float, default=1.0)
    ap.add_argument("--threads", type=int, default=1); ap.add_argument("--limit", type=float, default=0)
    ap.add_argument("names", nargs="+"); a = ap.parse_args()
    torch.set_num_threads(a.threads); os.makedirs(a.out, exist_ok=True)
    enc, dj = Enc(a.enc, a.threads), DJ()
    for name in a.names:
        dst = os.path.join(a.out, name + ".asr.json")
        if os.path.exists(dst): continue
        app = json.load(open(os.path.join(a.app_dir, name + ".asr.json")))
        audio, sr = sf.read(os.path.join(a.audio_dir, name + ".wav"), dtype="float32"); assert sr == SR
        if a.limit: audio = audio[:int(a.limit * SR)]
        dur = len(audio) / SR
        tl = [(u["start"], u["end"], u["speaker"]) for u in app["utterances"] if u["speaker"] is not None]
        base = [(0.0, dur)] if a.seg == "fixed" else [(u["start"], min(u["end"], dur)) for u in app["utterances"] if u["end"] > u["start"]]
        spans = [sp for s, e in base for sp in quiet_cut(audio, s, e, a.max_cut)]
        utts, w0 = [], time.perf_counter()
        for s, e in spans:
            lo, hi = int(s * SR), min(len(audio), int(e * SR))
            if hi - lo < SR // 10: continue
            t0 = time.perf_counter(); F = fbank(audio[lo:hi]); E = enc(F); hyp = dj.greedy(E, a.blank_penalty)
            dec_ms = 1000 * (time.perf_counter() - t0)
            txt = " ".join("".join(dj.tok[y] for y, _ in hyp).replace("▁", " ").split())
            txt = re.sub(r"(?<=[\u3400-\u9fff]) (?=[\u3400-\u9fff])", "", txt)
            if txt:
                toks = [[dj.tok[y], round(s + t * 0.04, 3)] for y, t in hyp]
                utts.append(dict(text=txt, start=round(s, 3), end=round(e, 3), speaker=majority(tl, s, e), dec_ms=round(dec_ms, 1), toks=toks))
        for i, u in enumerate(utts): u["index"] = i
        json.dump(dict(name=name, kind="xoff_eval", enc=a.enc, args=vars(a), audio_sec=dur, wall_sec=time.perf_counter() - w0,
                       gain=app.get("gain", 1.0), skipped_sec=0, n_spans=len(spans), utterances=utts), open(dst, "w"), ensure_ascii=False, indent=0)
        print(name, len(spans), "spans", len(utts), "utts", f"{time.perf_counter() - w0:.0f}s", flush=True)

if __name__ == "__main__":
    main()
