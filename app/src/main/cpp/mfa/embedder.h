// embedder.h - EmbeddingGemma-2 270M (int4, LiteRT) text embedder for the library's semantic search.
//
// Two graphs repacked from the LiteRT-LM bundle (tools/embed-export/repack.py): `embedder.tflite`
// (token id -> scaled 512-d input embedding) and `encoder.tflite` (padded embeddings + mask ->
// 768-d mean-pooled sentence vector, encoder_1x{128,256,512}). The SentencePiece tokenizer is the
// Gemma one. Pipeline (LiteRT-LM embedding_engine_impl.cc): bos + sp(text) + eos, mask 1.0 on
// real tokens, smallest bucket that fits, truncation at the largest bucket; the output is cut to
// DIM (Matryoshka) and L2-normalized here, so a dot product is a cosine.
#pragma once

#include <memory>
#include <string>
#include <vector>

namespace mfa {

class Embedder {
public:
    static constexpr int DIM = 256;       // Matryoshka cut of the 768-d output
    static constexpr int MAX_TOKENS = 512; // largest encoder bucket loaded

    /// Loads `<dir>/embedder.tflite`, `<dir>/encoder.tflite`, `<dir>/tokenizer.spiece`.
    Embedder(const std::string& dir, int threads);
    ~Embedder();

    /// Embeds `text` (prompt prefix already applied by the caller) into `out[DIM]`, normalized.
    /// Returns the number of tokens actually encoded (bos/eos included, after truncation).
    int embed(const std::string& text, float* out);

    /// Number of SentencePiece tokens in `text` (no bos/eos) — the chunker's budget unit.
    int count(const std::string& text) const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace mfa
