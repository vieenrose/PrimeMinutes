// speaker_embed.h - speaker voice vector (3D-Speaker CAM++ zh-en or any fixed-window speaker graph) on LiteRT CPU.
//
// docs/design/speaker-tags.md §4.3: the clean single-speaker speech of one speaker (several segments, 16 kHz mono,
// [-1, 1]) -> Kaldi 80-bin fbank per segment -> frames concatenated -> fixed windows of the graph's frame count
// (per-window mean normalisation when asked) -> one embedding per window -> frame-weighted mean -> L2-normalised.
// The window length, bins and normalisation come from the model's speaker card, never from constants here.
#pragma once

#include <memory>
#include <string>
#include <vector>

namespace nemo {

class LiteRtGraph;

struct SpeakerEmbedConfig {
    int n_mels = 80;
    bool mean_norm = true;      // subtract each bin's mean over the window
    bool int16_scale = false;   // fbank on int16-scaled samples
    bool snip_edges = true;
    float min_last = 0.5f;      // a last partial window shorter than this share of a window is dropped (unless it is the only one)
};

class SpeakerEmbedder {
public:
    SpeakerEmbedder(const std::string & tflite, int threads, const SpeakerEmbedConfig & cfg);
    ~SpeakerEmbedder();
    int dim() const { return dim_; }
    int window_frames() const { return frames_; }
    // pcm: the segments back to back; lens: their lengths in samples. Returns dim() floats, or empty when there is
    // not one frame of speech.
    std::vector<float> embed(const float * pcm, const int * lens, int n_segments);
    // Embeddings of each window (for parity tests): rows of dim() floats.
    std::vector<std::vector<float>> windows(const float * pcm, const int * lens, int n_segments, std::vector<float> * weights);

private:
    std::unique_ptr<LiteRtGraph> graph_;
    SpeakerEmbedConfig cfg_;
    int frames_ = 0, dim_ = 0;
};

}  // namespace nemo

// C API (iOS bridge, tools): handle or null; embed returns the number of floats written (0: no speech, -1: error).
extern "C" {
void * pm_spk_create(const char * tflite, int threads, int n_mels, int mean_norm, int int16_scale);
int pm_spk_dim(void * h);
int pm_spk_embed(void * h, const float * pcm, const int * lens, int n_segments, float * out, int out_cap);
void pm_spk_free(void * h);
}
