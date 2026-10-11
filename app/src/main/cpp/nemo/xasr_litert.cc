// xasr_litert.cc - see xasr_litert.h.
#include "xasr_litert.h"

#include <cstring>
#include <stdexcept>

#include "litert_graph.h"

namespace nemo {

namespace {
constexpr int kStates = 116;
}

XasrLiteRT::XasrLiteRT(const std::string & tflite_path, int threads, const std::string & backend)
    : graph_(std::make_unique<LiteRtGraph>(tflite_path, threads, backend)) {
    if (graph_->num_inputs() != 1 + kStates || graph_->num_outputs() != 1 + kStates)
        throw std::runtime_error("XasrLiteRT: expected 117 inputs and 117 outputs, got " +
                                 std::to_string(graph_->num_inputs()) + "/" + std::to_string(graph_->num_outputs()));
    if (graph_->input_bytes(0) != (size_t) (kWindow * kFeatDim * sizeof(float)))
        throw std::runtime_error("XasrLiteRT: input 0 is not a 61x80 fp32 window");
    if (graph_->output_bytes(0) != (size_t) (kEncFrames * kEncDim * sizeof(float)))
        throw std::runtime_error("XasrLiteRT: output 0 is not a 12x512 fp32 encoder output");
    state_.resize(kStates);
    for (int i = 0; i < kStates; i++) {
        // The state fed to input i+1 must come back, same size, from output i+1.
        if (graph_->input_bytes(i + 1) != graph_->output_bytes(i + 1))
            throw std::runtime_error("XasrLiteRT: state " + std::to_string(i) + " input/output sizes differ");
        state_[(size_t) i].assign(graph_->input_bytes(i + 1), 0);
    }
}

XasrLiteRT::~XasrLiteRT() = default;

void XasrLiteRT::reset() {
    for (auto & s : state_)
        std::memset(s.data(), 0, s.size());
}

void XasrLiteRT::chunk(const float * feats, int T, int F, float * enc_out, int Eo, int Ed) {
    if (T != kWindow || F != kFeatDim || Eo != kEncFrames || Ed != kEncDim)
        throw std::runtime_error("XasrLiteRT: unexpected chunk geometry");
    graph_->write_input(0, feats, (size_t) (kWindow * kFeatDim) * sizeof(float));
    for (int i = 0; i < kStates; i++)
        graph_->write_input(i + 1, state_[(size_t) i].data(), state_[(size_t) i].size());
    graph_->run();
    graph_->read_output(0, enc_out, (size_t) (kEncFrames * kEncDim) * sizeof(float));
    for (int i = 0; i < kStates; i++)
        graph_->read_output(i + 1, state_[(size_t) i].data(), state_[(size_t) i].size());
}

int xasr_litert_chunk_cb(void * user, const float * feats, int T, int feat_dim, float * enc_out, int enc_frames, int enc_dim) {
    try {
        static_cast<XasrLiteRT *>(user)->chunk(feats, T, feat_dim, enc_out, enc_frames, enc_dim);
        return 1;
    } catch (const std::exception & e) {
        fprintf(stderr, "xasr_litert: %s\n", e.what());
        return 0;
    }
}

void xasr_litert_reset_cb(void * user) {
    static_cast<XasrLiteRT *>(user)->reset();
}

}  // namespace nemo
