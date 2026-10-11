// xasr_litert.h - streaming X-ASR encoder (GilgameshWind chunk-480ms) on LiteRT, as a CrispASR external
// chunk encoder (see patches/crispasr-chunk-hook.patch, xasr_set_external_chunk_encoder).
//
// The tflite (tools/litert-export/xasr_stream_torch.py) has one signature: x [1,61,80] fp32 plus the 116
// streaming states in, enc_out [1,12,512] fp32 plus the 116 new states out. The states live in this
// adapter, so CrispASR's own caches are not used on this path. Dev-only (Config::backend), bench/validation.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace nemo {

class LiteRtGraph;

class XasrLiteRT {
public:
    static constexpr int kWindow = 61;   // input frames per decode step (chunk 24 * 2 + 7 + 6 pad)
    static constexpr int kFeatDim = 80;
    static constexpr int kEncFrames = 12; // output frames per step (chunk 24 / 2)
    static constexpr int kEncDim = 512;

    XasrLiteRT(const std::string & tflite_path, int threads, const std::string & backend);
    ~XasrLiteRT();

    XasrLiteRT(const XasrLiteRT &) = delete;
    XasrLiteRT & operator=(const XasrLiteRT &) = delete;

    // Zero every streaming state (stream start / reset).
    void reset();

    // One decode step: feats is kWindow x kFeatDim floats; writes kEncFrames x kEncDim floats to enc_out.
    // Throws std::runtime_error on shape mismatch or runtime failure.
    void chunk(const float * feats, int T, int F, float * enc_out, int Eo, int Ed);

private:
    std::unique_ptr<LiteRtGraph> graph_;
    std::vector<std::vector<uint8_t>> state_;  // state_[i] feeds input i+1 and holds output i+1
};

// C-callable hook for xasr_set_external_chunk_encoder.
int xasr_litert_chunk_cb(void * user, const float * feats, int T, int feat_dim, float * enc_out, int enc_frames, int enc_dim);
void xasr_litert_reset_cb(void * user);

}  // namespace nemo
