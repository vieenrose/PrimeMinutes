package studio.primeminutes.core.people

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * The two voice files (little-endian, a CRC32 at the end; Android and iOS write the same bytes):
 *  - a meeting's `voices.bin` sidecar ([MeetingVoices]): every speaker's vector and seconds, with the
 *    fingerprint of the speech spans it came from — derived data, recomputed when stale, deleted with
 *    the meeting;
 *  - `people/voices.bin` ([Exemplar] list): one exemplar per (person, meeting, model), the confirmed
 *    names only. "Delete all voice data" deletes this file and every sidecar.
 */
object VoiceFiles {
    private const val MEETING_MAGIC = 0x53564D50  // "PMVS"
    private const val PEOPLE_MAGIC = 0x50564D50   // "PMVP"
    const val VERSION = 1

    data class SpeakerVoice(val speaker: Int, val seconds: Float, val vector: FloatArray)

    data class MeetingVoices(val modelId: String, val fingerprint: String, val speakers: List<SpeakerVoice>) {
        fun of(speaker: Int): SpeakerVoice? = speakers.firstOrNull { it.speaker == speaker }
    }

    data class Exemplar(
        val person: String, val modelId: String, val meeting: String, val speaker: Int,
        val seconds: Float, val confirmed: Boolean, val vector: FloatArray,
    )

    private class W {
        val b = ByteArrayOutputStream()
        fun i32(v: Int) = b.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u16(v: Int) = b.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        fun f32(v: Float) = b.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
        fun str(s: String) { val x = s.toByteArray(Charsets.UTF_8); u16(x.size); b.write(x) }
        fun vec(v: FloatArray) { u16(v.size); val bb = ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN); v.forEach { bb.putFloat(it) }; b.write(bb.array()) }
        fun done(): ByteArray { val body = b.toByteArray(); val c = CRC32().apply { update(body) }.value.toInt(); return body + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c).array() }
    }

    private class R(val bb: ByteBuffer) {
        fun i32() = bb.int
        fun u16() = bb.short.toInt() and 0xFFFF
        fun f32() = bb.float
        fun str(): String { val n = u16(); val x = ByteArray(n); bb.get(x); return String(x, Charsets.UTF_8) }
        fun vec(): FloatArray { val n = u16(); return FloatArray(n) { bb.float } }
    }

    private fun body(bytes: ByteArray, magic: Int): R? {
        if (bytes.size < 12) return null
        val body = bytes.copyOfRange(0, bytes.size - 4)
        val crc = ByteBuffer.wrap(bytes, bytes.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (CRC32().apply { update(body) }.value.toInt() != crc) return null
        val r = R(ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN))
        if (r.i32() != magic || r.i32() != VERSION) return null
        return r
    }

    fun encodeMeeting(m: MeetingVoices): ByteArray = W().apply {
        i32(MEETING_MAGIC); i32(VERSION); str(m.modelId); str(m.fingerprint); i32(m.speakers.size)
        for (s in m.speakers) { u16(s.speaker); f32(s.seconds); vec(s.vector) }
    }.done()

    fun decodeMeeting(bytes: ByteArray): MeetingVoices? = runCatching {
        val r = body(bytes, MEETING_MAGIC) ?: return null
        val model = r.str(); val fp = r.str(); val n = r.i32()
        MeetingVoices(model, fp, List(n) { SpeakerVoice(r.u16(), r.f32(), r.vec()) })
    }.getOrNull()

    fun encodePeople(list: List<Exemplar>): ByteArray = W().apply {
        i32(PEOPLE_MAGIC); i32(VERSION); i32(list.size)
        for (e in list) { str(e.person); str(e.modelId); str(e.meeting); u16(e.speaker); f32(e.seconds); u16(if (e.confirmed) 1 else 0); vec(e.vector) }
    }.done()

    fun decodePeople(bytes: ByteArray): List<Exemplar>? = runCatching {
        val r = body(bytes, PEOPLE_MAGIC) ?: return null
        List(r.i32()) { Exemplar(r.str(), r.str(), r.str(), r.u16(), r.f32(), r.u16() != 0, r.vec()) }
    }.getOrNull()

    /** The fingerprint of a speaker's clean speech spans (recompute the vector when it changes). */
    fun fingerprint(spans: Map<Int, List<CleanSpeech.Span>>): String {
        val sb = StringBuilder()
        for ((k, v) in spans.toSortedMap()) { sb.append(k).append(':'); v.forEach { sb.append("%.2f-%.2f,".format(java.util.Locale.ROOT, it.start, it.end)) }; sb.append(';') }
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Exemplar edits (pure). Newest [cap] per (person, model). */
    object Exemplars {
        const val CAP = 30

        fun put(list: List<Exemplar>, e: Exemplar, order: (String) -> Long = { 0L }): List<Exemplar> {
            val all = list.filterNot { it.person == e.person && it.meeting == e.meeting && it.modelId == e.modelId } + e
            val drop = all.filter { it.person == e.person && it.modelId == e.modelId }
                .sortedByDescending { order(it.meeting) }.drop(CAP).map { it.meeting }.toSet()
            return all.filterNot { it.person == e.person && it.modelId == e.modelId && it.meeting in drop }
        }
        fun removeMeeting(list: List<Exemplar>, meeting: String) = list.filterNot { it.meeting == meeting }
        fun removePerson(list: List<Exemplar>, person: String) = list.filterNot { it.person == person }
        fun remove(list: List<Exemplar>, person: String, meeting: String) = list.filterNot { it.person == person && it.meeting == meeting }
        fun move(list: List<Exemplar>, from: String, to: String) = list.map { if (it.person == from) it.copy(person = to) else it }
        fun moveMeeting(list: List<Exemplar>, meeting: String, from: String, to: String) =
            list.map { if (it.person == from && it.meeting == meeting) it.copy(person = to) else it }
        fun voices(list: List<Exemplar>, modelId: String): Map<String, FloatArray> =
            list.filter { it.confirmed && it.modelId == modelId }.groupBy { it.person }
                .mapNotNull { (p, es) -> VoiceMath.centroid(es.map { it.vector to it.seconds })?.let { p to it } }.toMap()
    }
}
