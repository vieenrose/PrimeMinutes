// diar_host.cc - see diar_host.h. Ported from native/audiocpp/src/models/nemotron_3_diar/{streaming,session,frontend}.cpp
// and framework/audio/{nemo_mel_frontend,dsp,waveform_ops}.cpp (shipped profile: hann/win 400/n_fft 512/hop 160, 128 mels,
// preemphasis 0.97, zero-padded center, no dither, log(x + 2^-24)).
#include "diar_host.h"

#include "diar_litert.h"
#include "diar_pre_encode.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstring>
#include <limits>
#include <numeric>
#include <set>
#include <stdexcept>
#include <tuple>

namespace nemo {
namespace {

constexpr int kNFft = 512, kWin = 400, kHop = 160, kMels = 128, kBins = kNFft / 2 + 1;
constexpr int kSub = 8, kHidden = 512, kSpk = 8, kUp = 8;
constexpr int64_t kOutHop = 160;   // samples per 10 ms output frame
constexpr float kPreemph = 0.97f;
constexpr float kInf = std::numeric_limits<float>::infinity();
constexpr int64_t kTrainedSpkcache = 264;   // the checkpoint's spkcache_len (model_config.yaml), every NVIDIA profile

struct Streaming {
    int spkcache_len, fifo_len, chunk_len, right, update_period;
    int sil_per_spk = 1;
    float pred_score_threshold = 0.25f, scores_boost_latest = 0.05f, strong_boost_rate = 0.75f,
          weak_boost_rate = 1.5f, min_pos_scores_rate = 0.5f;
    int64_t max_index = 99999;
};

// ---- frontend -------------------------------------------------------------------------------------------------
struct Frontend {
    std::vector<float> fb;                 // [kMels, kBins]
    std::vector<int> lo, hi;               // nonzero range per mel
    std::vector<float> window;             // [kWin]
    std::vector<std::complex<double>> tw;  // twiddles
    std::vector<int> rev;

    void init(std::vector<float> filterbank) {
        fb = std::move(filterbank);
        lo.assign(kMels, 0); hi.assign(kMels, 0);
        for (int m = 0; m < kMels; m++) {
            int a = kBins, b = 0;
            for (int f = 0; f < kBins; f++) if (fb[(size_t) m * kBins + f] != 0.0f) { a = std::min(a, f); b = f + 1; }
            lo[m] = a < b ? a : 0; hi[m] = b;
        }
        window.resize(kWin);
        constexpr long double pi = 3.14159265358979323846264338327950288L;
        for (int i = 0; i < kWin; i++)
            window[i] = (float) (0.5f - 0.5f * std::cos(2.0L * pi * (long double) i / (long double) (kWin - 1)));
        tw.resize(kNFft / 2);
        for (int i = 0; i < kNFft / 2; i++) tw[i] = std::polar(1.0, -2.0 * M_PI * i / kNFft);
        rev.resize(kNFft);
        int bits = 0; while ((1 << bits) < kNFft) bits++;
        for (int i = 0; i < kNFft; i++) { int r = 0; for (int b = 0; b < bits; b++) if (i >> b & 1) r |= 1 << (bits - 1 - b); rev[i] = r; }
    }

    void fft(std::vector<std::complex<double>> & a) const {
        for (int i = 0; i < kNFft; i++) if (i < rev[i]) std::swap(a[i], a[rev[i]]);
        for (int len = 2; len <= kNFft; len <<= 1) {
            const int step = kNFft / len;
            for (int i = 0; i < kNFft; i += len)
                for (int j = 0; j < len / 2; j++) {
                    const auto u = a[i + j], v = a[i + j + len / 2] * tw[j * step];
                    a[i + j] = u + v; a[i + j + len / 2] = u - v;
                }
        }
    }

    // mono -> time-major log-mel [frames, kMels]; `center` zero-pads n_fft/2 on both sides.
    std::vector<float> mel(std::vector<float> mono, bool center, int64_t & frames) const {
        for (size_t i = mono.size(); i-- > 1;) mono[i] -= kPreemph * mono[i - 1];
        const int64_t n = (int64_t) mono.size(), pad = center ? kNFft / 2 : 0;
        frames = 1 + (n + 2 * pad - kNFft) / kHop;
        std::vector<float> out((size_t) std::max<int64_t>(frames, 0) * kMels);
        const int off = (kNFft - kWin) / 2;
        std::vector<std::complex<double>> a(kNFft);
        std::vector<float> power(kBins);
        for (int64_t t = 0; t < frames; t++) {
            std::fill(a.begin(), a.end(), std::complex<double>(0, 0));
            const int64_t start = t * kHop - pad;
            for (int i = 0; i < kWin; i++) {
                const int64_t s = start + off + i;
                const float v = (s >= 0 && s < n) ? mono[(size_t) s] : 0.0f;
                a[off + i] = (double) (v * window[i]);
            }
            fft(a);
            for (int f = 0; f < kBins; f++) {
                const float re = (float) a[f].real(), im = (float) a[f].imag();
                const float mag = std::sqrt(re * re + im * im);
                power[f] = mag * mag;
            }
            for (int m = 0; m < kMels; m++) {
                double sum = 0.0;
                for (int f = lo[m]; f < hi[m]; f++) sum += (double) power[f] * (double) fb[(size_t) m * kBins + f];
                out[(size_t) t * kMels + m] = std::log((float) sum + 5.960464477539063e-8f);
            }
        }
        return out;
    }
};

// ---- scheduler ------------------------------------------------------------------------------------------------
struct Window {
    int64_t mel_start = 0, mel_frames = 0, encoder_frames = 0, left_ctx = 0, right_ctx = 0;
    std::vector<float> mono;
};

struct Scheduler {
    Streaming s;
    std::vector<float> audio;
    int64_t base = 0, received = 0, logical_end = 0, produced = 0, consumed = 0;
    bool started = false, finished = false;

    int64_t avail(bool fin) const {
        if (logical_end < kNFft / 2) return 0;
        const int64_t smp = logical_end - kNFft / 2;
        return fin ? (smp + kHop - 1) / kHop : smp / kHop + 1;
    }
    Window make(int64_t end_mel, bool fin) const {
        const int64_t right_limit = (int64_t) s.right * kSub;
        const int64_t start = consumed;
        const int64_t right_mel = std::min(right_limit, produced - end_mel);
        const int64_t wstart = start;           // chunk_left_context = 0
        const int64_t wframes = end_mel + right_mel - wstart;
        const int64_t sample_start = wstart * kHop;
        const int64_t fstart = wstart == 0 ? 0 : sample_start - kNFft / 2;
        const int64_t fend = sample_start + (wframes - 1) * kHop + kNFft / 2;
        Window w;
        w.mel_start = wstart; w.mel_frames = wframes;
        const int64_t nm = end_mel - start;
        w.encoder_frames = fin ? (nm + kSub - 1) / kSub : nm / kSub;
        w.left_ctx = 0;
        w.right_ctx = (right_mel + kSub - 1) / kSub;
        w.mono.assign((size_t) std::max<int64_t>(fend - fstart, 0), 0.0f);
        const int64_t cs = std::max(fstart, base), ce = std::min(fend, received);
        if (ce > cs) std::copy_n(audio.data() + (cs - base), (size_t) (ce - cs), w.mono.data() + (cs - fstart));
        return w;
    }
    void trim() {
        const int64_t keep = std::max<int64_t>(consumed * kHop - kNFft / 2, 0);
        if (keep <= base) return;
        const int64_t c = std::min<int64_t>(keep - base, (int64_t) audio.size());
        audio.erase(audio.begin(), audio.begin() + c);
        base += c;
    }
    std::vector<Window> drain(bool fin) {
        const int64_t chunk_mel = (int64_t) s.chunk_len * kSub, right_mel = (int64_t) s.right * kSub;
        std::vector<Window> ws;
        produced = avail(fin);
        for (;;) {
            const int64_t start = consumed;
            int64_t end = start + chunk_mel;
            if (!fin) { if (end + right_mel > produced) break; }
            else { end = std::min(end, produced); if (end <= start) break; }
            ws.push_back(make(end, fin));
            consumed = end;
            trim();
        }
        return ws;
    }
    std::vector<Window> push(const float * pcm, size_t n) {
        if (finished) return {};
        audio.insert(audio.end(), pcm, pcm + n);
        received += (int64_t) n; logical_end = received;
        started = true;
        return drain(false);
    }
    std::vector<Window> finalize() {
        if (finished) return {};
        finished = true;
        if (!started || received == 0) return {};
        const int64_t actual = received;
        audio.insert(audio.end(), (size_t) (kNFft / 2), 0.0f);
        received += kNFft / 2;
        logical_end = actual + kNFft / 2;
        return drain(true);
    }
};

// ---- AOSC -----------------------------------------------------------------------------------------------------
std::vector<int> top_indices(const std::vector<float> & scores, int frames, int speaker, int count) {
    if (frames <= 0 || count <= 0) return {};
    std::vector<int> idx(frames);
    std::iota(idx.begin(), idx.end(), 0);
    count = std::min(count, frames);
    std::partial_sort(idx.begin(), idx.begin() + count, idx.end(), [&](int l, int r) {
        const float a = scores[(size_t) l * kSpk + speaker], b = scores[(size_t) r * kSpk + speaker];
        return a != b ? a > b : l < r;
    });
    idx.resize(count);
    return idx;
}

struct Aosc {
    Streaming cfg;
    std::vector<float> silence;
    std::vector<float> cache, fifo, cache_probs;
    int cache_frames = 0, fifo_frames = 0;

    void update(const float * chunk_emb, int window_frames, const float * probs, int left, int right) {
        const int valid = window_frames - left - right;
        if (valid <= 0) return;
        const int old_cache = cache_frames, old_fifo = fifo_frames;
        const float * fifo_p = probs + (size_t) old_cache * kSpk;
        const float * chunk_p = probs + (size_t) (old_cache + old_fifo + left) * kSpk;
        const float * valid_e = chunk_emb + (size_t) left * kHidden;
        fifo.insert(fifo.end(), valid_e, valid_e + (size_t) valid * kHidden);
        std::vector<float> comb((size_t) (old_fifo + valid) * kSpk);
        std::memcpy(comb.data(), fifo_p, (size_t) old_fifo * kSpk * sizeof(float));
        std::memcpy(comb.data() + (size_t) old_fifo * kSpk, chunk_p, (size_t) valid * kSpk * sizeof(float));
        fifo_frames = old_fifo + valid;
        if (fifo_frames <= cfg.fifo_len) return;
        int pop = cfg.update_period;
        pop = std::max(pop, valid - cfg.fifo_len + old_fifo);
        pop = std::min(pop, fifo_frames);
        cache.insert(cache.end(), fifo.begin(), fifo.begin() + (size_t) pop * kHidden);
        if (!cache_probs.empty())
            cache_probs.insert(cache_probs.end(), comb.begin(), comb.begin() + (size_t) pop * kSpk);
        cache_frames += pop;
        if (cache_frames > cfg.spkcache_len && cache_probs.empty()) {
            cache_probs.resize((size_t) cache_frames * kSpk);
            std::memcpy(cache_probs.data(), probs, (size_t) old_cache * kSpk * sizeof(float));
            std::memcpy(cache_probs.data() + (size_t) old_cache * kSpk, comb.data(), (size_t) pop * kSpk * sizeof(float));
        }
        fifo.erase(fifo.begin(), fifo.begin() + (size_t) pop * kHidden);
        fifo_frames -= pop;
        if (cache_frames > cfg.spkcache_len) compress(cache_probs);
    }

    void compress(const std::vector<float> & P) {
        const int frames = cache_frames, cap = cfg.spkcache_len;
        const int per = cap / kSpk - cfg.sil_per_spk;
        const int strong = (int) std::floor(per * cfg.strong_boost_rate);
        const int weak = (int) std::floor(per * cfg.weak_boost_rate);
        const int min_pos = (int) std::floor(per * cfg.min_pos_scores_rate);
        const float log_half = std::log(0.5f);
        std::vector<float> sc((size_t) frames * kSpk);
        for (int f = 0; f < frames; f++) {
            float inactive = 0.0f;
            for (int s = 0; s < kSpk; s++)
                inactive += std::log(std::max(1.0f - P[(size_t) f * kSpk + s], cfg.pred_score_threshold));
            for (int s = 0; s < kSpk; s++) {
                const size_t i = (size_t) f * kSpk + s;
                const float p = P[i];
                sc[i] = std::log(std::max(p, cfg.pred_score_threshold)) -
                        std::log(std::max(1.0f - p, cfg.pred_score_threshold)) + inactive - log_half;
            }
        }
        std::vector<int> pos(kSpk, 0);
        for (int f = 0; f < frames; f++)
            for (int s = 0; s < kSpk; s++) {
                const size_t i = (size_t) f * kSpk + s;
                if (!(P[i] > 0.5f)) sc[i] = -kInf;
                if (sc[i] > 0.0f) ++pos[s];
            }
        for (int s = 0; s < kSpk; s++) {
            if (pos[s] < min_pos) continue;
            for (int f = 0; f < frames; f++) {
                const size_t i = (size_t) f * kSpk + s;
                if (P[i] > 0.5f && !(sc[i] > 0.0f)) sc[i] = -kInf;
            }
        }
        if (cfg.scores_boost_latest > 0.0f)
            for (int f = cap; f < frames; f++)
                for (int s = 0; s < kSpk; s++) sc[(size_t) f * kSpk + s] += cfg.scores_boost_latest;
        for (int s = 0; s < kSpk; s++) {
            for (int f : top_indices(sc, frames, s, strong)) sc[(size_t) f * kSpk + s] -= 2.0f * log_half;
            for (int f : top_indices(sc, frames, s, weak)) sc[(size_t) f * kSpk + s] -= log_half;
        }
        const int padded = frames + cfg.sil_per_spk;
        std::vector<int64_t> flat((size_t) kSpk * padded);
        std::iota(flat.begin(), flat.end(), 0);
        auto score = [&](int64_t i) {
            const int f = (int) (i % padded);
            if (f >= frames) return kInf;
            return sc[(size_t) f * kSpk + (int) (i / padded)];
        };
        std::partial_sort(flat.begin(), flat.begin() + cap, flat.end(), [&](int64_t l, int64_t r) {
            const float a = score(l), b = score(r);
            return a != b ? a > b : l < r;
        });
        flat.resize(cap);
        for (auto & i : flat) if (score(i) == -kInf) i = cfg.max_index * padded + cfg.max_index;
        std::sort(flat.begin(), flat.end());
        std::vector<float> nc((size_t) cap * kHidden), np((size_t) cap * kSpk, 0.0f);
        for (int o = 0; o < cap; o++) {
            const int64_t i = flat[o];
            const int f = (int) (i % padded);
            const bool disabled = i >= (int64_t) kSpk * padded || f >= frames;
            if (disabled) std::copy(silence.begin(), silence.end(), nc.begin() + (size_t) o * kHidden);
            else {
                std::copy_n(cache.begin() + (size_t) f * kHidden, kHidden, nc.begin() + (size_t) o * kHidden);
                std::copy_n(P.begin() + (size_t) f * kSpk, kSpk, np.begin() + (size_t) o * kSpk);
            }
        }
        cache = std::move(nc); cache_probs = std::move(np); cache_frames = cap;
    }
};

}  // namespace

// ---- session --------------------------------------------------------------------------------------------------
struct DiarHost::Impl {
    DiarHostConfig cfg;
    Streaming s;
    Frontend fe;
    std::vector<float> silence;
    std::unique_ptr<DiarPreEncode> pre;
    std::unique_ptr<DiarLiteRT> graph;
    Scheduler sched;
    Aosc aosc;
    std::vector<float> probs;   // [frames, kSpk] high resolution timeline
    std::vector<float> energy;  // log total mel energy per 10 ms frame (see DiarHost::energy_min)
    int64_t samples = 0;
    std::set<std::tuple<int64_t, int64_t, int>> emitted;

    // Total energy of one mel frame on a log scale: mel[m] is log(power_m + eps), so summing the exponents
    // and taking the log again is log(sum power). Silence floors at log(128 * eps) ~ -11.8, speech runs a
    // few log units above it, which is the dip a speaker change falls in.
    static float frame_energy(const float * mel_row) {
        double sum = 0.0;
        for (int m = 0; m < kMels; m++) sum += std::exp(mel_row[m]);
        return (float) std::log(sum + 5.960464477539063e-8);
    }

    void process(const Window & w) {
        int64_t raw = 0;
        auto mel = fe.mel(w.mono, w.mel_start == 0, raw);
        if (raw < w.mel_frames) throw std::runtime_error("DiarHost: frontend produced too few frames");
        if (w.mel_start + w.mel_frames > (int64_t) energy.size())
            energy.resize((size_t) (w.mel_start + w.mel_frames), -1.0f);
        for (int64_t t = 0; t < w.mel_frames; t++)
            energy[(size_t) (w.mel_start + t)] = frame_energy(mel.data() + (size_t) t * kMels);
        const int64_t enc = (w.mel_frames + kSub - 1) / kSub;
        std::vector<float> stacked((size_t) enc * kSub * kMels, 0.0f);
        std::copy_n(mel.begin(), (size_t) w.mel_frames * kMels, stacked.begin());
        const auto emb = pre->encode(stacked, 1, enc, kSub * kMels);

        const int64_t cache_n = aosc.cache_frames, fifo_n = aosc.fifo_frames;
        const int64_t packed_n = cache_n + fifo_n + enc;
        std::vector<float> packed((size_t) packed_n * kHidden, 0.0f);
        std::copy(aosc.cache.begin(), aosc.cache.end(), packed.begin());
        std::copy(aosc.fifo.begin(), aosc.fifo.end(), packed.begin() + aosc.cache.size());
        std::copy(emb.begin(), emb.end(), packed.begin() + aosc.cache.size() + aosc.fifo.size());
        const auto high = graph->encode(packed, 1, packed_n, {packed_n});

        const int64_t left = std::min<int64_t>(w.left_ctx, enc);
        const int64_t right = std::min<int64_t>(w.right_ctx, enc - left);
        const int64_t central = std::min<int64_t>(w.encoder_frames, enc - left - right);
        if (central <= 0) return;
        const int64_t hs = (cache_n + fifo_n + left) * kUp;
        probs.insert(probs.end(), high.begin() + (size_t) hs * kSpk, high.begin() + (size_t) (hs + central * kUp) * kSpk);
        std::vector<float> low((size_t) packed_n * kSpk, 0.0f);
        for (int64_t f = 0; f < packed_n; f++)
            for (int sub = 0; sub < kUp; sub++)
                for (int sp = 0; sp < kSpk; sp++)
                    low[(size_t) f * kSpk + sp] += high[(size_t) (f * kUp + sub) * kSpk + sp] / (float) kUp;
        aosc.update(emb.data(), (int) enc, low.data(), (int) left, (int) right);
    }

    std::vector<DiarHostTurn> decode(int64_t frames, bool include_open) const {
        std::vector<DiarHostTurn> turns;
        for (int sp = 0; sp < kSpk; sp++) {
            int64_t start = -1;
            for (int64_t f = 0; f <= frames; f++) {
                const bool active = f < frames && probs[(size_t) (f * kSpk + sp)] >= cfg.threshold;
                if (active && start < 0) start = f;
                else if (!active && start >= 0) {
                    const int64_t end = f;
                    if (include_open || end < frames) {
                        const int64_t ps = std::max<int64_t>(0, start - cfg.pad_frames);
                        const int64_t pe = std::min<int64_t>(frames, end + cfg.pad_frames);
                        if (pe - ps >= cfg.min_frames) {
                            float conf = 0.0f;
                            for (int64_t i = start; i < end; i++) conf += probs[(size_t) (i * kSpk + sp)];
                            conf /= (float) std::max<int64_t>(1, end - start);
                            turns.push_back({ps * kOutHop, pe * kOutHop, sp, conf});
                        }
                    }
                    start = -1;
                }
            }
        }
        std::sort(turns.begin(), turns.end(), [](const DiarHostTurn & a, const DiarHostTurn & b) {
            if (a.start_sample != b.start_sample) return a.start_sample < b.start_sample;
            return std::to_string(a.speaker) < std::to_string(b.speaker);   // audio.cpp sorts "speaker_N" strings
        });
        return turns;
    }

    std::vector<DiarHostTurn> run(const std::vector<Window> & ws) {
        for (const auto & w : ws) process(w);
        const auto turns = decode((int64_t) (probs.size() / kSpk), false);
        std::vector<DiarHostTurn> fresh;
        for (const auto & t : turns)
            if (emitted.insert({t.start_sample, t.end_sample, t.speaker}).second) fresh.push_back(t);
        return fresh;
    }
};

DiarHost::DiarHost(const DiarHostConfig & c) : p_(new Impl) {
    auto & I = *p_;
    I.cfg = c;
    FILE * f = std::fopen(c.host_bin.c_str(), "rb");
    if (!f) throw std::runtime_error("DiarHost: cannot open " + c.host_bin);
    char magic[4]; uint32_t dim[3];
    bool ok = std::fread(magic, 1, 4, f) == 4 && std::memcmp(magic, "DH01", 4) == 0 &&
              std::fread(dim, 4, 3, f) == 3 && dim[0] == kMels && dim[1] == kBins && dim[2] == kHidden;
    std::vector<float> fb((size_t) kMels * kBins);
    I.silence.resize(kHidden);
    ok = ok && std::fread(fb.data(), 4, fb.size(), f) == fb.size() &&
         std::fread(I.silence.data(), 4, I.silence.size(), f) == I.silence.size();
    std::fclose(f);
    if (!ok) throw std::runtime_error("DiarHost: " + c.host_bin + " is not a DH01 file");
    I.fe.init(std::move(fb));
    I.pre = std::make_unique<DiarPreEncode>(c.pre_f32);
    I.graph = std::make_unique<DiarLiteRT>(c.tflite, c.threads);
    if (c.spkcache_len <= 0) {
        if (!(c.spkcache_update_period >= c.chunk_len && c.chunk_len > c.fifo_len))
            throw std::runtime_error("DiarHost: spkcache_len 0 needs spkcache_update_period >= chunk_len > fifo_len");
        I.cfg.spkcache_len = (int) std::min<int64_t>(kTrainedSpkcache, I.graph->max_frames() - c.chunk_len - c.chunk_right_context);
        if (I.cfg.spkcache_len < kSpk * (Streaming{}.sil_per_spk + 1))
            throw std::runtime_error("DiarHost: " + c.tflite + " is too short for a speaker cache");
    }
    I.s = {I.cfg.spkcache_len, c.fifo_len, c.chunk_len, c.chunk_right_context, c.spkcache_update_period};
    I.sched.s = I.s;
    I.aosc.cfg = I.s;
    I.aosc.silence = I.silence;
}

DiarHost::~DiarHost() = default;

std::vector<DiarHostTurn> DiarHost::push(const float * pcm, size_t n) {
    p_->samples += (int64_t) n;
    return p_->run(p_->sched.push(pcm, n));
}

std::vector<DiarHostTurn> DiarHost::finish() {
    auto & I = *p_;
    for (const auto & w : I.sched.finalize()) I.process(w);
    const int64_t expected = (I.samples + kOutHop - 1) / kOutHop;
    const int64_t frames = std::min<int64_t>(expected, (int64_t) (I.probs.size() / kSpk));
    I.probs.resize((size_t) (frames * kSpk));
    return I.decode(frames, true);
}

bool DiarHost::energy_min(int64_t f0, int64_t f1, int64_t * out_frame, float * out_val) const {
    const int64_t n = (int64_t) p_->energy.size();
    f0 = std::max<int64_t>(f0, 0);
    f1 = std::min<int64_t>(f1, n - 1);
    int64_t best = -1;
    float bv = 0.0f;
    for (int64_t f = f0; f <= f1; f++) {
        const float v = p_->energy[(size_t) f];
        if (v < -1e30f) continue;                     // a frame the stream never computed
        if (best < 0 || v < bv) { best = f; bv = v; }
    }
    if (best < 0) return false;
    if (out_frame) *out_frame = best;
    if (out_val) *out_val = bv;
    return true;
}

}  // namespace nemo
