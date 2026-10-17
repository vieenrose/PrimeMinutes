// C surface of the speaker embedder (app/src/main/cpp/nemo/speaker_embed.h, built into libprimeminutes-nemo.a):
// the iOS twin of speaker_jni.cpp. The front-end values come from the speaker card (SpeakerCard).
#ifndef SPEAKER_C_H
#define SPEAKER_C_H
#ifdef __cplusplus
extern "C" {
#endif

// The graph at `tflite` on LiteRT CPU; NULL on failure.
void * pm_spk_create(const char * tflite, int threads, int n_mels, int mean_norm, int int16_scale);
int pm_spk_dim(void * h);
// pcm: the segments back to back (16 kHz mono, [-1, 1]); lens: their lengths in samples. Writes dim floats
// (L2-normalised) to out; returns their count, 0 when there is no speech, -1 on error.
int pm_spk_embed(void * h, const float * pcm, const int * lens, int n_segments, float * out, int out_cap);
void pm_spk_free(void * h);

#ifdef __cplusplus
}
#endif
#endif
