// C bridge for the nemo-x-asr-diarizer streaming engine — the iOS twin of nemo_jni.cpp (same string encoding).
// Segments cross the boundary as one UTF-8 string: records separated by RS (0x1e), fields by US (0x1f):
//   speaker US start_s US end_s US text [US ms,ms,...]   nemo_live() joins frozen and tail with GS (0x1d).
// The optional last field (nemo_finish_timed only) gives each codepoint's start in ms after start_s.
#ifndef NEMO_C_H
#define NEMO_C_H
#ifdef __cplusplus
extern "C" {
#endif

typedef struct nemo_handle nemo_handle;

// NULL on failure (the reason goes to stderr).
nemo_handle* nemo_create(const char* xasr_path, const char* diar_path, int threads, double settle_s);
// backend: "ggml" | "litert_cpu" (CPU only). The tflite paths are read only when backend != ggml.
// diar_pre_f32 (litert_cpu only, NULL/"" = audio.cpp ggml linear): the 512x1024 fp32 pre-encode matrix.
nemo_handle* nemo_create_ex(const char* xasr_path, const char* diar_path, int threads, double settle_s,
                            const char* backend, const char* xasr_tflite, const char* diar_tflite,
                            const char* diar_pre_f32, double token_offset_ms);   // token_offset_ms: model-specific (app ASR registry)
int nemo_push(nemo_handle* h, const float* pcm, int n);                 // 1 = ok
// Heap string, free with nemo_string_free.
char* nemo_live(nemo_handle* h);
char* nemo_finish(nemo_handle* h);                                       // NULL on failure
// nemo_finish plus a 5th field per segment: each codepoint's start in ms after start_s ("0,40,120,...").
char* nemo_finish_timed(nemo_handle* h);                                 // NULL on failure
double nemo_fed_seconds(nemo_handle* h);
void nemo_string_free(char* s);
void nemo_free(nemo_handle* h);

#ifdef __cplusplus
}
#endif
#endif
