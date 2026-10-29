package studio.primeminutes.online

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Channel artwork, downloaded ONCE and kept on the device (`filesDir/artwork/<key>.jpg`, at most
 * [MAX_PX] px a side, a few KB): the library draws podcast rows offline. Best-effort: a failed fetch
 * is not retried in the same run, and nothing ever waits for it (the row shows a generic tile).
 * [key]: the channel's subscription id when known, else a hash of the artwork URL ([keyOf]).
 */
object ArtworkCache {
    private const val TAG = "ArtworkCache"
    const val MAX_PX = 256
    private const val MAX_BYTES = 8L * 1024 * 1024

    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun dir(ctx: Context) = File(ctx.filesDir, "artwork")

    /** The cache key of a channel: its series id, else the artwork URL's hash; null with neither. */
    fun keyOf(seriesId: String?, artworkUrl: String?): String? =
        seriesId?.takeIf { it.isNotBlank() }?.let(::safe) ?: artworkUrl?.takeIf { it.isNotBlank() }?.let { "u-" + SubscriptionLogic.fnv1a(it) }

    private fun safe(s: String) = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)

    fun file(ctx: Context, key: String) = File(dir(ctx), "$key.jpg")

    fun cached(ctx: Context, key: String): File? = file(ctx, key).takeIf { it.isFile && it.length() > 0 }

    /** The cached artwork of [key], fetching [url] first when it is not there yet. Null on any failure. */
    suspend fun ensure(ctx: Context, key: String, url: String?): File? = withContext(Dispatchers.IO) {
        cached(ctx, key)?.let { return@withContext it }
        if (url.isNullOrBlank() || key in failed || !(url.startsWith("https://") || url.startsWith("http://"))) return@withContext null
        locks.getOrPut(key) { Mutex() }.withLock {
            cached(ctx, key)?.let { return@withLock it }
            runCatching { fetch(ctx, key, url) }
                .onFailure { failed += key; Log.w(TAG, "artwork $key not cached: ${it.message}") }
                .getOrNull()
        }
    }

    private fun fetch(ctx: Context, key: String, url: String): File {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PrimeMinutes")
        }
        val bytes = try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) error("artwork over ${MAX_BYTES / 1024 / 1024} MB")
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("not an image")
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_PX) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("undecodable")
        val (w, h) = scaled(decoded.width, decoded.height, MAX_PX)
        val bmp = if (w != decoded.width || h != decoded.height) Bitmap.createScaledBitmap(decoded, w, h, true) else decoded
        dir(ctx).mkdirs()
        val dest = file(ctx, key)
        val tmp = File(dest.parentFile, "$key.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        Log.i(TAG, "artwork $key cached: ${w}x$h, ${dest.length()} B")
        return dest
    }

    /** Drops cached artwork no item or subscription uses any more ([keep]: the keys still in use). */
    fun prune(ctx: Context, keep: Set<String>) {
        dir(ctx).listFiles()?.forEach { f -> if (f.nameWithoutExtension !in keep) runCatching { f.delete() } }
    }

    /** Largest power-of-two subsampling that keeps both sides at or above [target]. */
    fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }

    /** [w]×[h] scaled down (never up) so the longer side is at most [max]. */
    fun scaled(w: Int, h: Int, max: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= max) return w to h
        val f = max.toDouble() / longest
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }
}
