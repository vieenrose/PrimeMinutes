package studio.primeminutes.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import studio.primeminutes.core.audio.WavIo
import java.io.File

/** Raw PCM16 of one of our own 16 kHz mono work WAVs, as float blocks. */
internal fun wavChunks(wav: File, startSec: Double = 0.0, endSec: Double = Double.MAX_VALUE): Flow<FloatArray> = flow {
    java.io.DataInputStream(wav.inputStream().buffered(1 shl 16)).use { ins ->
        ins.skipBytes(WavIo.HEADER)
        // Resume: skip the already-transcribed audio (16 kHz mono PCM16 → 2 bytes/sample).
        var skip = (startSec * WavIo.SAMPLE_RATE).toLong() * 2
        while (skip > 0) { val k = ins.skip(skip); if (k <= 0) break; skip -= k }
        val bytes = ByteArray(2048 * 2)
        // An enrollment clip ([studio.primeminutes.core.asr.SeamEnrollment]) reads only up to endSec.
        var left = if (endSec == Double.MAX_VALUE) Long.MAX_VALUE else ((endSec - startSec) * WavIo.SAMPLE_RATE).toLong() * 2
        while (left > 0) {
            var n = 0
            val want = minOf(bytes.size.toLong(), left).toInt()
            while (n < want) {
                val k = ins.read(bytes, n, want - n)
                if (k < 0) break
                n += k
            }
            left -= n
            if (n < 2) break
            val f = FloatArray(n / 2)
            for (i in f.indices) {
                val lo = bytes[2 * i].toInt() and 0xFF
                val hi = bytes[2 * i + 1].toInt()
                f[i] = ((hi shl 8) or lo).toShort() / 32768f
            }
            emit(f)
            if (n < want) break
        }
    }
}.flowOn(Dispatchers.IO)

/** Peak amplitude → 0..5 display bucket (log-ish thresholds: quiet speech still registers). */
internal fun micLevelBucket(peak: Float): Int = when {
    peak > 0.5f -> 5
    peak > 0.25f -> 4
    peak > 0.12f -> 3
    peak > 0.06f -> 2
    peak > 0.02f -> 1
    else -> 0
}
