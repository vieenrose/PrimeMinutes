package studio.primeminutes.core.embed

import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.data.SpeakerName
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * A meeting's semantic index: `index.bin` next to its `session.m4a` — one int8 unit vector per
 * unit of the meeting ([Unit]). Cross-platform format (iOS writes the same bytes, see
 * `ios/App/SessionIndex.swift`), regenerated rather than exported: it is derived data.
 *
 * Layout (big-endian, DataOutputStream):
 * ```
 * "PMIX" u32 version=1 | u16 len + modelId | u16 dim | 32 B fingerprint | u32 count
 * count × { u8 kind | u32 a | u32 b | u64 textHash | dim × i8 }
 * u32 crc32 of everything above
 * ```
 * [fingerprint] is the SHA-256 of what the units are built from (title, summary, notes, utterances,
 * speaker names), so a stale index is detected without opening it unit by unit. [Unit.textHash]
 * lets a rebuild reuse every vector whose text did not change: renaming one speaker re-embeds only
 * that speaker's chunks.
 */
object SessionIndex {
    const val FILE_NAME = "index.bin"
    private const val MAGIC = 0x504D4958   // "PMIX"
    private const val VERSION = 1
    const val DIM = Embedder.DIM

    enum class Kind(val code: Int) { MEETING(0), NOTE(1), CHUNK(2);
        companion object { fun of(c: Int) = entries.first { it.code == c } }
    }

    /** One indexable piece of a meeting. [a]/[b]: CHUNK = first/last utterance index (inclusive),
     *  NOTE = note line index, MEETING = part number. [text] carries no prompt prefix. */
    data class Unit(val kind: Kind, val a: Int, val b: Int, val text: String) {
        val textHash: Long get() = fnv64(kind.code.toString() + "\u0000" + text)
    }

    data class Vector(val kind: Kind, val a: Int, val b: Int, val textHash: Long, val q: ByteArray)

    class Index(val modelId: String, val fingerprint: ByteArray, val vectors: List<Vector>)

    // ---- building ----------------------------------------------------------------------------

    /** Chunk budget: ~40 s or ≤ [CHUNK_TOKENS] tokens, one-utterance overlap. */
    const val CHUNK_TOKENS = 200
    const val CHUNK_SECONDS = 40.0
    /** Eval harness only: another chunk size (tokens, seconds) for a retrieval experiment; null = the constants. */
    @Volatile internal var chunkOverride: Pair<Int, Double>? = null
    /** The summary is split so each part stays under the 512 bucket (with the prompt prefix). */
    private const val MEETING_TOKENS = 440

    fun fingerprint(s: PrimeMinutesSession.Loaded): ByteArray = fingerprint(s.title, s.summary, s.notes, s.utterances, s.speakerNames)

    fun fingerprint(
        title: String?, summary: String?, notes: String?,
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
    ): ByteArray = MessageDigest.getInstance("SHA-256").run {
        fun s(x: String?) { update((x ?: "").toByteArray()); update(0) }
        s(title); s(summary); s(notes)
        for (u in utterances) { s(u.text); s(u.speaker?.toString()); s(u.startSec.toString()) }
        for ((k, v) in speakerNames.toSortedMap()) { s(k.toString()); s(v.name) }
        digest()
    }

    /** The units of a meeting. [tokens] counts SentencePiece tokens (the embedder's tokenizer). */
    fun units(s: PrimeMinutesSession.Loaded, tokens: (String) -> Int): List<Unit> {
        val out = ArrayList<Unit>()
        // Meeting: title + summary, split by lines into ≤ MEETING_TOKENS parts.
        val head = listOfNotNull(s.title?.takeIf { it.isNotBlank() }, s.summary?.takeIf { it.isNotBlank() }).joinToString("\n")
        if (head.isNotBlank()) {
            var part = 0; val sb = StringBuilder(); var n = 0
            for (line in head.lines()) {
                if (line.isBlank()) continue
                val t = tokens(line)
                if (n > 0 && n + t > MEETING_TOKENS) { out += Unit(Kind.MEETING, part++, 0, sb.toString()); sb.clear(); n = 0 }
                if (sb.isNotEmpty()) sb.append('\n'); sb.append(line); n += t
            }
            if (sb.isNotEmpty()) out += Unit(Kind.MEETING, part, 0, sb.toString())
        }
        // Notes: one unit per line that carries text (journal `#id [ts] (tag) text` or the v2 wire form).
        s.notes?.lines()?.forEachIndexed { i, raw ->
            val t = noteText(raw)
            if (t.length >= 6) out += Unit(Kind.NOTE, i, 0, t)
        }
        // Chunks: sliding window over the utterances.
        val us = s.utterances
        val (maxTokens, maxSeconds) = chunkOverride ?: (CHUNK_TOKENS to CHUNK_SECONDS)
        var i = 0
        while (i < us.size) {
            var j = i; var n = 0; val sb = StringBuilder()
            while (j < us.size) {
                val line = chunkLine(us[j], s.speakerNames)
                val t = tokens(line)
                val tooLong = n > 0 && (n + t > maxTokens || us[j].endSec - us[i].startSec > maxSeconds)
                if (tooLong) break
                if (sb.isNotEmpty()) sb.append('\n'); sb.append(line); n += t; j++
            }
            if (sb.isNotEmpty()) out += Unit(Kind.CHUNK, i, j - 1, sb.toString())
            // Overlap of one utterance, but always advance.
            i = if (j - 1 > i) j - 1 else j
        }
        return out
    }

    /** `Name: text` when the speaker has a user/LLM name, else the bare text. */
    fun chunkLine(u: TranscriptEvent.Utterance, names: Map<Int, SpeakerName>): String {
        val name = u.speaker?.let { names[it]?.name }?.takeIf { it.isNotBlank() }
        return if (name != null) "$name: ${u.text}" else u.text
    }

    /** The searchable text of one notes line (journal line → its text; wire-form bullets → bare). */
    fun noteText(raw: String): String {
        val j = JOURNAL_LINE.matchEntire(raw.trim())
        if (j != null) return j.groupValues[1].trim()
        return raw.trim().trimStart('-', '*', '•', ' ').trim()
    }
    private val JOURNAL_LINE = Regex("^#\\d+ \\[\\d+:\\d{2}(?::\\d{2})?\\] (?:\\(\\w[\\w-]*\\) )?(.+)$")

    /**
     * Builds the vectors for [units], reusing those of [old] whose [Unit.textHash] matches, and
     * embedding the rest with [embed] (text already prefixed as a document). [onProgress] = done/total.
     */
    fun build(
        modelId: String, fingerprint: ByteArray, units: List<Unit>, old: Index?,
        embed: (String) -> FloatArray, onProgress: ((Int, Int) -> Unit)? = null,
    ): Index {
        val reuse = HashMap<Long, Vector>()
        if (old != null && old.modelId == modelId) for (v in old.vectors) reuse[v.textHash] = v
        val vectors = ArrayList<Vector>(units.size)
        units.forEachIndexed { i, u ->
            val h = u.textHash
            val q = reuse[h]?.q ?: quantize(embed(Embedder.Prompts.document(u.text)))
            vectors += Vector(u.kind, u.a, u.b, h, q)
            onProgress?.invoke(i + 1, units.size)
        }
        return Index(modelId, fingerprint, vectors)
    }

    /** Unit float vector → int8 (round(v·127)). The dot of two int8 vectors / 127² ≈ cosine. */
    fun quantize(v: FloatArray): ByteArray = ByteArray(v.size) { i -> Math.round(v[i] * 127f).coerceIn(-127, 127).toByte() }

    /** Cosine (approx.) between an int8 vector and a float query. */
    fun dot(q: ByteArray, f: FloatArray): Float {
        var s = 0f
        for (i in q.indices) s += q[i] * f[i]
        return s / 127f
    }

    // ---- file --------------------------------------------------------------------------------

    fun file(dir: File) = File(dir, FILE_NAME)

    /** Writes atomically (tmp + rename): a kill mid-write leaves the previous file or none. */
    fun write(dir: File, index: Index) {
        val bytes = encode(index)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(file(dir))) { tmp.delete(); error("rename $tmp failed") }
    }

    fun encode(index: Index): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION)
            val id = index.modelId.toByteArray(); o.writeShort(id.size); o.write(id)
            o.writeShort(DIM)
            require(index.fingerprint.size == 32); o.write(index.fingerprint)
            o.writeInt(index.vectors.size)
            for (v in index.vectors) {
                o.writeByte(v.kind.code); o.writeInt(v.a); o.writeInt(v.b); o.writeLong(v.textHash)
                require(v.q.size == DIM); o.write(v.q)
            }
            o.flush()
            val crc = CRC32().apply { update(bos.toByteArray()) }.value.toInt()
            o.writeInt(crc)
        }
        return bos.toByteArray()
    }

    /** Null when absent, truncated, corrupt, or of another format version. */
    fun read(dir: File): Index? = runCatching { decode(file(dir).readBytes()) }.getOrNull()

    fun decode(bytes: ByteArray): Index? {
        if (bytes.size < 4 + 4 + 2 + 2 + 32 + 4 + 4) return null
        val body = bytes.copyOfRange(0, bytes.size - 4)
        val crc = CRC32().apply { update(body) }.value.toInt()
        val stored = DataInputStream(bytes.inputStream(bytes.size - 4, 4)).readInt()
        if (crc != stored) return null
        DataInputStream(body.inputStream()).use { i ->
            if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
            val idLen = i.readUnsignedShort(); val id = ByteArray(idLen).also { i.readFully(it) }
            if (i.readUnsignedShort() != DIM) return null
            val fp = ByteArray(32).also { i.readFully(it) }
            val n = i.readInt()
            val vs = ArrayList<Vector>(n)
            repeat(n) {
                val kind = Kind.of(i.readUnsignedByte()); val a = i.readInt(); val b = i.readInt(); val h = i.readLong()
                val q = ByteArray(DIM).also { i.readFully(it) }
                vs += Vector(kind, a, b, h, q)
            }
            return Index(String(id), fp, vs)
        }
    }

    /** True when [dir] holds a valid index built for [fingerprint] with [modelId]. */
    fun isCurrent(dir: File, modelId: String, fingerprint: ByteArray): Boolean {
        val idx = read(dir) ?: return false
        return idx.modelId == modelId && idx.fingerprint.contentEquals(fingerprint)
    }

    private fun fnv64(s: String): Long {
        var h = -0x340d631b7bdddcdbL   // FNV-1a offset basis
        for (b in s.toByteArray()) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return h
    }
}
