# Keep JNI entry points reachable from native code.
# (The X-ASR/Nemotron JNI binds by class+method name — those classes are referenced from
#  Kotlin so R8 keeps them, and the release dex is checked in CI.)

# The reader engine (cpp/mfa/mfa_jni.cpp) resolves GetMethodID(<callback's class>, "onToken", "(I)Z")
# by name. Without this rule R8 merges the interface into its lambda class and renames the method:
# every reading died with `no non-static method "Lt0/P0;.onToken(I)Z"` in release builds (0.48+,
# issue #5) — debug builds are not minified, so nothing caught it.
-keep class studio.primeminutes.core.llm.MfaEngine$TokenCallback { *; }
-keepclassmembers class * implements studio.primeminutes.core.llm.MfaEngine$TokenCallback { *; }

# NewPipeExtractor (YouTube) bundles Mozilla Rhino, which references desktop-JVM classes
# (java.beans.*, javax.*) that don't exist on Android. R8 fails the release build on these
# missing references unless told to ignore them. Rhino + the extractor also use reflection,
# so keep them intact.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn javax.servlet.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**

# (The llama.cpp bridge (LlmEngine / TextGen) and the sherpa diarizer callback (SegProgress) are
#  gone, and their keep rules with them.)

# Nicer crash traces in release.
-keepattributes SourceFile,LineNumberTable
