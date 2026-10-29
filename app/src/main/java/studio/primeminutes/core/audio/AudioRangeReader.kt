package studio.primeminutes.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Random access to a compressed recording as 16 kHz mono: [read] seeks to a time range and decodes only that range
 * (one extractor and one decoder for the whole session, flushed per seek). The voice lane reads a speaker's selected
 * lines with it instead of decoding the whole meeting to a WAV first, so an hour costs about what a minute does.
 *
 * Samples are placed on the decoder's presentation timeline, the timeline of [AudioDecoder]'s full decode (which
 * emits every output sample from t = 0), so the line times of the transcript address the same audio.
 */
class AudioRangeReader(context: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        require(track != null) { "No audio track in $uri" }
        extractor.selectTrack(track)
        val fmt = extractor.getTrackFormat(track)
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    /** Samples [start, end) seconds at 16 kHz (shorter at the end of the file). */
    fun read(start: Double, end: Double): FloatArray {
        if (end <= start) return FloatArray(0)
        val startUs = (start * 1e6).toLong(); val endUs = (end * 1e6).toLong()
        extractor.seekTo(maxOf(0L, startUs - PRE_ROLL_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        // Mono source-rate samples from the first decoded buffer on, and that buffer's time.
        var src = FloatArray(1 shl 16); var n = 0
        var baseUs = -1L; var rate = 0; var channels = 0
        var inEos = false; var polls = 0
        while (true) {
            if (!inEos) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val sz = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (sz < 0 || extractor.sampleTime > endUs + POST_ROLL_US) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                    } else { codec.queueInputBuffer(i, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (o >= 0 && rate == 0)) {
                val f = codec.outputFormat
                rate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
                channels = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
                check(rate > 0 && channels > 0) { "bad decoder output format" }
            }
            if (o < 0) { if (inEos && ++polls > MAX_POLLS) break; continue }
            polls = 0
            if (info.size > 0) {
                if (baseUs < 0) baseUs = info.presentationTimeUs
                val b = codec.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset + info.size)
                val s = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = s.remaining() / channels
                if (n + frames > src.size) src = src.copyOf(maxOf(src.size * 2, n + frames))
                for (k in 0 until frames) { var acc = 0; for (c in 0 until channels) acc += s.get().toInt(); src[n++] = acc.toFloat() / channels / 32768f }
            }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        if (baseUs < 0 || n == 0) return FloatArray(0)
        // Linear resampling onto the 16 kHz grid of [start, end).
        val want = ((end - start) * AudioDecoder.SAMPLE_RATE).toInt()
        val out = FloatArray(want)
        val off = (start - baseUs / 1e6) * rate
        val step = rate.toDouble() / AudioDecoder.SAMPLE_RATE
        var m = 0
        while (m < want) {
            val p = off + m * step
            val i = p.toInt()
            if (i < 0) { m++; continue }
            if (i + 1 >= n) break
            val f = (p - i).toFloat()
            out[m] = src[i] * (1 - f) + src[i + 1] * f
            m++
        }
        return if (m < want) out.copyOf(m) else out
    }

    override fun close() {
        runCatching { codec.stop() }; runCatching { codec.release() }; extractor.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_POLLS = 300
        /** Decoded before the range: a seek lands on the previous sync frame, and AAC needs a frame of warm-up. */
        const val PRE_ROLL_US = 100_000L
        const val POST_ROLL_US = 100_000L
    }
}
