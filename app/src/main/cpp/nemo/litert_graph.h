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
    // CPU only (XNNPACK, num_threads). The XNNPACK weight cache is kept next to the model (<tflite>.wcache, set
    // NEMO_LITERT_NO_WCACHE to disable): the repacked weights are file-backed and shared instead of a second private
    // copy, which takes the peak RSS from +200 MB to +0 against ggml on the 152 MB X-ASR graph. Throws std::runtime_error on any failure.
    LiteRtGraph(const std::string & tflite_path, int threads);
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

    // Caller-owned tensor buffers, bound to the graph without a host copy. alloc_* returns a handle into a
    // pool owned by the graph; run_bound takes handles in signature order. Used by streaming state (ping-pong:
    // the buffers that held output k are passed as input k+1), see docs/pipeline-design.md 4(d).
    int alloc_input(int i);
    int alloc_output(int i);
    void write_buffer(int h, const void * src, size_t bytes);
    void read_buffer(int h, void * dst, size_t bytes);
    void run_bound(const std::vector<int> & in, const std::vector<int> & out);

private:
    struct Impl;
    Impl * impl_ = nullptr;
    std::vector<size_t> in_bytes_, out_bytes_;
};

}  // namespace nemo
