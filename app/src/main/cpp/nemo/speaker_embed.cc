// speaker_embed.cc - see speaker_embed.h.
#include "speaker_embed.h"

#include <cmath>
#include <stdexcept>

#include "kaldi_fbank.h"
#include "litert_graph.h"

namespace nemo {

SpeakerEmbedder::SpeakerEmbedder(const std::string & tflite, int threads, const SpeakerEmbedConfig & cfg) : cfg_(cfg) {
    graph_ = std::make_unique<LiteRtGraph>(tflite, threads);
    if (graph_->num_inputs() < 1 || graph_->num_outputs() < 1) throw std::runtime_error("speaker graph: no input/output");
    frames_ = (int) (graph_->input_bytes(0) / sizeof(float) / (size_t) cfg_.n_mels);
    dim_ = (int) (graph_->output_bytes(0) / sizeof(float));
    if (frames_ <= 0 || dim_ <= 0) throw std::runtime_error("speaker graph: bad shapes");
}

SpeakerEmbedder::~SpeakerEmbedder() = default;

std::vector<std::vector<float>> SpeakerEmbedder::windows(const float * pcm, const int * lens, int n, std::vector<float> * weights) {
    core_kaldi::FbankParams p;
    p.n_mels = cfg_.n_mels;
    p.int16_scale = cfg_.int16_scale;
    p.snip_edges = cfg_.snip_edges;
    const int M = cfg_.n_mels;
    // Frames of every segment, back to back (no frame straddles two segments).
    std::vector<float> feats;
    size_t off = 0;
    for (int s = 0; s < n; ++s) {
        int T = 0;
        std::vector<float> f = core_kaldi::compute_fbank(pcm + off, lens[s], p, T);
        off += (size_t) lens[s];
        if (T > 0) feats.insert(feats.end(), f.begin(), f.end());
    }
    const int T = (int) (feats.size() / (size_t) M);
    std::vector<std::vector<float>> out;
    if (T <= 0) return out;
    // Window starts: every frames_ frames; the last partial window is taken as the LAST frames_ frames when it is
    // long enough, and a meeting with less than one window repeats its frames to fill it.
    std::vector<std::pair<int, int>> starts;   // (start, new frames)
    for (int a = 0; a + frames_ <= T; a += frames_) starts.push_back({a, frames_});
    const int rest = T - (int) starts.size() * frames_;
    if (rest > 0 && (starts.empty() || rest >= (int) (cfg_.min_last * frames_)))
        starts.push_back({std::max(0, T - frames_), rest});
    std::vector<float> win((size_t) frames_ * M), emb((size_t) dim_);
    for (auto [a, w] : starts) {
        for (int t = 0; t < frames_; ++t) {
            const int src = (T >= frames_) ? a + t : t % T;
            std::copy(feats.begin() + (size_t) src * M, feats.begin() + (size_t) (src + 1) * M, win.begin() + (size_t) t * M);
        }
        if (cfg_.mean_norm) {
            for (int m = 0; m < M; ++m) {
                double s = 0;
                for (int t = 0; t < frames_; ++t) s += win[(size_t) t * M + m];
                const float mu = (float) (s / frames_);
                for (int t = 0; t < frames_; ++t) win[(size_t) t * M + m] -= mu;
            }
        }
        graph_->write_input(0, win.data(), win.size() * sizeof(float));
        graph_->run();
        graph_->read_output(0, emb.data(), emb.size() * sizeof(float));
        out.push_back(emb);
        if (weights) weights->push_back((float) w);
    }
    return out;
}

std::vector<float> SpeakerEmbedder::embed(const float * pcm, const int * lens, int n) {
    std::vector<float> w;
    auto ws = windows(pcm, lens, n, &w);
    std::vector<float> acc((size_t) dim_, 0.f);
    if (ws.empty()) return {};
    for (size_t i = 0; i < ws.size(); ++i) {
        double nn = 0;
        for (float x : ws[i]) nn += (double) x * x;
        const float inv = nn > 0 ? (float) (1.0 / std::sqrt(nn)) : 0.f;
        for (int d = 0; d < dim_; ++d) acc[(size_t) d] += w[i] * ws[i][(size_t) d] * inv;
    }
    double nn = 0;
    for (float x : acc) nn += (double) x * x;
    if (nn <= 0) return {};
    const float inv = (float) (1.0 / std::sqrt(nn));
    for (float & x : acc) x *= inv;
    return acc;
}

}  // namespace nemo

extern "C" {
void * pm_spk_create(const char * tflite, int threads, int n_mels, int mean_norm, int int16_scale) {
    try {
        nemo::SpeakerEmbedConfig c;
        c.n_mels = n_mels; c.mean_norm = mean_norm != 0; c.int16_scale = int16_scale != 0;
        return new nemo::SpeakerEmbedder(tflite, threads, c);
    } catch (...) { return nullptr; }
}
int pm_spk_dim(void * h) { return h ? static_cast<nemo::SpeakerEmbedder *>(h)->dim() : 0; }
int pm_spk_embed(void * h, const float * pcm, const int * lens, int n, float * out, int cap) {
    if (!h) return -1;
    try {
        auto v = static_cast<nemo::SpeakerEmbedder *>(h)->embed(pcm, lens, n);
        if (v.empty()) return 0;
        if ((int) v.size() > cap) return -1;
        std::copy(v.begin(), v.end(), out);
        return (int) v.size();
    } catch (...) { return -1; }
}
void pm_spk_free(void * h) { delete static_cast<nemo::SpeakerEmbedder *>(h); }
}
