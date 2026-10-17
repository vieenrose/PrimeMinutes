// speaker_jni.cpp - JNI of studio.primeminutes.core.people.SpeakerEmbedNative (speaker_embed.h).
#include <jni.h>
#include <vector>
#include "speaker_embed.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_primeminutes_core_people_SpeakerEmbedNative_nativeCreate(JNIEnv * env, jclass, jstring path, jint threads, jint nMels, jboolean meanNorm, jboolean int16) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    void * h = pm_spk_create(p, threads, nMels, meanNorm ? 1 : 0, int16 ? 1 : 0);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) h;
}

JNIEXPORT jfloatArray JNICALL
Java_studio_primeminutes_core_people_SpeakerEmbedNative_nativeEmbed(JNIEnv * env, jclass, jlong h, jfloatArray pcm, jintArray lens) {
    const jsize n = env->GetArrayLength(lens);
    std::vector<int> l((size_t) n);
    env->GetIntArrayRegion(lens, 0, n, l.data());
    jfloat * x = env->GetFloatArrayElements(pcm, nullptr);
    const int dim = pm_spk_dim((void *) h);
    std::vector<float> out((size_t) (dim > 0 ? dim : 1));
    const int k = pm_spk_embed((void *) h, x, l.data(), (int) n, out.data(), (int) out.size());
    env->ReleaseFloatArrayElements(pcm, x, JNI_ABORT);
    if (k <= 0) return nullptr;
    jfloatArray r = env->NewFloatArray(k);
    env->SetFloatArrayRegion(r, 0, k, out.data());
    return r;
}

JNIEXPORT void JNICALL
Java_studio_primeminutes_core_people_SpeakerEmbedNative_nativeFree(JNIEnv *, jclass, jlong h) { pm_spk_free((void *) h); }

}
