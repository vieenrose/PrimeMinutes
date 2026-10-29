package studio.primeminutes.core.people

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.primeminutes.core.audio.AudioRangeReader
import studio.primeminutes.core.audio.WavIo
import studio.primeminutes.core.audio.WavSlicer
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.session.PrimeMinutesSession
import java.io.File

/** The speaker graph through JNI (`cpp/nemo/speaker_jni.cpp`). One instance at a time ([VoiceStore] serializes). */
internal class SpeakerEmbedder private constructor(private var h: Long) : AutoCloseable {
    fun embed(pcm: FloatArray, lens: IntArray): FloatArray? = if (h == 0L) null else SpeakerEmbedNative.nativeEmbed(h, pcm, lens)
    override fun close() { if (h != 0L) { SpeakerEmbedNative.nativeFree(h); h = 0L } }

    companion object {
        fun load(tflite: File, threads: Int, card: SpeakerCard): SpeakerEmbedder {
            val h = SpeakerEmbedNative.nativeCreate(tflite.absolutePath, threads, card.mels, card.meanNorm, card.int16Scale)
            check(h != 0L) { "speaker model load failed" }
            return SpeakerEmbedder(h)
        }
    }
}

internal object SpeakerEmbedNative {
    init { System.loadLibrary("primeminutes-nemo") }
    @JvmStatic external fun nativeCreate(path: String, threads: Int, nMels: Int, meanNorm: Boolean, int16: Boolean): Long
    @JvmStatic external fun nativeEmbed(h: Long, pcm: FloatArray, lens: IntArray): FloatArray?
    @JvmStatic external fun nativeFree(h: Long)
}

/**
 * Voice recognition (Phase 2, opt-in; docs/design/speaker-tags.md §2.5, §2.6, §4): the per-meeting
 * `voices.bin` sidecars (every speaker's vector), the people's confirmed exemplars (`people/voices.bin`),
 * and the background lane that computes the sidecars from the meetings' audio — never beside a
 * recording, a transcription or a reading, only with enough battery, cancellable between meetings.
 * Exemplars follow the links: a speaker linked to a person by the user teaches that person's voice
 * (unless it does not match their other meetings: then it waits for "learn anyway"). Nothing here ever
 * names a speaker: it only ranks suggestions. Local only (app files, excluded from backups and exports).
 */
object VoiceStore : VoiceHooks.Impl, VoiceLinks.Impl {
    private const val TAG = "primeminutes-voice"
    const val SIDECAR = "voices.bin"
    private const val MIN_BATTERY = 20

    val card: SpeakerCard by lazy { SpeakerCard.load() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val runMutex = Mutex()
    @Volatile private var exemplars: List<VoiceFiles.Exemplar>? = null
    private val queue = ArrayDeque<String>()
    private var worker: Job? = null
    /**
     * Bumped (under [lock]) when recognition is turned off or its data deleted: a computation that
     * started before stores nothing, so "Delete all voice data" cannot be undone by a meeting the
     * lane was still working on.
     */
    @Volatile private var generation = 0
    internal fun generation() = generation

    /** Bumped when a sidecar or an exemplar changes (the rename sheet re-ranks). */
    private val _version = MutableStateFlow(0)
    val version = _version.asStateFlow()
    /** Meetings whose vectors are being computed (the sheet says "Comparing voices…"). */
    private val _working = MutableStateFlow<Set<String>>(emptySet())
    val working = _working.asStateFlow()
    /** Where each queued, running or failed meeting stands (the sheet's note); absent once its vectors are written. */
    private val _status = MutableStateFlow<Map<String, VoiceLane.Status>>(emptyMap())
    val status = _status.asStateFlow()
    private fun setStatus(id: String, st: VoiceLane.Status?) { _status.value = if (st == null) _status.value - id else _status.value + (id to st) }
    /** The meeting whose rename sheet is open: computed first, even while the app is busy. */
    @Volatile private var front: String? = null
    /** A confirmed name whose voice does not match the person's other meetings: (meeting, speaker, person). */
    private val _outliers = MutableSharedFlow<Triple<String, Int, String>>(extraBufferCapacity = 4)
    val outliers = _outliers.asSharedFlow()
    /** Download of the model (fraction), null when idle. */
    val download = MutableStateFlow<Float?>(null)

    /** Dev log line of the last meeting computed (cost measurement). */
    @Volatile var lastCost: String? = null

    fun init(context: Context) {
        VoiceHooks.impl = this; VoiceLinks.impl = this
        PeopleStore.init(context)
    }

    fun enabled(): Boolean = PeopleStore.doc.value.voice
    fun modelReady(context: Context) = runCatching { ModelManager(context).speakerReady(card) }.getOrDefault(false)

    private fun peopleFile(context: Context) = File(context.applicationContext.filesDir, "people/$SIDECAR")
    private fun sidecar(entryDir: File) = File(entryDir, SIDECAR)

    private fun all(context: Context): List<VoiceFiles.Exemplar> = exemplars ?: synchronized(lock) {
        exemplars ?: (runCatching { peopleFile(context).takeIf { it.isFile }?.readBytes()?.let(VoiceFiles::decodePeople) }.getOrNull() ?: emptyList())
            .also { exemplars = it }
    }

    private fun edit(context: Context, f: (List<VoiceFiles.Exemplar>) -> List<VoiceFiles.Exemplar>) {
        synchronized(lock) {
            val cur = all(context)
            val n = f(cur)
            if (n == cur) return
            exemplars = n
            runCatching {
                val file = peopleFile(context).apply { parentFile?.mkdirs() }
                val tmp = File(file.parentFile, ".$SIDECAR.tmp")
                tmp.writeBytes(VoiceFiles.encodePeople(n)); if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.onFailure { Log.w(TAG, "could not write voices", it) }
        }
        _version.value++
    }

    fun readMeeting(entryDir: File): VoiceFiles.MeetingVoices? =
        runCatching { sidecar(entryDir).takeIf { it.isFile }?.readBytes()?.let(VoiceFiles::decodeMeeting) }.getOrNull()
            ?.takeIf { it.modelId == card.id }

    /** Each person's voice (confirmed exemplars of the current model), leaving out [exceptMeeting]'s own. */
    fun voices(context: Context, exceptMeeting: String? = null): Map<String, FloatArray> {
        val off = PeopleStore.doc.value.people.filter { !it.voiceEnabled }.map { it.id }.toSet()
        return VoiceFiles.Exemplars.voices(all(context).filter { it.meeting != exceptMeeting && it.person !in off }, card.id)
    }

    /** People with a learned voice: person → (meetings, seconds). */
    fun known(context: Context): Map<String, Pair<Int, Float>> =
        all(context).filter { it.confirmed && it.modelId == card.id }.groupBy { it.person }
            .mapValues { (_, es) -> es.map { it.meeting }.distinct().size to es.sumOf { it.seconds.toDouble() }.toFloat() }

    fun pendingOutliers(context: Context, person: String): List<VoiceFiles.Exemplar> =
        all(context).filter { it.person == person && !it.confirmed && it.modelId == card.id }

    // --- switches -----------------------------------------------------------------------------

    /** Turns recognition on (downloading the model) and starts the backfill of the library. */
    suspend fun enable(context: Context): Boolean {
        init(context)
        val mm = ModelManager(context)
        if (!mm.speakerReady(card)) {
            download.value = 0f
            val r = runCatching { mm.ensureSpeaker(card) { download.value = it } }
            download.value = null
            if (r.isFailure) { Log.w(TAG, "speaker model download failed", r.exceptionOrNull()); return false }
        }
        PeopleStore.mutate(context) { it.copy(voice = true) }
        catchUp(context)
        return true
    }

    /** Off: [deleteData] also removes every voice vector (people and meetings). */
    fun disable(context: Context, deleteData: Boolean) {
        PeopleStore.mutate(context) { it.copy(voice = false) }
        synchronized(lock) { queue.clear(); generation++ }
        if (deleteData) deleteAll(context)
    }

    /** "Delete all voice data": the people's exemplars and every meeting's sidecar; the switch goes off. */
    fun deleteAll(context: Context) {
        PeopleStore.mutate(context) { it.copy(voice = false) }
        synchronized(lock) {
            generation++
            queue.clear(); exemplars = emptyList(); runCatching { peopleFile(context).delete() }
            SessionLibrary.root(context).listFiles()?.forEach { runCatching { sidecar(it).delete() } }
        }
        _version.value++
    }

    /** "Forget Mark's voice": the samples go and learning stops for Mark until the user turns it back on. */
    fun forget(context: Context, person: String) {
        PeopleStore.mutate(context) { PeopleOps.setVoiceEnabled(it, person, false) }
        edit(context) { VoiceFiles.Exemplars.removePerson(it, person) }
    }

    /** "Learn this voice" back on: relearned from the meetings where the person is named. */
    fun setLearning(context: Context, person: String, on: Boolean) {
        PeopleStore.mutate(context) { PeopleOps.setVoiceEnabled(it, person, on) }
        if (on) catchUp(context) else _version.value++
    }

    /** "Learn anyway" after the outlier warning. */
    fun learnAnyway(context: Context, person: String, meeting: String) =
        edit(context) { l -> l.map { if (it.person == person && it.meeting == meeting) it.copy(confirmed = true) else it } }

    // --- hooks from the directory and the rename sheet ------------------------------------------

    override fun onMerge(context: Context, from: String, into: String) = edit(context) { VoiceFiles.Exemplars.move(it, from, into) }
    override fun onDeletePerson(context: Context, id: String) = edit(context) { VoiceFiles.Exemplars.removePerson(it, id) }
    override fun onSplit(context: Context, entryId: String, from: String, to: String) =
        edit(context) { VoiceFiles.Exemplars.moveMeeting(it, entryId, from, to) }
    override fun onMeetingDeleted(context: Context, entryId: String) {
        // The user's decision: a deleted meeting's voice samples go, even when it was a person's only source.
        edit(context) { VoiceFiles.Exemplars.removeMeeting(it, entryId) }
        synchronized(lock) { queue.remove(entryId) }
    }

    override fun confirmed(context: Context, entryId: String, speaker: Int, person: String) {
        if (!enabled()) return
        scope.launch { syncMeeting(context, entryId, announce = setOf(speaker)) ?: request(context, entryId) }
    }

    override fun unlinked(context: Context, entryId: String, speaker: Int, person: String) {
        edit(context) { l -> l.filterNot { it.person == person && it.meeting == entryId && it.speaker == speaker } }
    }

    /**
     * The meeting's exemplars follow its links (speaker → person) and its sidecar. Null when the sidecar
     * is missing or stale (the lane computes it first). [announce]: speakers whose new exemplar that does
     * not match is announced (a name just confirmed); a backfill stays silent.
     */
    fun syncMeeting(context: Context, entryId: String, announce: Set<Int> = emptySet()): Boolean? {
        val dir = File(SessionLibrary.root(context), entryId)
        val mv = readMeeting(dir) ?: return null
        val links = PeopleStore.readLinks(dir) ?: emptyMap()
        val doc = PeopleStore.doc.value
        val order = SessionLibrary.list(context).associate { it.id to it.createdAt }
        edit(context) { cur ->
            // Recognition turned off (or its data deleted) meanwhile: nobody learns from this meeting.
            if (!enabled()) return@edit cur
            var l = cur.filterNot { e -> e.meeting == entryId && links[e.speaker]?.person != e.person }
            for ((sid, link) in links) {
                val p = doc.byId(link.person) ?: continue
                if (!p.voiceEnabled) continue
                val v = mv.of(sid)?.takeIf { it.seconds >= card.minCleanSec } ?: continue
                val old = l.firstOrNull { it.person == p.id && it.meeting == entryId && it.modelId == card.id }
                val others = VoiceFiles.Exemplars.voices(l.filter { it.meeting != entryId }, card.id)[p.id]
                val fits = others == null || VoiceMath.cos(v.vector, others) >= card.outlier
                val confirmed = old?.confirmed ?: fits
                if (!confirmed && old == null && sid in announce) _outliers.tryEmit(Triple(entryId, sid, p.id))
                l = VoiceFiles.Exemplars.put(l, VoiceFiles.Exemplar(p.id, card.id, entryId, sid, v.seconds, confirmed, v.vector)) { order[it] ?: 0L }
            }
            l
        }
        return true
    }

    // --- the lane ------------------------------------------------------------------------------

    fun request(context: Context, entryId: String, front: Boolean = false) {
        if (!enabled()) return
        if (front) this.front = entryId
        if (!modelReady(context)) { setStatus(entryId, VoiceLane.Status.ModelMissing); return }
        synchronized(lock) { queue.remove(entryId); queue.addFirst(entryId) }
        if (_status.value[entryId] != VoiceLane.Status.Computing) setStatus(entryId, VoiceLane.Status.Queued(VoiceLane.Wait.QUEUE))
        start(context.applicationContext)
    }

    /** "Retry" after a failure. */
    fun retry(context: Context, entryId: String) { setStatus(entryId, null); request(context, entryId, front = true) }

    /** "Download" when the model is missing: downloads it, then backfills. */
    fun downloadModel(context: Context) {
        scope.launch {
            if (enable(context)) for ((id, st) in _status.value) if (st == VoiceLane.Status.ModelMissing) { setStatus(id, null); request(context, id) }
        }
    }

    /** Every finished meeting whose sidecar is missing or stale (and the people's exemplars from links). */
    fun catchUp(context: Context) {
        if (!enabled() || !modelReady(context)) return
        val app = context.applicationContext
        scope.launch {
            val ids = SessionLibrary.list(app).filter { it.status == SessionLibrary.Status.DONE && it.sessionFile.exists() && !it.noSpeech }.map { it.id }
            synchronized(lock) { for (id in ids) if (id !in queue) queue.addLast(id) }
            start(app)
        }
    }

    private fun start(app: Context) {
        synchronized(lock) { if (worker?.isActive == true) return; worker = scope.launch { loop(app) } }
    }

    private fun busy() = studio.primeminutes.service.RunRegistry.busy

    private fun batteryOk(app: Context): Boolean {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return charging || level < 0 || level * 100 / scale > MIN_BATTERY
    }

    private suspend fun loop(app: Context) {
        var model: SpeakerEmbedder? = null
        try {
            while (true) {
                if (!enabled()) { synchronized(lock) { queue.forEach { setStatus(it, null) }; queue.clear(); worker = null }; break }
                if (!modelReady(app)) {
                    // Never a silent stop: every meeting that waited says the model is missing (Download).
                    synchronized(lock) { Log.w(TAG, "model missing, ${queue.size} meeting(s) waiting"); queue.forEach { setStatus(it, VoiceLane.Status.ModelMissing) }; queue.clear(); worker = null }
                    break
                }
                val id = synchronized(lock) {
                    // The meeting on screen first, wherever it is in the backlog.
                    front?.let { f -> if (queue.remove(f)) queue.addFirst(f) }
                    queue.firstOrNull() ?: run { worker = null; null }
                } ?: break
                val isFront = id == front
                val w = VoiceLane.wait(isFront, busy(), batteryOk(app))
                if (w != null) {
                    if (_status.value[id] != VoiceLane.Status.Queued(w)) setStatus(id, VoiceLane.Status.Queued(w))
                    delay(1000); continue
                }
                synchronized(lock) { queue.remove(id) }
                val gen = generation
                val entry = SessionLibrary.byId(app, id)
                if (entry == null) { setStatus(id, if (isFront) VoiceLane.Status.Queued(VoiceLane.Wait.TRANSCRIPTION) else null); continue }
                _working.value = _working.value + id
                setStatus(id, VoiceLane.Status.Computing)
                var result = Computed.FAILED
                try {
                    runMutex.withLock {
                        val m = model ?: SpeakerEmbedder.load(File(ModelManager(app).speakerDir(card), card.graph), threads(), card).also { model = it }
                        result = compute(app, entry, m, gen)
                    }
                    if (gen == generation) syncMeeting(app, id)
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "voice of $id failed", e)
                } finally {
                    _working.value = _working.value - id
                    setStatus(id, if (result == Computed.FAILED) VoiceLane.Status.Failed else null)
                    if (result == Computed.FAILED) _version.value++
                    if (front == id) front = null
                }
            }
        } finally { model?.close() }
    }

    internal enum class Computed { WRITTEN, UNCHANGED, FAILED }

    private fun threads() = minOf(2, studio.primeminutes.core.hw.HwInfo.cores())

    /**
     * Computes the meeting's sidecar if it is missing or stale. Only the lines the vectors use are decoded (seeking into
     * the recording, [AudioRangeReader]), so a long meeting costs about the same as a short one and an edit does not
     * re-decode it all; the capture WAV, when still there, is read directly.
     */
    private suspend fun compute(app: Context, entry: SessionLibrary.Entry, m: SpeakerEmbedder, gen: Int): Computed {
        val s = PrimeMinutesSession.peek(entry.sessionFile) ?: run { Log.w(TAG, "voice ${entry.id}: unreadable session file"); return Computed.FAILED }
        val turns = CleanSpeech.voiceTurns(s.utterances)
        val speakers = s.utterances.mapNotNull { it.speaker }.distinct().sorted()
        val spans = speakers.associateWith { CleanSpeech.ordered(turns, it) }
        val fp = VoiceFiles.fingerprint(spans)
        val cur = readMeeting(entry.dir)
        if (cur != null && cur.fingerprint == fp) return Computed.UNCHANGED
        val t0 = System.nanoTime()
        var decodeNs = 0L
        val sr = WavIo.SAMPLE_RATE
        val wav = entry.wavFile.takeIf { WavIo.isCanonical16kMono(it) }
        val slicer = wav?.let { WavSlicer(it) }
        val ranges = if (slicer == null) AudioRangeReader(app, Uri.fromFile(entry.sessionFile)) else null
        try {
            fun read(a: Double, b: Double): FloatArray {
                val td = System.nanoTime()
                val x = slicer?.read((a * sr).toLong(), (b * sr).toLong()) ?: ranges!!.read(a, b)
                decodeNs += System.nanoTime() - td
                return x
            }
            var speech = 0.0
            val out = ArrayList<VoiceFiles.SpeakerVoice>()
            val cap = (card.capSec * sr).toInt()
            for ((sid, sp) in spans) {
                // Lines in selection order, each gated to its voiced frames, until capSec of voice.
                val pieces = ArrayList<FloatArray>()
                var have = 0
                for (s in sp) {
                    if (have >= cap) break
                    val line = read(s.start, s.end)
                    for ((a, b) in VoiceGate.runs(line, sr)) {
                        if (have >= cap) break
                        val take = minOf(b - a, cap - have)
                        pieces += line.copyOfRange(a, a + take); have += take
                    }
                }
                val sec = have.toDouble() / sr
                if (sec < card.minCleanSec) continue
                val pcm = FloatArray(have); var o = 0
                for (p in pieces) { p.copyInto(pcm, o); o += p.size }
                m.embed(pcm, IntArray(pieces.size) { pieces[it].size })?.let { out += VoiceFiles.SpeakerVoice(sid, sec.toFloat(), it) }
                speech += sec
            }
            val t2 = System.nanoTime()
            if (!storeSidecar(entry.dir, VoiceFiles.MeetingVoices(card.id, fp, out), gen)) return Computed.UNCHANGED
            lastCost = "voice ${entry.id}: ${out.size}/${speakers.size} speakers, %.0f s speech, decode %d ms, embed %d ms (total %d ms)".format(
                java.util.Locale.ROOT, speech, decodeNs / 1_000_000, (t2 - t0 - decodeNs) / 1_000_000, (t2 - t0) / 1_000_000)
            Log.i(TAG, lastCost!!)
            _version.value++
            return Computed.WRITTEN
        } finally { slicer?.close(); ranges?.close() }
    }

    /** Writes a meeting's sidecar computed under [gen]; nothing when recognition went off or its data was deleted since. */
    internal fun storeSidecar(entryDir: File, mv: VoiceFiles.MeetingVoices, gen: Int): Boolean = synchronized(lock) {
        if (gen != generation || !enabled()) return false
        val f = sidecar(entryDir); val tf = File(entryDir, ".$SIDECAR.tmp")
        tf.writeBytes(VoiceFiles.encodeMeeting(mv))
        if (!tf.renameTo(f)) { f.delete(); tf.renameTo(f) }
        true
    }

    /** "Me": a voice from a short recording of the phone's owner ([pcm] 16 kHz mono). */
    suspend fun enrolMe(context: Context, pcm: FloatArray, name: String): Person? {
        val mm = ModelManager(context)
        if (!mm.speakerReady(card)) return null
        val v = runMutex.withLock {
            SpeakerEmbedder.load(File(mm.speakerDir(card), card.graph), threads(), card).use { it.embed(pcm, intArrayOf(pcm.size)) }
        } ?: return null
        val me = PeopleStore.doc.value.people.firstOrNull { it.me } ?: PeopleStore.create(context, name, me = true)
        edit(context) { VoiceFiles.Exemplars.put(it, VoiceFiles.Exemplar(me.id, card.id, ME_ENROL, -1, pcm.size / 16000f, true, v)) }
        return me
    }

    const val ME_ENROL = "me-enrolment"
}
