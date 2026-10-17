#!/usr/bin/env python3
"""Summarize pseudo_bench JSON outputs (Note10+): memory, decode time vs utterance length, display delay, backlog.
    python3 -I pb_summary.py out/*.json
utts rows are [start, end, decode_s, delay_s (paced: decode done - utterance end, audio clock; else -1), chars, queue].
"""
import json, os, sys


def pct(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(round(p / 100 * (len(v) - 1))))] if v else float("nan")


BINS = [(0, 2), (2, 5), (5, 10), (10, 15), (15, 20.5)]
for f in sys.argv[1:]:
    d = json.load(open(f)); tag = os.path.basename(f)[:-5]
    u = d["utts"]
    hwm = d["vmhwm_kb"] / 1024; load_hwm = d["hwm_after_load_kb"] / 1024
    line = (f"{tag:12} audio {d['audio_sec']:6.0f}s eng {d['engine']:4} xoff {d['xoff']} | VmHWM {hwm:6.0f} MB (after load {load_hwm:5.0f}, "
            f"rss0 {d['rss0_kb']/1024:4.0f}) load {d['load_s']:5.1f}s")
    if d.get("eng_asr_s") is not None:
        line += f" | eng asr {d['eng_asr_s']:6.1f}s diar {d['eng_diar_s']:6.1f}s feed {d['feed_wall_s']:6.1f}s finish {d['finish_s']:4.1f}s"
    print(line)
    if not d["xoff"] or not u:
        continue
    dec = [r[2] for r in u if r[1] - r[0] >= 0.1]; aud = sum(r[1] - r[0] for r in u)
    print(f"{'':12} xoff: {len(u)} utts, {aud:.0f}s speech, decode sum {sum(dec):.1f}s -> RTF/speech {sum(dec)/aud:.3f}, "
          f"RTF/audio {sum(dec)/d['audio_sec']:.3f}; per-utt decode p50 {pct(dec,50):.2f}s p95 {pct(dec,95):.2f}s max {max(dec):.2f}s")
    for lo, hi in BINS:
        rows = [r for r in u if lo <= r[1] - r[0] < hi]
        if rows:
            rt = sum(r[2] for r in rows) / sum(r[1] - r[0] for r in rows)
            print(f"{'':14} len {lo:4.1f}-{hi:4.1f}s n={len(rows):3} decode p50 {pct([r[2] for r in rows],50):.2f}s "
                  f"p95 {pct([r[2] for r in rows],95):.2f}s  RTF {rt:.3f}")
    if d["paced"]:
        dl = [r[3] for r in u]
        print(f"{'':12} display delay after end of utterance (incl. {d['avail_delay']}s endpoint wait): p50 {pct(dl,50):.2f}s "
              f"p95 {pct(dl,95):.2f}s max {max(dl):.2f}s; max queue {d['max_queue']}, max backlog {d['max_backlog_audio_s']:.1f}s, "
              f"drain after feed {d['drain_after_feed_s']:.1f}s")
        wl = []  # speech-time-weighted latency of a word spoken at t: (end - t) + delay, sampled every 0.1 s
        for r in u:
            t = r[0]
            while t < r[1]:
                wl.append(r[1] - t + r[3]); t += 0.1
        print(f"{'':12} word-level latency (speech spoken -> offline text shown): p50 {pct(wl,50):.1f}s p95 {pct(wl,95):.1f}s")
        bl = d["backlog"]
        if bl:
            q = len(bl) // 4 or 1
            print(f"{'':12} backlog (s of speech waiting) by quarter: " +
                  " ".join(f"{max(b for _, b in bl[i:i+q]):.1f}" for i in range(0, len(bl), q)))
