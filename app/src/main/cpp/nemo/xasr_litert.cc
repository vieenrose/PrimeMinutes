// xasr_litert.cc - see xasr_litert.h.
#include "xasr_litert.h"

#include <cstring>
#include <stdexcept>

#include "litert_graph.h"

namespace nemo {

namespace {
constexpr int kStates = 116;
}

XasrLiteRT::XasrLiteRT(const std::string & tflite_path, int threads)
    : graph_(std::make_unique<LiteRtGraph>(tflite_path, threads)) {
    if (graph_->num_inputs() != 1 + kStates || graph_->num_outputs() != 1 + kStates)
        throw std::runtime_error("XasrLiteRT: expected 117 inputs and 117 outputs, got " +
                                 std::to_string(graph_->num_inputs()) + "/" + std::to_string(graph_->num_outputs()));
    const size_t in0 = graph_->input_bytes(0), out0 = graph_->output_bytes(0), row_in = kFeatDim * sizeof(float),
                 row_out = kEncDim * sizeof(float);
    if (in0 % row_in || out0 % row_out) throw std::runtime_error("XasrLiteRT: input 0 / output 0 are not [T,80] / [T',512] fp32");
    window_ = (int) (in0 / row_in);
    enc_frames_ = (int) (out0 / row_out);
    const int chunk = (window_ - 13) / 2;
    if (window_ < 15 || (window_ - 13) % 2 || chunk % 2 || enc_frames_ != chunk / 2)
        throw std::runtime_error("XasrLiteRT: unexpected chunk geometry " + std::to_string(window_) + " -> " + std::to_string(enc_frames_));
    feat_h_ = graph_->alloc_input(0);
    enc_h_ = graph_->alloc_output(0);
    for (int s = 0; s < 2; s++)
        for (int i = 0; i < kStates; i++) {
            // The state fed to input i+1 must come back, same size, from output i+1.
            if (graph_->input_bytes(i + 1) != graph_->output_bytes(i + 1))
                throw std::runtime_error("XasrLiteRT: state " + std::to_string(i) + " input/output sizes differ");
            // One buffer per state and set: a set is an input in one step and the output of the next.
            states_[s][i] = graph_->alloc_input(i + 1);
        }
    reset();
}

XasrLiteRT::~XasrLiteRT() = default;

void XasrLiteRT::reset() {
    for (int s = 0; s < 2; s++)
        for (int i = 0; i < kStates; i++) {
            std::vector<uint8_t> z(graph_->input_bytes(i + 1), 0);
            graph_->write_buffer(states_[s][i], z.data(), z.size());
        }
    cur_ = 0;
}

void XasrLiteRT::chunk(const float * feats, int T, int F, float * enc_out, int Eo, int Ed) {
    if (T != window_ || F != kFeatDim || Eo != enc_frames_ || Ed != kEncDim)
        throw std::runtime_error("XasrLiteRT: unexpected chunk geometry");
    graph_->write_buffer(feat_h_, feats, (size_t) (window_ * kFeatDim) * sizeof(float));
    std::vector<int> in{feat_h_}, out{enc_h_};
    for (int i = 0; i < kStates; i++) in.push_back(states_[cur_][i]);
    for (int i = 0; i < kStates; i++) out.push_back(states_[1 - cur_][i]);
    graph_->run_bound(in, out);
    graph_->read_buffer(enc_h_, enc_out, (size_t) (enc_frames_ * kEncDim) * sizeof(float));
    cur_ = 1 - cur_;
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
