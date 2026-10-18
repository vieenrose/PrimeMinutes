// xasr_litert.h - streaming X-ASR encoder (GilgameshWind chunk-480ms) on LiteRT, as a CrispASR external
// chunk encoder (see patches/crispasr-chunk-hook.patch, xasr_set_external_chunk_encoder).
//
// The tflite (tools/litert-export/xasr_stream_torch.py) has one signature: x [1,T,80] fp32 plus the 116
// streaming states in, enc_out [1,T',512] fp32 plus the 116 new states out. The chunk geometry is read from the
// graph: T = 2*chunk + 13 input frames, T' = chunk/2 output frames (480 ms export: 61 -> 12; 1920 ms: 205 -> 48). The states live in this
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
    static constexpr int kFeatDim = 80;
    static constexpr int kEncDim = 512;

    XasrLiteRT(const std::string & tflite_path, int threads);
    ~XasrLiteRT();

    XasrLiteRT(const XasrLiteRT &) = delete;
    XasrLiteRT & operator=(const XasrLiteRT &) = delete;

    // Zero every streaming state (stream start / reset).
    void reset();

    int window() const { return window_; }          // input frames per decode step (2 * chunk + 13)
    int enc_frames() const { return enc_frames_; }  // output frames per step (chunk / 2)
    int chunk_ms() const { return (window_ - 13) / 2 * 20; }  // 50 Hz encoder_embed frames of 20 ms

    // One decode step: feats is kWindow x kFeatDim floats; writes kEncFrames x kEncDim floats to enc_out.
    // Throws std::runtime_error on shape mismatch or runtime failure.
    void chunk(const float * feats, int T, int F, float * enc_out, int Eo, int Ed);

private:
    std::unique_ptr<LiteRtGraph> graph_;
    // The states live in two sets of graph buffers, swapped every step: set cur_ feeds the inputs, the other
    // set receives the outputs. No host copy per chunk.
    int feat_h_ = -1, enc_h_ = -1;
    int window_ = 0, enc_frames_ = 0;
    int states_[2][116];
    int cur_ = 0;
};

// C-callable hook for xasr_set_external_chunk_encoder.
int xasr_litert_chunk_cb(void * user, const float * feats, int T, int feat_dim, float * enc_out, int enc_frames, int enc_dim);
void xasr_litert_reset_cb(void * user);

}  // namespace nemo
