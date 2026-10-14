// C surface of the EmbeddingGemma-2 text embedder (embedder.h) — the iOS twin of embedder_jni.cpp.
#ifndef EMBED_C_H
#define EMBED_C_H
#ifdef __cplusplus
extern "C" {
#endif

typedef struct embed_handle embed_handle;

#define EMBED_DIM 256

// Loads `<dir>/embedder.tflite`, `<dir>/encoder.tflite`, `<dir>/tokenizer.spiece`. NULL on failure (reason in err).
embed_handle* embed_load(const char* dir, int threads, char* err, int err_len);
// Embeds `text` (prompt prefix already applied) into out[EMBED_DIM], L2-normalized. Returns the token
// count (bos/eos included, after truncation at 512), or -1 with the reason in err.
int embed_embed(embed_handle* h, const char* text, float* out, char* err, int err_len);
// SentencePiece token count of `text` (no bos/eos); -1 when h is NULL.
int embed_count(embed_handle* h, const char* text);
void embed_free(embed_handle* h);

#ifdef __cplusplus
}
#endif
#endif
