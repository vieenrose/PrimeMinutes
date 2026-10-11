// litert_graph.h - minimal LiteRT (C API) wrapper for one single-signature .tflite model: CPU (XNNPACK) or GPU.
//
// Used by DiarLiteRT (the Sortformer graph). Pattern copied from the mobile reader's wrapper
// (VoxSumDroid mfa/mfa_engine.cc): private writable mapping of the model file, TOML options for XNNPACK,
// managed tensor buffers, RunCompiledModel. Dev-only path for now (behind Config::backend).
#pragma once

#include <cstddef>
#include <string>
#include <vector>

struct LiteRtEnvironmentImpl;
struct LiteRtModelImpl;
struct LiteRtOptionsImpl;
struct LiteRtCompiledModelImpl;

namespace nemo {

class LiteRtGraph {
public:
    // backend: "cpu" (XNNPACK, num_threads) or "gpu". Throws std::runtime_error on any failure.
    LiteRtGraph(const std::string & tflite_path, int threads, const std::string & backend);
    ~LiteRtGraph();

    LiteRtGraph(const LiteRtGraph &) = delete;
    LiteRtGraph & operator=(const LiteRtGraph &) = delete;

    int num_inputs() const { return (int) in_bytes_.size(); }
    int num_outputs() const { return (int) out_bytes_.size(); }
    size_t input_bytes(int i) const { return in_bytes_[(size_t) i]; }
    size_t output_bytes(int i) const { return out_bytes_[(size_t) i]; }

    void write_input(int i, const void * src, size_t bytes);
    void read_output(int i, void * dst, size_t bytes);
    void run();

private:
    struct Impl;
    Impl * impl_ = nullptr;
    std::vector<size_t> in_bytes_, out_bytes_;
};

}  // namespace nemo
