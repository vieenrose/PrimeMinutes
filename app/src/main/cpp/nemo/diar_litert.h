// diar_litert.h - the Nemotron-3 Sortformer encoder + head as one fixed-shape LiteRT graph (dev bench only).
//
// Same contract as DiarCrispASR::encode (see diar_crispasr.h): embeddings [batch=1, frames, 512] + valid_frames
// -> probabilities [1, 8*frames, 8]. The graph is exported at kMaxFrames (tools/litert-export/export_sortformer.py,
// --frames 190); shorter windows are zero-padded and masked by `valid`, longer windows are an error.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace nemo {

class LiteRtGraph;

class DiarLiteRT {
public:
    static constexpr int64_t kMaxFrames = 190;
    static constexpr int64_t kHidden = 512;
    static constexpr int64_t kSpeakers = 8;
    static constexpr int64_t kUpsample = 8;

    // backend: "cpu" (XNNPACK, `threads`) or "gpu". Throws std::runtime_error on failure.
    DiarLiteRT(const std::string & tflite_path, int threads, const std::string & backend);
    ~DiarLiteRT();

    DiarLiteRT(const DiarLiteRT &) = delete;
    DiarLiteRT & operator=(const DiarLiteRT &) = delete;

    std::vector<float> encode(
        const std::vector<float> & embeddings,
        int64_t batch,
        int64_t frames,
        const std::vector<int64_t> & valid_frames);

    int64_t hidden_size() const { return kHidden; }
    int64_t num_speakers() const { return kSpeakers; }

private:
    std::unique_ptr<LiteRtGraph> graph_;
    int emb_input_ = 0;     // index of the float32 [1,190,512] input
    int valid_input_ = 1;   // index of the int64 [1] input
    bool valid_float_ = false;   // valid_frames input is float32 (GPU export)
};

}  // namespace nemo
