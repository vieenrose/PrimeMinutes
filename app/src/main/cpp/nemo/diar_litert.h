// diar_litert.h - the Nemotron-3 Sortformer encoder + head as one fixed-shape LiteRT graph (dev bench only).
//
// Same contract as DiarCrispASR::encode (see diar_crispasr.h): embeddings [batch=1, frames, 512] + valid_frames
// -> probabilities [1, 8*frames, 8]. The graph has a fixed window length, read from its input shape
// (tools/litert-export/export_sortformer.py --frames: 190 for the 128-frame speaker cache, 380 for the model's own
// 264); shorter windows are zero-padded and masked by `valid`, longer windows are an error.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace nemo {

class LiteRtGraph;

class DiarLiteRT {
public:
    static constexpr int64_t kHidden = 512;
    static constexpr int64_t kSpeakers = 8;
    static constexpr int64_t kUpsample = 8;

    // CPU only (XNNPACK, `threads`). Throws std::runtime_error on failure.
    DiarLiteRT(const std::string & tflite_path, int threads);
    ~DiarLiteRT();

    DiarLiteRT(const DiarLiteRT &) = delete;
    DiarLiteRT & operator=(const DiarLiteRT &) = delete;

    std::vector<float> encode(
        const std::vector<float> & embeddings,
        int64_t batch,
        int64_t frames,
        const std::vector<int64_t> & valid_frames);

    int64_t hidden_size() const { return kHidden; }
    /** Window length the graph was exported at (encoder frames, 80 ms each). */
    int64_t max_frames() const { return max_frames_; }
    int64_t num_speakers() const { return kSpeakers; }

private:
    std::unique_ptr<LiteRtGraph> graph_;
    int64_t max_frames_ = 0;
    int emb_input_ = 0;     // index of the float32 [1,max_frames,512] input
    int valid_input_ = 1;   // index of the int64 [1] input
    bool valid_float_ = false;   // valid_frames input is float32 (GPU export)
};

}  // namespace nemo
