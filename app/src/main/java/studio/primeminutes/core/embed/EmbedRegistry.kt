package studio.primeminutes.core.embed

/**
 * The library's embedding model: EmbeddingGemma-2 270M, int4, repacked for LiteRT
 * (`tools/embed-export/`, HF `Luigi/primeminutes-embed-litert`). Same shape as the reader's
 * [studio.primeminutes.core.models.LlmSpec]: a revision-pinned set of sha256-verified files under
 * one directory of ModelManager.
 */
data class EmbedSpec(
    val id: String,
    val displayName: String,
    val dirName: String,
    /** HF `resolve/<commit>` base URL. */
    val revision: String,
    /** relative path -> (sizeBytes, sha256). */
    val files: Map<String, Pair<Long, String>>,
) {
    val totalBytes: Long get() = files.values.sumOf { it.first }
}

object EmbedRegistry {
    const val DEFAULT_ID = "embeddinggemma2-270m"

    // NOTE: the HF repo is not published yet (the upload needs the user's go-ahead); the revision
    // below is `main` until a commit is pinned. The files' sizes and hashes are those of the
    // repack validated on the host (T6 cos ≥ 0.97 vs torch bf16).
    val GEMMA2_270M = EmbedSpec(
        id = DEFAULT_ID,
        displayName = "EmbeddingGemma 2 270M",
        dirName = "embed-gemma2-270m",
        revision = "https://huggingface.co/Luigi/primeminutes-embed-litert/resolve/main",
        files = mapOf(
            "embedder.tflite" to (70_257_800L to "25596c901d4cc9bb1156bdbff71829506b9137c48292993036464dab889ac3a3"),
            "encoder.tflite" to (89_540_120L to "912763557c5b892d6db76a95382ac9141fc55b1ee4e0ee786d454b70c12d8ccb"),
            "tokenizer.spiece" to (4_689_013L to "e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03"),
        ),
    )

    val ALL = listOf(GEMMA2_270M)
    fun byId(id: String): EmbedSpec = ALL.firstOrNull { it.id == id } ?: GEMMA2_270M
}
