// pseudo_bench - phone cost of X-ASR OFFLINE pseudo-streaming (docs/research/xasr-offline-pseudostream.md).
// One process, as the app would hold it: optionally the app's own engine (nemo::Engine, ggml-free LiteRT build:
// streaming X-ASR + diarizer, or the diarizer alone), fed in 2048-sample blocks at 1x wall clock (or unpaced), and
// a worker thread that decodes each utterance with the sherpa-onnx offline transducer as soon as the audio clock
// passes its end + --avail-delay (the endpointer's own wait). Prints one JSON object: per-utterance decode time and
// display delay (decode done - utterance end, audio clock), queue backlog, VmHWM / VmRSS samples, engine stats.
//
//   pseudo_bench --audio a.f32 --gain G --segs segs.txt --out out.json
//                [--xoff DIR --xthreads 2 --tail-zero 0.0 --blank-penalty 0]   (no --xoff: no offline ASR)
//                [--engine none|diar|asr|full --ethreads 2 --xasr-host F --xasr-tflite F --diar-host F --diar-tflite F
//                 --diar-pre F] [--paced 1] [--avail-delay 0.5] [--limit-sec S] [--live 1]
// segs.txt: one "start end" (seconds) per line, in time order.
#include <sherpa-onnx/c-api/c-api.h>
#include "engine.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

static double now() { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
static long status_kb(const char* key) {
    std::ifstream f("/proc/self/status"); std::string l;
    while (std::getline(f, l)) if (l.rfind(key, 0) == 0) return std::atol(l.c_str() + std::strlen(key));
    return -1;
}

struct Seg { double s, e; };
struct Rec { double s, e, avail_audio, start_wall, done_wall, dec_s; int chars; size_t queue_at_start; };

int main(int argc, char** argv) {
    std::string audio_p, segs_p, out_p, xoff, engine_mode = "none";
    std::string xasr_host, xasr_tflite, diar_host, diar_tflite, diar_pre;
    float gain = 1.f, tail_zero = 0.f, blank_penalty = 0.f; int xthreads = 2, ethreads = 2; bool paced = true, live = true;
    double avail_delay = 0.5, limit_sec = 0;
    for (int i = 1; i + 1 < argc; i += 2) {
        std::string k = argv[i]; const char* v = argv[i + 1];
        if (k == "--audio") audio_p = v; else if (k == "--gain") gain = std::atof(v); else if (k == "--segs") segs_p = v;
        else if (k == "--out") out_p = v; else if (k == "--xoff") xoff = v; else if (k == "--xthreads") xthreads = std::atoi(v);
        else if (k == "--tail-zero") tail_zero = std::atof(v); else if (k == "--blank-penalty") blank_penalty = std::atof(v);
        else if (k == "--engine") engine_mode = v; else if (k == "--ethreads") ethreads = std::atoi(v);
        else if (k == "--xasr-host") xasr_host = v; else if (k == "--xasr-tflite") xasr_tflite = v;
        else if (k == "--diar-host") diar_host = v; else if (k == "--diar-tflite") diar_tflite = v; else if (k == "--diar-pre") diar_pre = v;
        else if (k == "--paced") paced = std::atoi(v) != 0; else if (k == "--avail-delay") avail_delay = std::atof(v);
        else if (k == "--limit-sec") limit_sec = std::atof(v); else if (k == "--live") live = std::atoi(v) != 0;
        else { std::fprintf(stderr, "unknown %s\n", k.c_str()); return 2; }
    }
    // audio
    FILE* f = std::fopen(audio_p.c_str(), "rb"); if (!f) { std::perror("audio"); return 1; }
    std::fseek(f, 0, SEEK_END); long n = std::ftell(f) / 4; std::fseek(f, 0, SEEK_SET);
    std::vector<float> pcm((size_t)n); if (std::fread(pcm.data(), 4, (size_t)n, f) != (size_t)n) return 1; std::fclose(f);
    if (limit_sec > 0) { n = std::min<long>(n, (long)(limit_sec * 16000)); pcm.resize((size_t)n); }
    for (auto& x : pcm) x = std::max(-1.f, std::min(1.f, x * gain));
    const double audio_s = n / 16000.0;
    std::vector<Seg> segs;
    { std::ifstream sf(segs_p); double s, e; while (sf >> s >> e) if (s < audio_s) segs.push_back({s, std::min(e, audio_s)}); }

    const long rss0 = status_kb("VmRSS:");
    const double t_load0 = now();
    // offline recognizer
    const SherpaOnnxOfflineRecognizer* rec = nullptr;
    std::string enc, dec, joi, tok;
    if (!xoff.empty()) {
        enc = xoff + "/encoder-epoch-99-avg-1.int8.onnx"; dec = xoff + "/decoder-epoch-99-avg-1.onnx";
        joi = xoff + "/joiner-epoch-99-avg-1.int8.onnx"; tok = xoff + "/tokens.txt";
        SherpaOnnxOfflineRecognizerConfig c; std::memset(&c, 0, sizeof(c));
        c.feat_config.sample_rate = 16000; c.feat_config.feature_dim = 80;
        c.model_config.transducer.encoder = enc.c_str(); c.model_config.transducer.decoder = dec.c_str();
        c.model_config.transducer.joiner = joi.c_str(); c.model_config.tokens = tok.c_str();
        c.model_config.num_threads = xthreads; c.model_config.provider = "cpu"; c.model_config.model_type = "transducer";
        c.decoding_method = "greedy_search"; c.blank_penalty = blank_penalty;
        rec = SherpaOnnxCreateOfflineRecognizer(&c);
        if (!rec) { std::fprintf(stderr, "offline recognizer failed\n"); return 1; }
    }
    const double t_xload = now() - t_load0; const long hwm_xload = status_kb("VmHWM:");
    // the app engine
    std::unique_ptr<nemo::Engine> eng;
    if (engine_mode != "none") {
        nemo::Config cfg;
        cfg.backend = "litert_host"; cfg.xasr_model = xasr_host; cfg.diar_model = diar_host;
        cfg.xasr_tflite = xasr_tflite; cfg.diar_tflite = diar_tflite; cfg.diar_pre_f32 = diar_pre;
        cfg.threads = ethreads; cfg.max_segment_s = 10.0; cfg.live_settle_s = 15.0;
        cfg.skip_asr = engine_mode == "diar"; cfg.skip_diar = engine_mode == "asr";
        eng.reset(new nemo::Engine(cfg));
        std::string err; if (!eng->init(err)) { std::fprintf(stderr, "engine init: %s\n", err.c_str()); return 1; }
        eng->begin();
    }
    const double t_load = now() - t_load0; const long hwm_load = status_kb("VmHWM:");

    // worker
    std::mutex mu; std::condition_variable cv; std::deque<size_t> q; bool done_feeding = false;
    std::vector<Rec> recs; recs.reserve(segs.size());
    std::atomic<double> t0{0};
    size_t max_queue = 0; double max_backlog_audio = 0;
    std::vector<std::pair<double, double>> backlog_log;   // (audio clock, pending audio seconds incl. the one decoding)
    auto clock_audio = [&](double w) { return w - t0.load(); };   // paced: wall since start == audio clock
    std::thread worker;
    if (rec) worker = std::thread([&] {
        for (;;) {
            size_t i; size_t qs;
            { std::unique_lock<std::mutex> lk(mu); cv.wait(lk, [&] { return !q.empty() || done_feeding; });
              if (q.empty()) return; i = q.front(); q.pop_front(); qs = q.size(); }
            const Seg& sg = segs[i];
            const long lo = (long)(sg.s * 16000), hi = std::min<long>(n, (long)(sg.e * 16000));
            if (hi - lo < 1600) { recs.push_back({sg.s, sg.e, sg.e + avail_delay, now(), now(), 0, 0, qs}); continue; }
            std::vector<float> x(pcm.begin() + lo, pcm.begin() + hi); x.resize(x.size() + (size_t)(tail_zero * 16000), 0.f);
            const double w0 = now();
            const SherpaOnnxOfflineStream* st = SherpaOnnxCreateOfflineStream(rec);
            SherpaOnnxAcceptWaveformOffline(st, 16000, x.data(), (int32_t)x.size());
            SherpaOnnxDecodeOfflineStream(rec, st);
            const SherpaOnnxOfflineRecognizerResult* r = SherpaOnnxGetOfflineStreamResult(st);
            const int chars = r && r->text ? (int)std::strlen(r->text) : 0;
            SherpaOnnxDestroyOfflineRecognizerResult(r); SherpaOnnxDestroyOfflineStream(st);
            const double w1 = now();
            recs.push_back({sg.s, sg.e, sg.e + avail_delay, w0, w1, w1 - w0, chars, qs});
        }
    });

    // feeder
    std::vector<std::pair<double, long>> rss_log;
    std::vector<double> push_ms;
    size_t next_seg = 0; double next_live = 0.5, next_rss = 0;
    const double feed0 = now(); t0 = feed0;
    for (long off = 0; off < n; off += 2048) {
        const long m = std::min<long>(2048, n - off);
        const double fed = (off + m) / 16000.0;
        if (paced) { const double due = feed0 + fed; while (now() < due) std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
        if (eng) {
            const double p0 = now(); std::string err;
            if (!eng->push(pcm.data() + off, (size_t)m, err)) { std::fprintf(stderr, "push: %s\n", err.c_str()); return 1; }
            if (live && eng->fed_s() >= next_live) { next_live = eng->fed_s() + 0.5; std::vector<nemo::Segment> nf, tail; eng->live(nf, tail); }
            push_ms.push_back(1000 * (now() - p0));
        }
        if (rec) {
            std::lock_guard<std::mutex> lk(mu);
            while (next_seg < segs.size() && segs[next_seg].e + avail_delay <= fed) { q.push_back(next_seg++); }
            double pend = 0; for (size_t j : q) pend += segs[j].e - segs[j].s;
            max_queue = std::max(max_queue, q.size()); max_backlog_audio = std::max(max_backlog_audio, pend);
            if (backlog_log.empty() || fed - backlog_log.back().first >= 5) backlog_log.push_back({fed, pend});
            cv.notify_one();
        }
        if (fed >= next_rss) { rss_log.push_back({fed, status_kb("VmRSS:")}); next_rss = fed + 10; }
    }
    const double feed_end = now();
    { std::lock_guard<std::mutex> lk(mu); while (next_seg < segs.size()) q.push_back(next_seg++); done_feeding = true; cv.notify_all(); }
    double fin_s = 0;
    if (eng) { const double a = now(); std::string err; size_t ns = 0; eng->finish([&](const nemo::Segment&) { ns++; }, err); fin_s = now() - a; }
    if (worker.joinable()) worker.join();
    const double all_end = now();

    // output
    FILE* o = std::fopen(out_p.c_str(), "w");
    std::fprintf(o, "{\"audio_sec\":%.2f,\"paced\":%d,\"engine\":\"%s\",\"xoff\":%d,\"xthreads\":%d,\"ethreads\":%d,\"tail_zero\":%.2f,"
                    "\"avail_delay\":%.2f,\"rss0_kb\":%ld,\"xload_s\":%.2f,\"hwm_after_xload_kb\":%ld,\"load_s\":%.2f,\"hwm_after_load_kb\":%ld,"
                    "\"feed_wall_s\":%.2f,\"finish_s\":%.2f,\"drain_after_feed_s\":%.2f,\"vmhwm_kb\":%ld,\"max_queue\":%zu,\"max_backlog_audio_s\":%.2f",
                 audio_s, (int)paced, engine_mode.c_str(), rec ? 1 : 0, xthreads, ethreads, tail_zero, avail_delay, rss0, t_xload, hwm_xload,
                 t_load, hwm_load, feed_end - feed0, fin_s, all_end - feed_end, status_kb("VmHWM:"), max_queue, max_backlog_audio);
    if (eng) { const auto& s = eng->stats(); std::fprintf(o, ",\"eng_asr_s\":%.2f,\"eng_diar_s\":%.2f,\"eng_peak_rss_mb\":%.1f", s.asr_compute_s, s.diar_compute_s, s.peak_rss_mb); }
    if (!push_ms.empty()) { auto p = push_ms; std::sort(p.begin(), p.end());
        std::fprintf(o, ",\"push_ms_p50\":%.1f,\"push_ms_p95\":%.1f,\"push_ms_max\":%.1f", p[p.size() / 2], p[p.size() * 95 / 100], p.back()); }
    std::fprintf(o, ",\"rss\":["); for (size_t i = 0; i < rss_log.size(); i++) std::fprintf(o, "%s[%.0f,%ld]", i ? "," : "", rss_log[i].first, rss_log[i].second);
    std::fprintf(o, "],\"backlog\":["); for (size_t i = 0; i < backlog_log.size(); i++) std::fprintf(o, "%s[%.0f,%.1f]", i ? "," : "", backlog_log[i].first, backlog_log[i].second);
    std::fprintf(o, "],\"utts\":[");
    for (size_t i = 0; i < recs.size(); i++) {
        const Rec& r = recs[i];
        const double done_audio = paced ? clock_audio(r.done_wall) : -1;
        std::fprintf(o, "%s[%.2f,%.2f,%.4f,%.3f,%d,%zu]", i ? "," : "", r.s, r.e, r.dec_s, paced ? done_audio - r.e : -1.0, r.chars, r.queue_at_start);
    }
    std::fprintf(o, "]}\n"); std::fclose(o);
    std::printf("done audio %.0f s, utts %zu, VmHWM %ld MB, max backlog %.1f s, drain %.1f s\n", audio_s, recs.size(),
                status_kb("VmHWM:") / 1024, max_backlog_audio, all_end - feed_end);
    if (rec) SherpaOnnxDestroyOfflineRecognizer(rec);
    return 0;
}
