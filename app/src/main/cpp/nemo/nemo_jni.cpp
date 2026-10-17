// JNI bridge for the nemo-x-asr-diarizer streaming engine (studio.primeminutes.core.asr.NemoNative).
//
// Segments cross the boundary as one string: records separated by RS (0x1e), fields by US (0x1f):
//   speaker US start_s US end_s US text [US ms,ms,...]
// The optional last field (final pass only) gives each codepoint's start in ms after start_s.
// Cheaper and simpler than building Java objects per segment, and the text never contains either byte.
#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "engine.h"
#include "fixed_fmt.h"

namespace {

struct Handle {
    std::unique_ptr<nemo::Engine> engine;
    std::mutex mu;
};

std::string encode_segment(const nemo::Segment& s) {
    // Times in fixed text, never printf's %f: it follows the C locale ("0,000" broke parseSegments).
    std::string out = std::to_string(s.speaker) + "\x1f" + nemo::fixed(s.start_s, 3) + "\x1f" + nemo::fixed(s.end_s, 3) +
                      "\x1f" + s.text;
    // Optional 5th field (final pass): each codepoint's start, in ms after start_s, comma-separated.
    if (!s.char_starts.empty()) {
        out += "\x1f";
        for (size_t i = 0; i < s.char_starts.size(); i++) {
            if (i) out += ',';
            out += std::to_string((long long)std::llround((s.char_starts[i] - s.start_s) * 1000.0));
        }
    }
    return out + "\x1e";
}

jstring to_jstring(JNIEnv* env, const std::string& s) {
    // NewStringUTF wants MODIFIED UTF-8; build from UTF-8 bytes via String(byte[], "UTF-8") instead so
    // 4-byte code points (emoji, CJK extension B) survive.
    jbyteArray bytes = env->NewByteArray((jsize)s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize)s.size(), reinterpret_cast<const jbyte*>(s.data()));
    jclass str = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(str, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto out = (jstring)env->NewObject(str, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(str);
    return out;
}

std::string from_jstring(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_primeminutes_core_asr_NemoNative_nativeCreate(JNIEnv* env, jclass, jstring xasr, jstring diar, jint threads,
                                                      jdouble settle_s, jstring backend, jstring xasr_tflite,
                                                      jstring diar_tflite, jstring diar_pre_f32) {
    nemo::Config cfg;
    cfg.xasr_model = from_jstring(env, xasr);
    cfg.diar_model = from_jstring(env, diar);
    cfg.backend = from_jstring(env, backend);
    cfg.xasr_tflite = from_jstring(env, xasr_tflite);
    cfg.diar_tflite = from_jstring(env, diar_tflite);
    cfg.diar_pre_f32 = from_jstring(env, diar_pre_f32);
    cfg.threads = threads;
    cfg.max_segment_s = 10.0;   // one transcript line per ~10 s sentence group
    cfg.live_settle_s = settle_s;
    auto h = std::make_unique<Handle>();
    h->engine = std::make_unique<nemo::Engine>(cfg);
    std::string err;
    if (!h->engine->init(err)) {
        __android_log_print(ANDROID_LOG_ERROR, "primeminutes-nemo", "init failed: %s", err.c_str());
        return 0;
    }
    h->engine->begin();
    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT jboolean JNICALL
Java_studio_primeminutes_core_asr_NemoNative_nativePush(JNIEnv* env, jclass, jlong ptr, jfloatArray pcm, jint n) {
    auto* h = reinterpret_cast<Handle*>(ptr);
    if (!pcm || n < 0 || n > env->GetArrayLength(pcm)) return JNI_FALSE;
    jfloat* p = env->GetFloatArrayElements(pcm, nullptr);
    if (!p) return JNI_FALSE;
    std::string err;
    bool ok;
    // A C++ exception must never cross JNI (it aborts the process, and the live capture with it):
    // the graphs throw on a LiteRT runtime error, so a failed piece is reported like any other.
    try {
        std::lock_guard<std::mutex> lk(h->mu);
        ok = h->engine->push(p, (size_t)n, err);
    } catch (const std::exception& e) { ok = false; err = e.what(); }
    env->ReleaseFloatArrayElements(pcm, p, JNI_ABORT);
    if (!ok) __android_log_print(ANDROID_LOG_ERROR, "primeminutes-nemo", "push failed: %s", err.c_str());
    return ok;
}

// Live view: newly frozen segments, GS (0x1d), then the provisional tail.
JNIEXPORT jstring JNICALL
Java_studio_primeminutes_core_asr_NemoNative_nativeLive(JNIEnv* env, jclass, jlong ptr) {
    auto* h = reinterpret_cast<Handle*>(ptr);
    std::vector<nemo::Segment> frozen, tail;
    try {
        std::lock_guard<std::mutex> lk(h->mu);
        h->engine->live(frozen, tail);
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "primeminutes-nemo", "live failed: %s", e.what());
        frozen.clear(); tail.clear();
    }
    std::string out;
    for (const auto& s : frozen) out += encode_segment(s);
    out += "\x1d";
    for (const auto& s : tail) out += encode_segment(s);
    return to_jstring(env, out);
}

JNIEXPORT jstring JNICALL
Java_studio_primeminutes_core_asr_NemoNative_nativeFinish(JNIEnv* env, jclass, jlong ptr) {
    auto* h = reinterpret_cast<Handle*>(ptr);
    std::string out, err;
    std::lock_guard<std::mutex> lk(h->mu);
    bool ok;
    try { ok = h->engine->finish([&](const nemo::Segment& s) { out += encode_segment(s); }, err); }
    catch (const std::exception& e) { ok = false; err = e.what(); }
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, "primeminutes-nemo", "finish failed: %s", err.c_str());
        return nullptr;
    }
    const auto& st = h->engine->stats();
    __android_log_print(ANDROID_LOG_INFO, "primeminutes-nemo",
                        "audio %.1fs wall %.1fs rtf %.3f speakers %zu turns %zu rss %.0fMB",
                        st.audio_s, st.wall_s, st.audio_s > 0 ? st.wall_s / st.audio_s : 0.0,
                        st.speakers, st.turns, st.peak_rss_mb);
    return to_jstring(env, out);
}

JNIEXPORT jdouble JNICALL
Java_studio_primeminutes_core_asr_NemoNative_nativeFedSec(JNIEnv*, jclass, jlong ptr) {
    auto* h = reinterpret_cast<Handle*>(ptr);
    std::lock_guard<std::mutex> lk(h->mu);
    return h->engine->fed_s();
}

// Timing of the last finished stream, for the in-app benchmark: the ASR and diarizer compute split,
// load (which includes the weight prefault), first partial and the per-piece latency tail.
JNIEXPORT jstring JNICALL
Java_studio_primeminutes_core_asr_NemoNative_nativeStats(JNIEnv* env, jclass, jlong ptr) {
    auto* h = reinterpret_cast<Handle*>(ptr);
    std::lock_guard<std::mutex> lk(h->mu);
    const auto& st = h->engine->stats();
    using nemo::fixed;   // locale-independent (see fixed_fmt.h)
    const std::string json = "{\"audio_s\":" + fixed(st.audio_s, 4) + ",\"wall_s\":" + fixed(st.wall_s, 4) +
        ",\"load_s\":" + fixed(st.load_s, 4) + ",\"asr_s\":" + fixed(st.asr_compute_s, 4) +
        ",\"diar_s\":" + fixed(st.diar_compute_s, 4) + ",\"first_partial_s\":" + fixed(st.first_partial_s, 4) +
        ",\"piece_p95_ms\":" + fixed(st.piece_p95_ms, 2) + ",\"piece_max_ms\":" + fixed(st.piece_max_ms, 2) +
        ",\"speakers\":" + std::to_string(st.speakers) + ",\"turns\":" + std::to_string(st.turns) +
        ",\"peak_rss_mb\":" + fixed(st.peak_rss_mb, 1) + "}";
    return to_jstring(env, json);
}

JNIEXPORT void JNICALL
Java_studio_primeminutes_core_asr_NemoNative_nativeFree(JNIEnv*, jclass, jlong ptr) {
    delete reinterpret_cast<Handle*>(ptr);
}

}  // extern "C"
