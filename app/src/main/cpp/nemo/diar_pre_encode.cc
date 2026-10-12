// diar_pre_encode.cc - see diar_pre_encode.h.
#include "diar_pre_encode.h"

#include <cstdio>
#include <stdexcept>

namespace nemo {

DiarPreEncode::DiarPreEncode(const std::string & f32_path) : w_((size_t) kOut * kIn) {
    FILE * f = std::fopen(f32_path.c_str(), "rb");
    if (!f) throw std::runtime_error("DiarPreEncode: cannot open " + f32_path);
    const size_t n = std::fread(w_.data(), sizeof(float), w_.size(), f);
    std::fclose(f);
    if (n != w_.size()) throw std::runtime_error("DiarPreEncode: " + f32_path + " is not a 512x1024 fp32 matrix");
}

std::vector<float> DiarPreEncode::encode(const std::vector<float> & stacked, int64_t batch, int64_t frames, int64_t stacked_dim) const {
    if (stacked_dim != kIn) throw std::runtime_error("DiarPreEncode: stacked_dim must be 1024");
    const size_t rows = (size_t) batch * (size_t) frames;
    if (stacked.size() != rows * kIn) throw std::runtime_error("DiarPreEncode: stacked size mismatch");
    std::vector<float> out(rows * kOut);
    for (size_t r = 0; r < rows; r++) {
        const float * x = stacked.data() + r * kIn;
        float * y = out.data() + r * kOut;
        for (int o = 0; o < kOut; o++) {
            const float * wo = w_.data() + (size_t) o * kIn;
            float acc = 0.0f;
            for (int i = 0; i < kIn; i++) acc += wo[i] * x[i];
            y[o] = acc;
        }
    }
    return out;
}

}  // namespace nemo
