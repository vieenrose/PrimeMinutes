// diar_host.h - the Nemotron-3 Sortformer streaming diarizer without ggml / audio.cpp.
//
// Same algorithms as audio.cpp's nemotron_3_diar (NeMo mel frontend, StreamScheduler, AoscState, window batching,
// turn decoding); the only neural pieces are the pre-encode matrix (DiarPreEncode) and the LiteRT encoder+head
// graph (DiarLiteRT). Weights: diar_host.bin (tools/nemo-host/diar_host_pack.py: mel filterbank + silence embedding).
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace nemo {

struct DiarHostTurn {
    int64_t start_sample = 0;
    int64_t end_sample = 0;
    int speaker = 0;           // "speaker_<n>"
    float confidence = 0.0f;
};

struct DiarHostConfig {
    std::string host_bin;      // diar_host.bin
    std::string pre_f32;       // pre_encode_512x1024.f32
    std::string tflite;        // sortformer_*_f190.tflite
    int threads = 4;
    // Streaming geometry (engine defaults = the shipped 128/40/50/12 profile).
    int spkcache_len = 128;
    int fifo_len = 40;
    int chunk_len = 50;
    int chunk_right_context = 12;
    int spkcache_update_period = 60;
    // Turn decoding (10 ms frames). 0.5 / 20 since 2026-10-17 (was 0.3 / 45): fewer false alarms, same cost;
    // engine.h diar_opts carries the same values for the fusion turn snap.
    float threshold = 0.5f;
    int min_frames = 0;
    int pad_frames = 20;
};

class DiarHost {
public:
    explicit DiarHost(const DiarHostConfig & cfg);   // throws std::runtime_error
    ~DiarHost();
    DiarHost(const DiarHost &) = delete;
    DiarHost & operator=(const DiarHost &) = delete;

    // Contiguous 16 kHz mono PCM. Returns the turns that closed since the last call (each reported once).
    std::vector<DiarHostTurn> push(const float * pcm, size_t n);
    // Drains the last windows and returns every turn, open ones included.
    std::vector<DiarHostTurn> finish();

    // Log-energy of the mel frontend, one value per 10 ms frame - the same time base as DiarHostTurn
    // (frame f is the window centered at f * 160 samples). A clean speaker change sits in a dip of this
    // curve, which is a sharper clock than the padded turn start, so the engine's boundary snap aims at
    // the dip. Frames become available as the stream produces them (a window's right context is computed
    // early, then re-computed as central audio: same audio, same value, so overwriting is harmless).
    // Memory: one float per 10 ms = 100 KB per hour of audio.
    // Lowest log-energy in frames [f0, f1] (clamped to what the stream produced) and its frame; false
    // when that range holds no frame yet.
    bool energy_min(int64_t f0, int64_t f1, int64_t * out_frame, float * out_val) const;

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

}  // namespace nemo
