// embedder_jni.cpp - JNI surface of the semantic-search embedder (studio.primeminutes.core.embed.Embedder).
// One handle per loaded model; calls on a handle are serialized by the Kotlin side.
#include <jni.h>
#include <android/log.h>

#include <memory>
#include <string>

#include "embedder.h"

namespace {

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

void throwJava(JNIEnv* env, const std::string& msg) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, msg.c_str());
}

mfa::Embedder* emb(jlong h) { return reinterpret_cast<mfa::Embedder*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_primeminutes_core_embed_Embedder_nativeLoad(JNIEnv* env, jclass, jstring dir, jint threads) {
    try {
        return reinterpret_cast<jlong>(new mfa::Embedder(str(env, dir), threads));
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "primeminutes-embed", "load failed: %s", e.what());
        throwJava(env, std::string("embedder load: ") + e.what());
        return 0;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_studio_primeminutes_core_embed_Embedder_nativeEmbed(JNIEnv* env, jclass, jlong h, jstring text) {
    try {
        float v[mfa::Embedder::DIM];
        emb(h)->embed(str(env, text), v);
        jfloatArray out = env->NewFloatArray(mfa::Embedder::DIM);
        env->SetFloatArrayRegion(out, 0, mfa::Embedder::DIM, v);
        return out;
    } catch (const std::exception& e) {
        throwJava(env, std::string("embed: ") + e.what());
        return nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_studio_primeminutes_core_embed_Embedder_nativeCount(JNIEnv* env, jclass, jlong h, jstring text) {
    return emb(h)->count(str(env, text));
}

JNIEXPORT void JNICALL
Java_studio_primeminutes_core_embed_Embedder_nativeFree(JNIEnv*, jclass, jlong h) {
    delete emb(h);
}

}  // extern "C"
