// diar_pre_encode.h - the Nemotron-3 diarizer's pre-encode (8-frame stacking is done by audio.cpp; this is the
// linear 1024 -> 512 after it, no bias). Pure C++, no ggml. Weights: the fp32 `encoder.pre_encode.proj` matrix
// [512, 1024] row-major, extracted from the LiteRT community graph A (litert-community/Nemotron-3-Diarization-LiteRT).
// Plugged in through audiocpp_nemotron3_diar_set_external_pre_encoder (patches/audiocpp-pre-encode-hook.patch).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace nemo {

class DiarPreEncode {
public:
    static constexpr int kIn = 1024;   // subsampling_factor (8) * num_mel_bins (128)
    static constexpr int kOut = 512;   // hidden_size

    explicit DiarPreEncode(const std::string & f32_path);

    // stacked: [batch, frames, kIn] row-major. Returns [batch, frames, kOut]. Throws on a shape mismatch.
    std::vector<float> encode(const std::vector<float> & stacked, int64_t batch, int64_t frames, int64_t stacked_dim) const;

private:
    std::vector<float> w_;   // [kOut, kIn]
};

}  // namespace nemo
