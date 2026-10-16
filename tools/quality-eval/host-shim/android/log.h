// Host shim for <android/log.h>: the reader's JNI (app/src/main/cpp/mfa) built for the JVM on Linux.
#pragma once
#include <cstdio>
#include <cstdarg>
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    std::fprintf(stderr, "%c/%s: ", "??VDIWE"[prio < 7 ? prio : 0], tag);
    int n = std::vfprintf(stderr, fmt, ap); std::fputc('\n', stderr);
    va_end(ap); return n;
}
static inline int __android_log_write(int prio, const char* tag, const char* msg) { return __android_log_print(prio, tag, "%s", msg); }
