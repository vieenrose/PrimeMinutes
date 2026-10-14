package studio.primeminutes.core.embed

import java.io.Closeable
import java.io.File

/**
 * EmbeddingGemma-2 270M on LiteRT (`cpp/mfa/embedder.cc`): text → unit vector of [DIM] floats.
 * Used by the library's semantic search ([SessionIndex], [LibrarySearch]). One instance per loaded
 * model; calls are serialized by [EmbedHost]. Not thread-safe by itself.
 */
class Embedder private constructor(@Volatile private var handle: Long) : Closeable {

    private val lock = Any()

    /** The vector of [text], L2-normalized ([DIM] floats). [text] must already carry its task prefix
     *  ([Prompts]); it is truncated at 512 tokens by the native side. */
    fun embed(text: String): FloatArray = synchronized(lock) {
        check(handle != 0L) { "embedder closed" }
        nativeEmbed(handle, text)
    }

    /** Number of SentencePiece tokens in [text] (no bos/eos): the chunker's budget unit. */
    fun tokens(text: String): Int = synchronized(lock) {
        check(handle != 0L) { "embedder closed" }
        nativeCount(handle, text)
    }

    override fun close() { synchronized(lock) { if (handle != 0L) { nativeFree(handle); handle = 0L; live.decrementAndGet() } } }

    companion object {
        init { System.loadLibrary("primeminutes-mfa") }

        const val DIM = 256

        /** Number of native embedders currently alive — the tests' handle assertion (T4). */
        val live = java.util.concurrent.atomic.AtomicInteger(0)

        /** Loads the three files of [dir] (embedder.tflite, encoder.tflite, tokenizer.spiece). */
        fun load(dir: File, threads: Int): Embedder {
            val h = nativeLoad(dir.absolutePath, threads)
            check(h != 0L) { "embedder load failed" }
            live.incrementAndGet()
            return Embedder(h)
        }

        @JvmStatic private external fun nativeLoad(dir: String, threads: Int): Long
        @JvmStatic private external fun nativeEmbed(h: Long, text: String): FloatArray
        @JvmStatic private external fun nativeCount(h: Long, text: String): Int
        @JvmStatic private external fun nativeFree(h: Long)
    }

    /** The EmbeddingGemma prompt templates (model card): queries and documents get different prefixes. */
    object Prompts {
        fun query(q: String) = "task: search result | query: $q"
        fun document(text: String) = "title: none | text: $text"
    }
}
