package studio.primeminutes.core.session

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import studio.primeminutes.core.audio.AudioDecoder
import studio.primeminutes.core.audio.AudioTranscoder
import studio.primeminutes.core.audio.WavIo
import studio.primeminutes.core.audio.Mp4Tags
import studio.primeminutes.core.cover.CoverArt
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.EditJournal
import studio.primeminutes.data.SpeakerName
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A Prime Minutes session as a single self-describing **OGG/Opus** file: any player plays the audio (and
 * shows TITLE / DESCRIPTION / synced LYRICS), while Prime Minutes recovers the *exact* editable session
 * from a `PRIMEMINUTES` Vorbis comment — gzip+base64 of the full transcript (utterances with timestamps +
 * tokens, renamed speakers, summary, title, model ids). Round-trip is lossless: gzip carries its own
 * CRC, and the parser restores every field, so [open] reconstructs precisely what [save] wrote.
 */
object PrimeMinutesSession {
    const val EXT = "ogg"
    const val MIME = "audio/ogg"

    /** Container the session is stored in. Both embed the SAME self-describing session (audio +
     *  transcript + summary + speakers + cover + title) and reopen losslessly; M4A/AAC is the most
     *  universally-playable option, OGG/Opus the most efficient. */
    /**
     * Container for a WRITTEN session. M4A/AAC only: OGG/Opus needed an Opus ENCODER that many
     * devices do not have (a Boox ships only the decoder, disabled at that), so writing .ogg
     * silently failed there. Reading a legacy .ogg session still works — see [open].
     */
    enum class Format(val ext: String, val mime: String) {
        M4A("m4a", "audio/mp4"),
    }

    /** "Share meeting" sends `<title>.pmin` (the same MPEG-4 session inside), typed [SHARE_MIME]:
     *  an audio type made Files / Quick Share open it in the music player. Same extension + MIME as the
     *  iOS UTI (tw.com.pesi.primeminutes.session). Received .m4a sessions still restore (content sniffed). */
    const val SHARE_EXT = "pmin"
    const val SHARE_MIME = "application/vnd.primeminutes.session"

    /** A received file with no Prime Minutes manifest is only importable as audio when its type says
     *  audio/video (or is unknown); octet-stream / our MIME without a manifest is not a meeting. */
    fun importableAsAudio(mime: String?): Boolean =
        mime == null || mime.startsWith("audio/") || mime.startsWith("video/")

    /** Extensions taken as audio/video when the sender gave no useful type (null / octet-stream). */
    private val AV_EXTENSIONS = setOf(
        "m4a", "mp3", "wav", "aac", "ogg", "oga", "opus", "flac", "amr", "3gp", "3gpp", "wma", "aif", "aiff",
        "caf", "mp4", "m4v", "mov", "mkv", "webm", "avi", "ts",
    )

    /** [importableAsAudio] for a received `content:` Uri, which may come through the catch-all VIEW
     *  filter (any file a file manager opens with us): no type, or octet-stream, is audio only when
     *  the display name says so — a .txt / .pdf / .pmin without a manifest is refused. */
    fun importableReceived(mime: String?, displayName: String?, isContent: Boolean): Boolean {
        if (mime != null && mime != "application/octet-stream") return importableAsAudio(mime)
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in AV_EXTENSIONS) return true
        return mime == null && !isContent
    }

    private const val VERSION = SessionShare.SCHEMA_MAJOR
    private const val FIELD = "PRIMEMINUTES"
    // base64 ceiling before decode. Generous on purpose: a long meeting's transcript can exceed a
    // couple MB once base64'd (especially less-compressible CJK / dense text), and the REAL protection
    // against a gzip bomb is [MAX_JSON_BYTES], which bounds the *decompressed* output as it streams.
    // A 2 MB cap here used to silently reject (→ "plain audio", transcript lost) sessions that saved
    // fine — so it must be at least as large as any blob that gunzips within MAX_JSON_BYTES.
    private const val MAX_BLOB_CHARS = 32 * 1024 * 1024
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024     // gunzip ceiling (decompressed; the bomb guard)

    /** A restored session: the extracted audio file + the full editable transcript state. */
    data class Loaded(
        val audio: File,
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val summary: String?,
        val title: String?,
        /** v2 structured notes in their WIRE form ([MeetingNotes.render]), or null. Stored as the
         *  format itself rather than a second schema: it round-trips through the same parser and
         *  stays readable in the file. Without this the sections are lost the moment the session
         *  is reopened, and never appear at all for queue-processed meetings (the event bus is
         *  replay=0, so nothing is listening while the queue drains). */
        val notes: String?,
        val asrModelId: String?,
        /** ASR backend id ([AsrBackend.id]) that produced these utterances. Distinct from
         *  [asrModelId], which names a weights directory and cannot identify the backend on its
         *  own. Null for sessions written before this field existed. */
        val asrBackend: String?,
        val llmModelId: String?,
        val recovered: Boolean,   // false => plain audio with no embedded session
        val coverJpeg: ByteArray? = null,   // embedded cover art (METADATA_BLOCK_PICTURE), if any
        val coverSig: String? = null,       // signature the embedded cover was built from
        val actionItems: String? = null,    // extracted action items + decisions (editable draft)
        /** Revision of this content ([EditJournal]): 0 for a file written before revisions. */
        val editRev: Long = 0L,
    )

    /** Outcome of a save: FULL = transcript embedded; PARTIAL = audio+summary only (blob too big); FAILED. */
    enum class SaveOutcome { FULL, PARTIAL, FAILED }

    /** A built session file + whether the editable transcript blob fit inside it. */
    data class Built(val file: File, val transcriptEmbedded: Boolean)

    /** Stable per-track fingerprint: SHA-256 of the decoded 16 kHz PCM — the cover seed. Identical to
     *  the value [buildSession] derives from the same audio, so an in-app live preview equals the
     *  cover that gets embedded on save. Decodes to a throwaway temp WAV; null if it can't be decoded. */
    suspend fun audioFingerprint(context: Context, audioUri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        // Match buildSession exactly (same bytes hashed → identical cover): if the source is
        // already a canonical 16 kHz mono WAV, hash it directly (no decode); otherwise decode to a
        // throwaway temp. Both hash the whole WAV including its 44-byte header, and the decode is
        // deterministic, so the live-preview cover equals the one embedded on save either way.
        val srcFile = audioUri.takeIf { it.scheme == "file" }?.path?.let(::File)
        val direct = srcFile != null && WavIo.isCanonical16kMono(srcFile)
        val wav = if (direct) srcFile!! else File(context.cacheDir, ".fp_${audioUri.hashCode()}.wav")
        try {
            if (!direct) {
                val n = runCatching { AudioDecoder.decodeToWav16k(context, audioUri, wav) { _, _ -> } }.getOrNull()
                if (n == null || n <= 0L) return@withContext null
            }
            MessageDigest.getInstance("SHA-256").run {
                wav.inputStream().use { ins ->
                    val b = ByteArray(1 shl 16)
                    while (true) { val k = ins.read(b); if (k < 0) break; update(b, 0, k) }
                }
                digest()
            }
        } finally { if (!direct) wav.delete() }
    }

    /**
     * Build a self-describing `.ogg` in [dir]: transcode [audioUri] to OGG/Opus and embed the
     * session as Vorbis comments. Returns null if there's no audio / it can't be transcoded.
     * [Built.transcriptEmbedded] is false when the transcript was too large for the OpusTags page —
     * the file is still a playable ogg with TITLE/DESCRIPTION/LYRICS, but can't be reopened as an
     * editable session, so the caller MUST surface that (never report a silent full success).
     */
    suspend fun buildSession(
        context: Context,
        dir: File,
        audioUri: Uri?,
        utterances: List<TranscriptEvent.Utterance>,
        speakerNames: Map<Int, SpeakerName>,
        summary: String?,
        actionItems: String?,
        title: String?,
        notes: String?,
        asrModelId: String?,
        asrBackend: String?,
        llmModelId: String?,
        coverEnabled: Boolean = true,   // auto-generate + embed the cover (deterministic from audio + title)
        fileName: String = "session.$EXT",
        // NO DEFAULT, deliberately. This used to default to OGG, which needs an Opus ENCODER —
        // hardware that not every device has (a Boox Note Air ships only the decoder, and even that
        // is disabled), so the default silently produced nothing there. Every caller already
        // passes M4A; making it explicit means a future caller cannot inherit the broken one.
        format: Format,
        /** Revision embedded with the session ([EditJournal.REV]); 0 outside the library. */
        editRev: Long = 0L,
        /** A copy leaving the device (share, save to a file): the manifest goes through the
         *  [SessionShare.forShare] allowlist (no people links) and carries this identity. */
        share: SessionShare.Meta? = null,
    ): Built? = withContext(Dispatchers.IO) {
        if (audioUri == null) return@withContext null
        dir.mkdirs()
        // Already one of OUR session files (an edit re-save of a library entry — opened through a
        // cache copy named session.bin, hence the content check — or sharing one): the AAC audio is
        // unchanged, so only the tags are rewritten. Decoding and re-encoding the whole meeting for
        // that took over a minute on the Note10+, on every edit.
        val srcFile = audioUri.takeIf { it.scheme == "file" }?.path?.let(::File)
        val retagSource = srcFile != null && srcFile.isFile && isMp4(srcFile) &&
            runCatching { Mp4Tags.readSessionBlob(srcFile) != null }.getOrDefault(false)
        val plain: File
        if (retagSource) {
            plain = srcFile!!
        } else {
            // Stream-decode to a 16 kHz mono work WAV, then stream that to AAC — never the whole
            // waveform in RAM, so multi-hour sessions encode without OOM. BUT when the source is ALREADY
            // a canonical 16 kHz mono WAV (a library capture / decode output), skip the decode and
            // stream that file straight through: on a long meeting it is the most expensive step.
            val reuseSource = srcFile != null && WavIo.isCanonical16kMono(srcFile)
            val workWav: File
            if (reuseSource) {
                workWav = srcFile!!
            } else {
                workWav = File(dir, ".audio_tmp.wav")
                val decoded = runCatching {
                    AudioDecoder.decodeToWav16k(context, audioUri, workWav) { _, _ -> }
                }.getOrElse { android.util.Log.w("primeminutes-ogg", "decode failed", it); null }
                if (decoded == null || decoded <= 0L) { workWav.delete(); return@withContext null }
            }
            // Dot-prefixed temp name so it can never collide with a suggestFileName() output (which is
            // trimmed of leading dots), avoiding deleting the very file we return in the share flow.
            plain = File(dir, ".audio_tmp.${format.ext}")
            val transcoded = AudioTranscoder.wavToM4aAac(workWav, plain)
            if (!reuseSource) workWav.delete()   // never delete the reused SOURCE (the library capture)
            if (!transcoded) {
                android.util.Log.w("primeminutes-session", "wav->${format.ext} transcode returned false")
                return@withContext null
            }
        }
        // No generated cover art: the per-session identicon read as a random logo and was removed.
        // [coverEnabled] is kept for call-site compatibility and ignored.
        val coverJpeg: ByteArray? = null; val coverW = 0; val coverH = 0
        val m0 = manifest(utterances, speakerNames, summary, actionItems, title, notes, asrModelId, asrBackend, llmModelId, editRev)
        val blob = encodeSession(share?.let { SessionShare.forShare(m0, it.sessionId, it.createdAtMs, it.script, it.userEdited) } ?: m0)
        val cleanTitle = title?.replace('\n', ' ')?.trim()?.ifBlank { null }
        val cleanSummary = summary?.trim()?.ifBlank { null }
        // Player-facing lyrics (©lyr / LYRICS) = LRC-style SYNCHRONIZED lyrics: an `[mm:ss.xx]` timestamp
        // per transcript line. Players that parse embedded synced lyrics (Poweramp, Musicolet, Retro
        // Music…) SCROLL the transcript in real time as the audio plays; players without sync support
        // still show it as a text block (with the timestamps visible — the accepted trade-off for sync).
        // The summary stays in the comment field (©cmt / DESCRIPTION); the canonical transcript
        // round-trips via the PRIMEMINUTES blob, so recovery is untouched.
        val lyrics = buildString {
            cleanTitle?.let { append("[ti:").append(it.replace(']', ' ')).append("]\n") }
            for (u in utterances) {
                val t = u.text.replace('\n', ' ').trim()
                if (t.isEmpty()) continue
                val cs = (if (u.startSec > 0) u.startSec * 100 else 0.0).toLong()   // centiseconds
                append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100))
                append(t).append('\n')
            }
        }.ifBlank { null }
        val finalOut = File(dir, fileName)
        // Tag into a dot-prefixed temp, then rename into place at the end: attachResults rebuilds
        // session.m4a IN PLACE (edits persist), so a process kill mid-write under the final name
        // would destroy the only good copy. Rename is atomic on the same filesystem.
        val tagged = File(dir, ".tagged_tmp.${format.ext}")
        // A null blob (too large to round-trip) → embed a playable file with just player metadata,
        // no PRIMEMINUTES session; transcriptEmbedded=false so the caller reports PARTIAL, not FULL.
        var embedded: Boolean = if (blob == null) {
                if (!Mp4Tags.write(plain, tagged, primeminutes = null, title = cleanTitle, description = cleanSummary, coverJpeg = coverJpeg, lyrics = null)) plain.copyTo(tagged, overwrite = true)
            false
        } else {
            if (Mp4Tags.write(plain, tagged, primeminutes = blob, title = cleanTitle, description = cleanSummary, coverJpeg = coverJpeg, lyrics = lyrics)) true
            else { plain.copyTo(tagged, overwrite = true); false }   // fallback: playable m4a, no session
        }
        // Belt-and-braces: whatever the tag writer reported, a session file must exist and be
        // non-empty before the caller may mark the entry DONE — a phantom success here strands a
        // library entry as DONE with no session file behind it.
        if (!tagged.exists() || tagged.length() == 0L) {
            if (plain != tagged && plain.exists()) { plain.copyTo(tagged, overwrite = true); embedded = false }
            else { android.util.Log.w("primeminutes-session", "session build produced no file"); return@withContext null }
        }
        if (!retagSource) plain.delete()   // never the re-tagged SOURCE: it is the library's only audio
        // POSIX rename overwrites the target atomically — never pre-delete finalOut (a failure
        // after that would lose the previous good session file).
        if (!tagged.renameTo(finalOut)) {
            tagged.copyTo(finalOut, overwrite = true)
            tagged.delete()
        }
        Built(finalOut, embedded)
    }

    /** Write a self-describing session file to [out]; the return distinguishes full vs partial vs failed. */
    suspend fun save(
        context: Context, out: OutputStream, audioUri: Uri?,
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
        summary: String?, actionItems: String?, title: String?, notes: String?,
        asrModelId: String?, asrBackend: String?, llmModelId: String?,
        coverEnabled: Boolean = true, format: Format,   // no default — see buildSession
        share: SessionShare.Meta? = null,
    ): SaveOutcome = withContext(Dispatchers.IO) {
        out.use { o ->
            val dir = File(context.cacheDir, "primeminutes_save").apply { mkdirs() }
            val built = buildSession(context, dir, audioUri, utterances, speakerNames, summary, actionItems, title, notes, asrModelId, asrBackend, llmModelId, coverEnabled, "session.${format.ext}", format,
                share = share)
                ?: return@use SaveOutcome.FAILED
            built.file.inputStream().use { it.copyTo(o) }
            built.file.delete()
            if (built.transcriptEmbedded) SaveOutcome.FULL else SaveOutcome.PARTIAL
        }
    }

    /** Open a session file: extract its audio to cache and recover the embedded session (if any). */
    suspend fun open(context: Context, src: Uri): Loaded = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "primeminutes_open_${src.hashCode().toUInt()}")
        // Reclaim earlier opened-session caches (each holds a full audio copy) — keep only this one.
        context.cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("primeminutes_open_") && it.name != dir.name) it.deleteRecursively()
        }
        dir.mkdirs()
        val audio = File(dir, "session.bin")
        context.contentResolver.openInputStream(src)?.use { ins -> audio.outputStream().use { ins.copyTo(it) } }
            ?: error(context.getString(studio.primeminutes.R.string.err_open_file))
        // Sessions live in MP4/M4A ("ftyp" at offset 4). Anything else is treated as plain
        // audio: it still plays, it just carries no embedded transcript.
        val isM4a = isMp4(audio)
        val coverJpeg = if (isM4a) Mp4Tags.readCover(audio) else null
        val src0 = src.takeIf { it.scheme == "file" }?.path?.let(::File)
        // A library meeting's pending edits ([EditJournal]) over what its m4a embeds.
        val json = effectiveManifest(src0, if (isM4a) embeddedManifest(audio) else null)
            // No embedded session (or an implausibly large blob) — load as plain audio.
            ?: return@withContext Loaded(audio, emptyList(), emptyMap(), null, null, null, null, null, null, recovered = false, coverJpeg = coverJpeg)
        // Parse under one guard: 'Open session'/share-in accept arbitrary files, so a blob that is
        // valid gzip+JSON but semantically off (a future schema, a hand-edited or truncated file —
        // a non-object utterance, a non-integer speaker key) must degrade to plain audio, not crash
        // the open flow. parseManifest's strict getInt/getJSONObject calls are inside this guard.
        // Written by a newer app (a newer manifest major): never guessed at, it opens as plain audio.
        if (SessionShare.gate(json) == SessionShare.Gate.TOO_NEW)
            return@withContext Loaded(audio, emptyList(), emptyMap(), null, null, null, null, null, null, recovered = false, coverJpeg = coverJpeg)
        return@withContext runCatching {
            val m = parseManifest(json, audio, coverJpeg)
            // A library meeting: its speaker sidecar and the people directory name its speakers.
            if (src0 != null) m.copy(speakerNames = studio.primeminutes.core.people.PeopleStore.overlay(src0, m.speakerNames)) else m
        }.getOrElse {
            android.util.Log.w("primeminutes-session", "unreadable embedded session, opening as plain audio", it)
            Loaded(audio, emptyList(), emptyMap(), null, null, null, null, null, null, recovered = false, coverJpeg = coverJpeg)
        }
    }

    /** Cheap probe (tag read only, no decode/extract): does [file] embed a recoverable Prime Minutes session,
     *  as opposed to being plain audio? Used to decide whether a *shared* file should be recovered as a
     *  session or transcribed. */
    suspend fun hasEmbeddedSession(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val blob = if (isMp4(file)) Mp4Tags.readSessionBlob(file) else null
            blob != null && blob.length <= MAX_BLOB_CHARS
        }.getOrDefault(false)
    }

    /** The embedded transcript state of [file] without extracting its audio (the [Loaded.audio] is
     *  [file] itself, not a cache copy): for callers that only merge names and edits. Null when
     *  the file embeds nothing readable. */
    suspend fun peek(file: File): Loaded? = withContext(Dispatchers.IO) {
        runCatching {
            val json = effectiveManifest(file, embeddedManifest(file)) ?: return@runCatching null
            val m = parseManifest(json, file)
            m.copy(speakerNames = studio.primeminutes.core.people.PeopleStore.overlay(file, m.speakerNames))
        }.getOrNull()
    }

    private val SAFE_NAME = Regex("[^\\p{L}\\p{N}._-]")        // keep letters (incl. CJK), digits, . _ -
    private val RESERVED = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])$")

    /**
     * Derive a *secure*, portable `.ogg` filename from the session [title]. Whitelist approach:
     * only letters (incl. CJK), digits and `. _ -` survive; everything else — whitespace, path
     * separators, shell metacharacters, control chars — becomes `_`. This neutralizes path traversal
     * (`/`, `\` and leading dots are stripped, so a malicious title can't escape the target dir when
     * the name is used as `File(dir, name)` for sharing), dodges Windows reserved names, and caps
     * length. Falls back to `primeminutes_session` when nothing usable remains.
     */
    fun suggestFileName(title: String?, ext: String = EXT): String {
        var s = (title ?: "")
            .replace(SAFE_NAME, "_")
            .replace(Regex("_{2,}"), "_")
            .trim('_', '.', '-')
        if (s.length > 60) s = s.take(60).trim('_', '.', '-')
        if (s.isBlank() || RESERVED.matches(s.substringBefore('.'))) s = "primeminutes_session"
        return "$s.$ext"
    }

    // --- session (de)serialization: lossless JSON, gzip+base64 into one comment ---

    /** One utterance → JSON — shared by the session blob and the queue's pending-transcript
     *  sidecar ([studio.primeminutes.core.library.SessionLibrary.savePendingTranscript]), so both
     *  round-trip bit-exact (including per-token tokens/tokenTimes used by the diarization split). */
    fun utteranceToJson(u: TranscriptEvent.Utterance): JSONObject = JSONObject().apply {
        put("index", u.index); put("start", u.startSec); put("end", u.endSec); put("text", u.text)
        u.speaker?.let { put("speaker", it) }
        u.tokens?.let { put("tokens", JSONArray(it)) }
        u.tokenTimes?.let { put("token_times", JSONArray(it)) }
    }

    /** JSON → utterance (inverse of [utteranceToJson]); [fallbackIndex] when "index" is absent. */
    fun utteranceFromJson(o: JSONObject, fallbackIndex: Int = 0): TranscriptEvent.Utterance =
        TranscriptEvent.Utterance(
            index = o.optInt("index", fallbackIndex),
            text = o.optString("text", ""),
            startSec = o.optDouble("start", 0.0),
            endSec = o.optDouble("end", 0.0),
            speaker = if (o.has("speaker")) o.getInt("speaker") else null,
            tokens = o.optJSONArray("tokens")?.let { t -> List(t.length()) { t.getString(it) } },
            tokenTimes = o.optJSONArray("token_times")?.let { t -> List(t.length()) { t.getDouble(it) } },
        )

    /** The session manifest: the JSON the m4a embeds (gzip+base64) and [EditJournal] holds as is. */
    fun manifest(
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
        summary: String?, actionItems: String?, title: String?, notes: String?,
        asrModelId: String?, asrBackend: String?, llmModelId: String?, editRev: Long = 0L,
    ): JSONObject {
        val root = JSONObject()
        root.put("primeminutes_version", VERSION)
        // Unknown to readers older than the edit journal, which ignore it.
        if (editRev > 0L) root.put(EditJournal.REV, editRev)
        title?.let { root.put("title", it) }
        summary?.let { root.put("summary", it) }
        actionItems?.let { root.put("action_items", it) }
        notes?.let { root.put("notes", it) }
        asrModelId?.let { root.put("asr_model", it) }
        asrBackend?.let { root.put("asr_backend", it) }
        llmModelId?.let { root.put("llm_model", it) }
        val names = JSONObject()
        speakerNames.forEach { (id, n) ->
            names.put(id.toString(), JSONObject().apply {
                put("name", n.name); put("confidence", n.confidence); put("reason", n.reason)
                n.person?.let { put("person", it) }
            })
        }
        root.put("speaker_names", names)
        val arr = JSONArray()
        // Multi-page OpusTags removes the size cap, so embed everything for a bit-exact round-trip.
        utterances.forEach { u -> arr.put(utteranceToJson(u)) }
        root.put("utterances", arr)
        return root
    }

    /** The gzip+base64 session blob, or null when it exceeds the read-side size ceiling (so the
     *  caller embeds a session-less but playable file and reports PARTIAL instead of a false FULL). */
    private fun encodeSession(root: JSONObject): String? {
        val json = root.toString().toByteArray(Charsets.UTF_8)
        // A blob that the READ path would reject must not be embedded as if it round-trips. open()
        // aborts gunzip past MAX_JSON_BYTES (decompressed) and rejects base64 over MAX_BLOB_CHARS —
        // so bail here when the raw JSON already exceeds the decompressed ceiling, rather than
        // writing a "FULL" file that throws 'metadata is corrupt' on reopen. Returns null → caller
        // embeds a playable-but-session-less file and reports PARTIAL.
        if (json.size > MAX_JSON_BYTES) return null
        val blob = Base64.encodeToString(gzip(json), Base64.NO_WRAP)
        return blob.takeIf { it.length <= MAX_BLOB_CHARS }
    }

    /** The manifest embedded in [file] (an m4a), or null: plain audio, too large, unreadable. */
    fun embeddedManifest(file: File): JSONObject? = runCatching {
        val blob = if (isMp4(file)) Mp4Tags.readSessionBlob(file) else null
        if (blob == null || blob.length > MAX_BLOB_CHARS) null
        else JSONObject(gunzip(Base64.decode(blob, Base64.NO_WRAP)).toString(Charsets.UTF_8))
    }.onFailure { android.util.Log.w("primeminutes-session", "unreadable embedded session", it) }.getOrNull()

    /** The session [file] stands for: its library entry's pending edits ([EditJournal]) when newer
     *  than what it embeds, else the embedded manifest. */
    private fun effectiveManifest(file: File?, embedded: JSONObject?): JSONObject? {
        val dir = file?.parentFile?.takeIf { it.parentFile?.name == "library" } ?: return embedded
        return EditJournal.newer(EditJournal.read(dir), embedded) ?: embedded
    }

    /** A manifest → the restored session (parse errors throw: callers guard). */
    fun loaded(m: JSONObject, audio: File, coverJpeg: ByteArray? = null): Loaded = parseManifest(m, audio, coverJpeg)

    private fun parseManifest(m: JSONObject, audio: File, coverJpeg: ByteArray? = null): Loaded {
        val utts = ArrayList<TranscriptEvent.Utterance>()
        val arr = m.optJSONArray("utterances") ?: JSONArray()
        for (i in 0 until arr.length()) {
            utts.add(utteranceFromJson(arr.getJSONObject(i), fallbackIndex = i))
        }
        val names = HashMap<Int, SpeakerName>()
        m.optJSONObject("speaker_names")?.let { sn ->
            sn.keys().forEach { k ->
                val o = sn.getJSONObject(k)
                names[k.toInt()] = SpeakerName(
                    name = o.optString("name", ""),
                    confidence = o.optString("confidence", "user"),
                    reason = o.optString("reason", ""),
                    person = o.optString("person", "").ifEmpty { null },
                )
            }
        }
        return Loaded(
            audio = audio,
            utterances = utts,
            speakerNames = names,
            summary = m.optString("summary", "").ifEmpty { null },
            actionItems = m.optString("action_items", "").ifEmpty { null },
            title = m.optString("title", "").ifEmpty { null },
            notes = m.optString("notes", "").ifEmpty { null },
            asrModelId = m.optString("asr_model", "").ifEmpty { null },
            asrBackend = m.optString("asr_backend", "").ifEmpty { null },
            llmModelId = m.optString("llm_model", "").ifEmpty { null },
            recovered = true,
            coverJpeg = coverJpeg,
            coverSig = m.optString("cover_sig", "").ifEmpty { null },
            editRev = m.optLong(EditJournal.REV, 0L),
        )
    }

    /** LRC lyrics so music players show synced, karaoke-style transcript lines. */
    internal fun lrc(utts: List<TranscriptEvent.Utterance>): String =
        utts.joinToString("\n") { u ->
            val m = (u.startSec / 60).toInt()
            val s = u.startSec - m * 60
            // Locale.ROOT: players parse "[mm:ss.xx]" (a French default wrote "05,20")
            "[%02d:%05.2f]%s".format(java.util.Locale.ROOT, m, s, u.text.replace('\n', ' '))
        }

    /** True when [f] is an MP4/M4A container ("ftyp" at offset 4), vs an OGG ("OggS" at 0). */
    private fun isMp4(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val h = ByteArray(12)
            if (ins.read(h) < 12) false else String(h, 4, 4, Charsets.US_ASCII) == "ftyp"
        }
    }.getOrDefault(false)

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** Bounded inflate: never trust an attacker-controlled gzip blob to readBytes() unbounded
     *  (a gzip bomb would OOM/kill the process). Abort past [MAX_JSON_BYTES]. */
    private fun gunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        GZIPInputStream(data.inputStream()).use { gz ->
            var total = 0
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_JSON_BYTES) error("Session metadata too large")  // internal: only reachable with a hostile file
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
