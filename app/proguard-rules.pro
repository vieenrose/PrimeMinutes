# Keep JNI entry points reachable from native code.
# (The X-ASR/Nemotron JNI binds by class+method name — those classes are referenced from
#  Kotlin so R8 keeps them, and the release dex is checked in CI.)

# The reader engine (cpp/mfa/mfa_jni.cpp) resolves GetMethodID(<callback's class>, "onToken", "(I)Z")
# by name. Without this rule R8 merges the interface into its lambda class and renames the method:
# every reading died with `no non-static method "Lt0/P0;.onToken(I)Z"` in release builds (0.48+,
# issue #5) — debug builds are not minified, so nothing caught it.
-keep class studio.primeminutes.core.llm.MfaEngine$TokenCallback { *; }
-keepclassmembers class * implements studio.primeminutes.core.llm.MfaEngine$TokenCallback { *; }

# (The llama.cpp bridge (LlmEngine / TextGen) and the sherpa diarizer callback (SegProgress) are
#  gone, and their keep rules with them.)

# Nicer crash traces in release.
-keepattributes SourceFile,LineNumberTable

# WorkManager (0.56+, the podcast background check) starts at launch through androidx.startup and
# builds its Room database, which Room instantiates REFLECTIVELY: Class.forName("…WorkDatabase_Impl")
# .getDeclaredConstructor(). R8 dropped that no-arg constructor, so every release build since 0.56.0
# died before the first frame with NoSuchMethodException: WorkDatabase_Impl.<init> [] (issue #9).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
