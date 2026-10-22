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
    std::string tflite;        // sortformer_int8_f380.tflite (or the f190 stand-in)
    int threads = 4;
    // Streaming geometry (80 ms frames). spkcache_len 0 = the most speaker memory the graph's window holds, capped at
    // the model's trained 264: the f380 graph runs 264, the older f190 graph 128 (the window is cache + chunk + right
    // context; the FIFO is empty at every window because update_period >= chunk_len > fifo_len).
    // 2026-10-22: 264 back (was 128 since 2026-09-27, picked for speed). Note10+ 20 min real time: same rtf 1.00,
    // diarizer step lag p95 0.14 -> 0.34 s; missed speaker changes zh 39.0 -> 33.2 % (6 AISHELL-4 meetings), en
    // control 24.7 -> 22.4 % headset / 35.4 -> 29.3 % distant mic (AMI); the 128 cache splits one far-field voice
    // into two identities that 264 keeps whole (docs/research/diarization-precision.md).
    int spkcache_len = 0;
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

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

}  // namespace nemo
