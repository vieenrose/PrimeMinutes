#include "nemo_c.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "engine.h"

struct nemo_handle {
    std::unique_ptr<nemo::Engine> engine;
    std::mutex mu;
};

namespace {
std::string encode_segment(const nemo::Segment& s) {
    char head[96];
    std::snprintf(head, sizeof head, "%d\x1f%.3f\x1f%.3f\x1f", s.speaker, s.start_s, s.end_s);
    return std::string(head) + s.text + "\x1e";
}
char* dup(const std::string& s) {
    char* p = static_cast<char*>(std::malloc(s.size() + 1));
    std::memcpy(p, s.c_str(), s.size() + 1);
    return p;
}
}  // namespace

extern "C" {

nemo_handle* nemo_create(const char* xasr, const char* diar, int threads, double settle_s) {
    return nemo_create_ex(xasr, diar, threads, settle_s, "ggml", nullptr, nullptr, nullptr);
}

nemo_handle* nemo_create_ex(const char* xasr, const char* diar, int threads, double settle_s,
                            const char* backend, const char* xasr_tflite, const char* diar_tflite,
                            const char* diar_pre_f32) {
    nemo::Config cfg;
    cfg.backend = backend ? backend : "ggml";
    cfg.xasr_tflite = xasr_tflite ? xasr_tflite : "";
    cfg.diar_tflite = diar_tflite ? diar_tflite : "";
    cfg.diar_pre_f32 = diar_pre_f32 ? diar_pre_f32 : "";
    cfg.xasr_model = xasr;
    cfg.diar_model = diar;
    cfg.threads = threads;
    cfg.max_segment_s = 10.0;   // one transcript line per ~10 s sentence group (as on Android)
    cfg.live_settle_s = settle_s;
    // audio.cpp reserves 1 GiB per ggml context by default (graph arena + weights); a 3 GB iPhone refuses the malloc.
    cfg.diar_session_opts.push_back({"nemotron_3_diar.graph_arena_mb", "128"});
    cfg.diar_session_opts.push_back({"nemotron_3_diar.weight_context_mb", "128"});
    auto h = std::make_unique<nemo_handle>();
    h->engine = std::make_unique<nemo::Engine>(cfg);
    std::string err;
    if (!h->engine->init(err)) {
        std::fprintf(stderr, "primeminutes-nemo: init failed: %s\n", err.c_str());
        return nullptr;
    }
    h->engine->begin();
    return h.release();
}

int nemo_push(nemo_handle* h, const float* pcm, int n) {
    std::string err;
    bool ok;
    // A C++ exception must not reach Swift (it aborts the app and the live capture): the LiteRT graphs
    // throw on a runtime error, so a failed piece is reported like any other.
    try {
        std::lock_guard<std::mutex> lk(h->mu);
        ok = h->engine->push(pcm, static_cast<size_t>(n), err);
    } catch (const std::exception& e) { ok = false; err = e.what(); }
    if (!ok) std::fprintf(stderr, "primeminutes-nemo: push failed: %s\n", err.c_str());
    return ok ? 1 : 0;
}

char* nemo_live(nemo_handle* h) {
    std::vector<nemo::Segment> frozen, tail;
    try {
        std::lock_guard<std::mutex> lk(h->mu);
        h->engine->live(frozen, tail);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "primeminutes-nemo: live failed: %s\n", e.what());
        frozen.clear(); tail.clear();
    }
    std::string out;
    for (const auto& s : frozen) out += encode_segment(s);
    out += "\x1d";
    for (const auto& s : tail) out += encode_segment(s);
    return dup(out);
}

char* nemo_finish(nemo_handle* h) {
    std::string out, err;
    std::lock_guard<std::mutex> lk(h->mu);
    bool ok;
    try { ok = h->engine->finish([&](const nemo::Segment& s) { out += encode_segment(s); }, err); }
    catch (const std::exception& e) { ok = false; err = e.what(); }
    if (!ok) {
        std::fprintf(stderr, "primeminutes-nemo: finish failed: %s\n", err.c_str());
        return nullptr;
    }
    const auto& st = h->engine->stats();
    std::fprintf(stderr, "primeminutes-nemo: audio %.1fs wall %.1fs rtf %.3f speakers %zu turns %zu\n", st.audio_s, st.wall_s,
                 st.audio_s > 0 ? st.wall_s / st.audio_s : 0.0, st.speakers, st.turns);
    return dup(out);
}

double nemo_fed_seconds(nemo_handle* h) {
    std::lock_guard<std::mutex> lk(h->mu);
    return h->engine->fed_s();
}

void nemo_string_free(char* s) { std::free(s); }
void nemo_free(nemo_handle* h) { delete h; }

}  // extern "C"
