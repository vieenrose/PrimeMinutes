// diar_litert.cc - see diar_litert.h.
#include "diar_litert.h"

#include <cstring>
#include <stdexcept>

#include "litert_graph.h"

namespace nemo {

DiarLiteRT::DiarLiteRT(const std::string & tflite_path, int threads)
    : graph_(std::make_unique<LiteRtGraph>(tflite_path, threads)) {
    if (graph_->num_inputs() != 2 || graph_->num_outputs() != 1)
        throw std::runtime_error("DiarLiteRT: expected 2 inputs and 1 output, got " +
                                 std::to_string(graph_->num_inputs()) + "/" + std::to_string(graph_->num_outputs()));
    // Identify the inputs by size (the signature order is not fixed by the converter): valid_frames is one int64.
    const size_t emb_bytes = (size_t) (kMaxFrames * kHidden * sizeof(float));
    // A GPU-exported graph (--float-valid) takes valid_frames as one float32 instead (no INT64 ops).
    const size_t vb0 = graph_->input_bytes(0), vb1 = graph_->input_bytes(1);
    if (vb1 == emb_bytes && (vb0 == sizeof(int64_t) || vb0 == sizeof(float))) {
        emb_input_ = 1;
        valid_input_ = 0;
        valid_float_ = vb0 == sizeof(float);
    } else if (vb0 == emb_bytes && (vb1 == sizeof(int64_t) || vb1 == sizeof(float))) {
        valid_float_ = vb1 == sizeof(float);
    } else {
        throw std::runtime_error("DiarLiteRT: input sizes do not match a 190-frame Sortformer graph");
    }
    if (graph_->output_bytes(0) != (size_t) (kUpsample * kMaxFrames * kSpeakers * sizeof(float)))
        throw std::runtime_error("DiarLiteRT: output size does not match a 190-frame Sortformer graph");
}

DiarLiteRT::~DiarLiteRT() = default;

std::vector<float> DiarLiteRT::encode(
    const std::vector<float> & embeddings,
    int64_t batch,
    int64_t frames,
    const std::vector<int64_t> & valid_frames) {
    if (batch != 1 || valid_frames.size() != 1)
        throw std::runtime_error("DiarLiteRT: only batch == 1 is supported");
    if (frames > kMaxFrames)
        throw std::runtime_error("DiarLiteRT: window of " + std::to_string(frames) +
                                 " frames exceeds the exported graph (" + std::to_string(kMaxFrames) + ")");
    if (frames <= 0 || (int64_t) embeddings.size() != frames * kHidden)
        throw std::runtime_error("DiarLiteRT: embeddings size mismatch");
    const int64_t valid = valid_frames[0];
    if (valid < 0 || valid > frames)
        throw std::runtime_error("DiarLiteRT: valid_frames out of range");

    // Zero-pad to the exported length; the graph masks everything past `valid` itself.
    std::vector<float> padded((size_t) (kMaxFrames * kHidden), 0.0f);
    std::memcpy(padded.data(), embeddings.data(), embeddings.size() * sizeof(float));
    graph_->write_input(emb_input_, padded.data(), padded.size() * sizeof(float));
    if (valid_float_) { const float vf = (float) valid; graph_->write_input(valid_input_, &vf, sizeof vf); }
    else graph_->write_input(valid_input_, &valid, sizeof valid);

    graph_->run();

    std::vector<float> full((size_t) (kUpsample * kMaxFrames * kSpeakers));
    graph_->read_output(0, full.data(), full.size() * sizeof(float));
    // Keep the first 8*frames rows: the valid part of the output, as DiarCrispASR returns.
    full.resize((size_t) (kUpsample * frames * kSpeakers));
    return full;
}

}  // namespace nemo
