// xasr_host.cc - X-ASR without ggml: the host half of the streaming transducer (Kaldi fbank, stateless
// decoder, joiner, sherpa-onnx greedy search, detokenizer, stream bookkeeping) ported 1:1 from CrispASR's
// xasr.cpp, serving the same xasr_* C API (hostxasr/xasr.h). The encoder is the LiteRT graph, plugged in with
// xasr_set_external_chunk_encoder; the weights it needs here come from xasr_host.bin
// (tools/nemo-host/xasr_host_pack.py), not from the GGUF. Compiled instead of CrispASR in the ggml-free build.
#include "xasr.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "kaldi_fbank.h"

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace {

struct Model {
    int vocab = 0, blank_id = 0, unk_id = -1, D = 512, J = 512, Kc = 2, F = 80;
    std::vector<std::string> tokens;
    std::vector<float> dec_emb, dec_conv, dec_proj_w, dec_proj_b, join_out_b;
    std::vector<uint16_t> join_out_w;   // fp16, [V, J]
};

float half_to_float(uint16_t h) {
    const uint32_t s = (uint32_t)(h & 0x8000) << 16;
    uint32_t e = (h >> 10) & 0x1f, m = h & 0x3ff, o;
    if (e == 0) {
        if (m == 0) o = s;
        else { e = 113; while (!(m & 0x400)) { m <<= 1; e--; } m &= 0x3ff; o = s | (e << 23) | (m << 13); }
    } else if (e == 31) o = s | 0x7f800000 | (m << 13);
    else o = s | ((e + 112) << 23) | (m << 13);
    float f; std::memcpy(&f, &o, 4); return f;
}

bool read_all(FILE* f, void* p, size_t n) { return fread(p, 1, n, f) == n; }
bool read_vec(FILE* f, std::vector<float>& v) {
    uint64_t n; if (!read_all(f, &n, 8)) return false;
    v.resize(n); return read_all(f, v.data(), n * 4);
}

bool load(Model& m, const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    bool ok = false;
    do {
        char magic[4]; int32_t h[7]; uint32_t nv;
        if (!read_all(f, magic, 4) || memcmp(magic, "XAH1", 4) != 0 || !read_all(f, h, sizeof h) || !read_all(f, &nv, 4)) break;
        m.vocab = h[0]; m.blank_id = h[1]; m.unk_id = h[2]; m.D = h[3]; m.J = h[4]; m.Kc = h[5]; m.F = h[6];
        if ((int)nv != m.vocab) break;
        m.tokens.resize(nv);
        bool bad = false;
        for (auto& t : m.tokens) { uint32_t l; if (!read_all(f, &l, 4)) { bad = true; break; } t.resize(l); if (l && !read_all(f, &t[0], l)) { bad = true; break; } }
        if (bad || !read_vec(f, m.dec_emb) || !read_vec(f, m.dec_conv) || !read_vec(f, m.dec_proj_w) || !read_vec(f, m.dec_proj_b)) break;
        uint64_t n; if (!read_all(f, &n, 8)) break;
        m.join_out_w.resize(n); if (!read_all(f, m.join_out_w.data(), n * 2)) break;
        if (!read_vec(f, m.join_out_b)) break;
        ok = m.dec_emb.size() == (size_t)m.vocab * m.D && m.join_out_w.size() == (size_t)m.vocab * m.J &&
             m.dec_proj_w.size() == (size_t)m.J * m.D;
    } while (0);
    fclose(f);
    return ok;
}

// decoder_proj(relu(grouped_conv(embedding(ctx tokens)))) for the last Kc tokens.
std::vector<float> decoder_out(const Model& m, const std::vector<int>& hyp) {
    const int D = m.D, Kc = m.Kc, gs = (int)(m.dec_conv.size() / ((size_t)D * Kc));
    std::vector<float> e((size_t)Kc * D, 0.0f);
    for (int k = 0; k < Kc; k++) {
        const int tok = hyp[hyp.size() - (size_t)Kc + (size_t)k];
        if (tok >= 0) std::memcpy(e.data() + (size_t)k * D, m.dec_emb.data() + (size_t)tok * D, (size_t)D * sizeof(float));
    }
    std::vector<float> h((size_t)D);
    for (int o = 0; o < D; o++) {
        const int g0 = (o / gs) * gs;
        float acc = 0.0f;
        for (int i = 0; i < gs; i++)
            for (int k = 0; k < Kc; k++) acc += m.dec_conv[((size_t)o * gs + i) * Kc + k] * e[(size_t)k * D + g0 + i];
        h[(size_t)o] = acc > 0.0f ? acc : 0.0f;
    }
    std::vector<float> out((size_t)m.J);
    for (int j = 0; j < m.J; j++) {
        float acc = m.dec_proj_b[(size_t)j];
        const float* w = m.dec_proj_w.data() + (size_t)j * D;
        for (int i = 0; i < D; i++) acc += w[i] * h[(size_t)i];
        out[(size_t)j] = acc;
    }
    return out;
}

struct Greedy {
    std::vector<int> hyp;
    std::vector<float> d;
    std::vector<int32_t> toks;
    std::vector<int64_t> frames;
    int64_t frame_pos = 0, blank_run = 0;
    bool enc_reset_pending = false;
};

void greedy_init(const Model& m, Greedy& g) {
    g.hyp.assign((size_t)m.Kc, -1);
    g.hyp.back() = m.blank_id;
    g.d = decoder_out(m, g.hyp);
    g.toks.clear(); g.frames.clear();
    g.frame_pos = 0; g.blank_run = 0;
}

// One joiner row: bias + dot(fp16 weights, t), one accumulator ascending j (the NEON path of CrispASR).
inline float joint_row(const Model& m, int v, const float* t) {
    const int J = m.J;
    const uint16_t* w = m.join_out_w.data() + (size_t)v * J;
    float acc = m.join_out_b[(size_t)v];
    int j = 0;
#if defined(__ARM_NEON) && defined(__aarch64__)
    float32x4_t a = vdupq_n_f32(acc);
    for (; j + 7 < J; j += 8) {
        const float16x8_t wh = vreinterpretq_f16_u16(vld1q_u16(w + j));
        a = vfmaq_f32(a, vcvt_f32_f16(vget_low_f16(wh)), vld1q_f32(t + j));
        a = vfmaq_f32(a, vcvt_f32_f16(vget_high_f16(wh)), vld1q_f32(t + j + 4));
    }
    for (; j + 3 < J; j += 4) a = vfmaq_f32(a, vcvt_f32_f16(vreinterpret_f16_u16(vld1_u16(w + j))), vld1q_f32(t + j));
    acc = vaddvq_f32(a);
#endif
    for (; j < J; j++) acc += half_to_float(w[j]) * t[j];
    return acc;
}

void greedy_run(const Model& m, Greedy& g, const float* enc, int n_enc) {
    const int J = m.J, V = m.vocab;
    std::vector<float> t((size_t)J);
    for (int f = 0; f < n_enc; f++, g.frame_pos++) {
        const float* e = enc + (size_t)f * J;
        for (int j = 0; j < J; j++) t[(size_t)j] = std::tanh(e[j] + g.d[(size_t)j]);
        int best = 0; float best_v = joint_row(m, 0, t.data());
        for (int v = 1; v < V; v++) { const float a = joint_row(m, v, t.data()); if (a > best_v) { best_v = a; best = v; } }
        if (best == m.blank_id || best == m.unk_id) {
            // Endpoint reset (XASR_RESET_BLANK_FRAMES, default 25 frames of 40 ms): see CrispASR xasr.cpp.
            static const int64_t reset_after = [] { const char* v = getenv("XASR_RESET_BLANK_FRAMES"); return v ? (int64_t)atoll(v) : (int64_t)25; }();
            if (reset_after > 0 && ++g.blank_run == reset_after && g.hyp.back() != m.blank_id) {
                g.hyp.push_back(-1); g.hyp.push_back(m.blank_id);
                g.d = decoder_out(m, g.hyp);
                g.enc_reset_pending = true;
            }
        } else {
            g.blank_run = 0;
            g.toks.push_back(best); g.frames.push_back(g.frame_pos);
            g.hyp.push_back(best);
            g.d = decoder_out(m, g.hyp);
        }
    }
}

bool is_cjk(char32_t cp) {
    return (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0x2E80 && cp <= 0xA4CF) || (cp >= 0xA840 && cp <= 0xD7AF) ||
           (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE4F) || (cp >= 0xFF65 && cp <= 0xFFDC) ||
           (cp >= 0x20000 && cp <= 0x2FFFF);
}
bool is_punct(char32_t cp) {
    return (cp >= 0x21 && cp <= 0x2F) || (cp >= 0x3A && cp <= 0x40) || (cp >= 0x5B && cp <= 0x60) ||
           (cp >= 0x7B && cp <= 0x7E) || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF01 && cp <= 0xFF0F) ||
           (cp >= 0xFF1A && cp <= 0xFF20) || (cp >= 0xFF3B && cp <= 0xFF40) || (cp >= 0xFF5B && cp <= 0xFF65);
}
std::u32string utf8_to_u32(const std::string& s) {
    std::u32string out;
    for (size_t i = 0; i < s.size();) {
        const uint8_t c = (uint8_t)s[i];
        const int n = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 1;
        char32_t cp = n == 1 ? c : n == 2 ? (c & 0x1F) : n == 3 ? (c & 0x0F) : (c & 0x07);
        for (int k = 1; k < n && i + (size_t)k < s.size(); k++) cp = (cp << 6) | ((uint8_t)s[i + (size_t)k] & 0x3F);
        out.push_back(cp); i += (size_t)n;
    }
    return out;
}
std::string u32_to_utf8(const std::u32string& u) {
    std::string out;
    for (char32_t cp : u) {
        if (cp < 0x80) out += (char)cp;
        else if (cp < 0x800) { out += (char)(0xC0 | (cp >> 6)); out += (char)(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += (char)(0xE0 | (cp >> 12)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
        else { out += (char)(0xF0 | (cp >> 18)); out += (char)(0x80 | ((cp >> 12) & 0x3F)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
    }
    return out;
}

// sherpa-onnx Convert(): '▁' -> space, <unk> dropped, RemoveSpaceBetweenCjk, leading spaces dropped.
std::string detok(const Model& m, const int32_t* toks, int n) {
    std::string s;
    for (int i = 0; i < n; i++) {
        std::string sym = m.tokens[(size_t)toks[i]];
        if (sym == "<unk>") continue;
        if (sym.size() >= 3 && (uint8_t)sym[0] == 0xe2 && (uint8_t)sym[1] == 0x96 && (uint8_t)sym[2] == 0x81) sym.replace(0, 3, " ");
        s += sym;
    }
    const std::u32string u = utf8_to_u32(s);
    std::u32string out;
    for (size_t i = 0; i < u.size(); i++) {
        if (i > 0 && u[i] == U' ' && i + 1 < u.size() && ((is_cjk(u[i - 1]) && is_cjk(u[i + 1])) || is_punct(u[i + 1]))) continue;
        out.push_back(u[i]);
    }
    s = u32_to_utf8(out);
    const size_t b = s.find_first_not_of(' ');
    return b == std::string::npos ? std::string() : s.substr(b);
}

char* dup_str(const std::string& s) { char* r = (char*)malloc(s.size() + 1); std::memcpy(r, s.c_str(), s.size() + 1); return r; }

// Chunk geometry of the shipped 480 ms export: window of 61 fbank frames shifted by 48, 12 encoder frames out.
constexpr int kWindow = 61, kShift = 48, kEncFrames = 12;

core_kaldi::FbankParams fbank_params(int F) {
    core_kaldi::FbankParams p;   // sherpa-onnx FeatureExtractorConfig
    p.n_mels = F; p.high_freq = -400.0f; p.snip_edges = false; p.mel_domain_triangles = true;
    return p;
}

}  // namespace

struct xasr_context {
    Model m;
    int tail_pad_ms = kWindow * 10 + 1000;
    xasr_chunk_fn ext_fn = nullptr;
    xasr_reset_fn ext_reset = nullptr;
    void* ext_user = nullptr;
};

struct xasr_stream {
    xasr_context* c = nullptr;
    std::vector<float> pcm, feats;
    int n_frames = 0, pos = 0;
    Greedy gs;
    bool finished = false;
};

namespace {

void stream_reset(xasr_stream* s) {
    s->pcm.clear(); s->feats.clear();
    s->n_frames = s->pos = 0;
    if (s->c->ext_reset) s->c->ext_reset(s->c->ext_user);
    greedy_init(s->c->m, s->gs);
    s->finished = false;
}

// Same arithmetic as CrispASR: frames whose window needs only real samples are computed as soon as they exist,
// frame 0 uses the mirrored left edge; the rest is computed on flush after the tail padding.
void extend_frames(xasr_stream* s, bool flush) {
    const auto p = fbank_params(s->c->m.F);
    const int F = p.n_mels, hop = 160, win = 400, lead = win / 2 - hop / 2;
    const int n = (int)s->pcm.size();
    if (flush) {
        int T = 0;
        const auto fb = core_kaldi::compute_fbank(s->pcm.data(), n, p, T);
        if (T > s->n_frames) {
            s->feats.insert(s->feats.end(), fb.begin() + (size_t)s->n_frames * F, fb.begin() + (size_t)T * F);
            s->n_frames = T;
        }
        return;
    }
    const int ready = n >= win - lead ? (n - (win - lead)) / hop + 1 : 0;
    if (ready <= s->n_frames) return;
    if (s->n_frames == 0) {
        int T0 = 0;
        const auto f0 = core_kaldi::compute_fbank(s->pcm.data(), win - lead, p, T0);
        s->feats.insert(s->feats.end(), f0.begin(), f0.begin() + F);
        s->n_frames = 1;
    }
    if (ready > s->n_frames) {
        auto q = p; q.snip_edges = true;
        const int start = s->n_frames * hop - lead, len = (ready - 1 - s->n_frames) * hop + win;
        int T = 0;
        const auto fb = core_kaldi::compute_fbank(s->pcm.data() + start, len, q, T);
        s->feats.insert(s->feats.end(), fb.begin(), fb.end());
        s->n_frames = ready;
    }
}

bool stream_accept(xasr_stream* s, const float* pcm, int n, bool flush) {
    if (s->finished) return true;
    s->pcm.insert(s->pcm.end(), pcm, pcm + n);
    if (flush) s->pcm.resize(s->pcm.size() + (size_t)s->c->tail_pad_ms * 16, 0.0f);
    extend_frames(s, flush);
    static const bool reset_enc = [] { const char* v = getenv("XASR_RESET_ENC"); return v && atoi(v) != 0; }();
    if (s->gs.enc_reset_pending) {
        s->gs.enc_reset_pending = false;
        if (reset_enc && s->c->ext_reset) s->c->ext_reset(s->c->ext_user);   // encoder caches back to stream start
    }
    if (!s->c->ext_fn) { fprintf(stderr, "xasr_host: no chunk encoder set\n"); return false; }
    const int F = s->c->m.F, Ed = s->c->m.J;
    std::vector<float> enc, step((size_t)kEncFrames * Ed);
    for (; s->pos + kWindow < s->n_frames; s->pos += kShift) {
        if (!s->c->ext_fn(s->c->ext_user, s->feats.data() + (size_t)s->pos * F, kWindow, F, step.data(), kEncFrames, Ed)) {
            fprintf(stderr, "xasr: external chunk encoder failed\n");
            return false;
        }
        enc.insert(enc.end(), step.begin(), step.end());
    }
    greedy_run(s->c->m, s->gs, enc.data(), (int)(enc.size() / (size_t)Ed));
    s->finished = flush;
    return true;
}

}  // namespace

extern "C" struct xasr_context_params xasr_context_default_params(void) {
    xasr_context_params p; p.n_threads = 4; p.verbosity = 1; p.use_gpu = false; p.chunk_ms = 480; p.tail_pad_ms = -1;
    return p;
}

// path = xasr_host.bin (not the GGUF).
extern "C" struct xasr_context* xasr_init_from_file(const char* path, struct xasr_context_params params) {
    auto* c = new xasr_context();
    if (!load(c->m, path)) { fprintf(stderr, "xasr_host: cannot read '%s'\n", path); delete c; return nullptr; }
    if (params.tail_pad_ms >= 0) c->tail_pad_ms = params.tail_pad_ms;
    return c;
}
extern "C" void xasr_free(struct xasr_context* c) { delete c; }
extern "C" void xasr_set_external_chunk_encoder(struct xasr_context* c, xasr_chunk_fn fn, xasr_reset_fn reset, void* user) {
    c->ext_fn = fn; c->ext_reset = reset; c->ext_user = user;
}
extern "C" struct xasr_stream* xasr_stream_init(struct xasr_context* c) { auto* s = new xasr_stream(); s->c = c; stream_reset(s); return s; }
extern "C" char* xasr_stream_accept(struct xasr_stream* s, const float* samples, int n, bool flush) {
    if (!stream_accept(s, samples, n, flush)) return nullptr;
    return dup_str(detok(s->c->m, s->gs.toks.data(), (int)s->gs.toks.size()));
}
extern "C" int xasr_stream_token_times(struct xasr_stream* s, const int32_t** ids, const int64_t** frames, int* n) {
    if (!s) return -1;
    *ids = s->gs.toks.empty() ? nullptr : s->gs.toks.data();
    *frames = s->gs.frames.empty() ? nullptr : s->gs.frames.data();
    *n = (int)s->gs.frames.size();
    return (int)s->gs.toks.size() != *n ? -1 : 0;
}
extern "C" void xasr_stream_reset(struct xasr_stream* s) { stream_reset(s); }
extern "C" void xasr_stream_free(struct xasr_stream* s) { delete s; }
extern "C" char* xasr_tokens_to_text(struct xasr_context* c, const int32_t* toks, int n) { return dup_str(detok(c->m, toks, n)); }
extern "C" char* xasr_transcribe(struct xasr_context* c, const float* samples, int n) {
    xasr_stream* s = xasr_stream_init(c);
    char* r = xasr_stream_accept(s, samples, n, true);
    xasr_stream_free(s);
    return r;
}
