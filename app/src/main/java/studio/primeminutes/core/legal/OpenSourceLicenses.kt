package studio.primeminutes.core.legal

/**
 * The third-party components inside the Android app, with their licenses: what Settings › About ›
 * Open-source licenses lists. Kept in step with NOTICE at the repository root (the same entries,
 * in the same order) — a dependency added or removed in Gradle, native/ or the model registry
 * changes both.
 */
object OpenSourceLicenses {
    data class Component(val name: String, val license: String, val url: String)
    data class Group(val title: String, val items: List<Component>)

    val groups: List<Group> = listOf(
        Group("Speech, speakers and AI notes (native)", listOf(
            Component("nemo-x-asr-diarizer", "Apache-2.0", "https://github.com/vieenrose/nemo-x-asr-diarizer.cpp"),
            Component("audio.cpp", "Apache-2.0", "https://github.com/vieenrose/audio.cpp"),
            Component("CrispASR", "MIT", "https://github.com/vieenrose/CrispASR"),
            Component("ggml", "MIT", "https://github.com/ggml-org/ggml"),
            Component("llamafile sgemm", "MIT", "https://github.com/Mozilla-Ocho/llamafile"),
            Component("nlohmann/json", "MIT", "https://github.com/nlohmann/json"),
            Component("LiteRT", "Apache-2.0", "https://github.com/google-ai-edge/LiteRT"),
            Component("LiteRT-LM (mobile fused attention fork)", "Apache-2.0", "https://github.com/vieenrose/LiteRT-LM"),
            Component("SentencePiece", "Apache-2.0", "https://github.com/google/sentencepiece"),
        )),
        Group("Models and data (downloaded or bundled)", listOf(
            Component("X-ASR zh-en speech model", "Apache-2.0", "https://huggingface.co/Luigi/primeminutes-nemo-litert"),
            Component("Nemotron-3 Diarization model", "OpenMDW-1.1", "https://huggingface.co/Luigi/primeminutes-nemo-litert"),
            Component("Gemma-4 E2B / E4B meeting models", "Apache-2.0", "https://huggingface.co/Luigi/gemma-4-E4B-meeting-agent-zh-LiteRT"),
            Component("EmbeddingGemma 2 270M", "Gemma Terms of Use", "https://huggingface.co/Luigi/primeminutes-embed-litert"),
            Component("CAM++ zh-en (3D-Speaker)", "Apache-2.0", "https://huggingface.co/Luigi/primeminutes-speaker-litert"),
            Component("OpenCC dictionaries", "Apache-2.0", "https://github.com/BYVoid/OpenCC"),
        )),
        Group("Android libraries", listOf(
            Component("AndroidX (Core, Lifecycle, Activity)", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
            Component("Jetpack Compose, Material 3, Material Icons", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
            Component("Kotlin standard library, kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
            Component("desugar_jdk_libs", "GPL-2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs"),
        )),
    )
}
