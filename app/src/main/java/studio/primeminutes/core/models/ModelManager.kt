package studio.primeminutes.core.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.primeminutes.core.asr.NemoLitertFiles
import studio.primeminutes.core.asr.AsrEngineFiles
import studio.primeminutes.core.embed.EmbedRegistry
import studio.primeminutes.core.embed.EmbedSpec
import studio.primeminutes.core.asr.NemoModelFiles
import studio.primeminutes.core.asr.NemoStreamEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazy, first-run model provisioning — Android counterpart of the "download on first use"
 * behaviour in src/utils.py. Models are NOT bundled in the APK (F-Droid: keeps the build
 * lean and the APK FOSS); they download once into app-private storage, or can be
 * side-loaded (adb push / file copy) so the app works fully network-free.
 *
 * Phase 1 provisions the ASR models only (ASR + Silero VAD). The LLM (Phase 2) and
 * diarization models (Phase 3) extend this with the same pattern.
 */
class ModelManager(context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val assets = context.assets

    /** The diarizer pre-encode matrix ships inside the APK (assets/nemo); copied once next to the graphs. */
    private fun preEncodeF32(): File? = runCatching {
        val out = File(nemoDir, PRE_ENCODE_F32)
        if (out.length() != PRE_ENCODE_F32_BYTES) {
            nemoDir.mkdirs()
            assets.open("nemo/$PRE_ENCODE_F32").use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        out.takeIf { it.length() == PRE_ENCODE_F32_BYTES }
    }.getOrNull()

    init {
        // Reclaim long-dropped backends' models on construction. The engines retired by the
        // nemo switch (LITERT_RETIRED) are reclaimed only once the new models verify, in
        // ensureAsrModels, so a failed download never leaves the device without an engine.
        DROPPED_BACKEND_DIRS.filterNot { it in LITERT_RETIRED }
            .forEach { File(modelsDir, it).takeIf(File::exists)?.deleteRecursively() }
        DROPPED_FILES.filterNot { it in LITERT_RETIRED }
            .forEach { File(modelsDir, it).takeIf(File::exists)?.delete() }
    }

    // --- ASR + diarization: nemo-x-asr-diarizer on LiteRT. ---------------------------------------
    //   X-ASR zh-en punct int8 — streaming Zipformer2 transducer, zh + en, one of four chunk sizes (AsrRegistry, Apache-2.0)
    //   Nemotron-3 diar int8   — streaming Sortformer diarization, up to 8 speakers (OpenMDW-1.1)
    // Layout: the diarizer host weights in nemo/ (stamped NEMO_REVISION), its graph in nemo/sortformer-f380/, the shared
    // X-ASR host weights in nemo/xasr-punct-host/, each recognizer variant's graph in nemo/<variant.dirName>/ (each
    // folder stamped with its file's SHA-256).
    // Installs from before the variants carry the 480 ms non-punct pair in nemo/ (LEGACY_*): it keeps running until
    // the chosen variant is installed, then it is deleted. Likewise installs from before the 264-frame speaker cache
    // carry the 190-frame diarizer graph in nemo/ (STANDIN_DIAR_FILE): it runs until the f380 graph has loaded once.
    val nemoDir: File get() = File(modelsDir, NEMO_DIR)
    private val hostDir: File get() = File(nemoDir, HOST_DIR)
    private val diarGraphDir: File get() = File(nemoDir, DIAR_GRAPH_DIR)
    fun variantDir(v: AsrVariant): File = File(nemoDir, v.dirName)

    private fun stamped(dir: File, stamp: String) = runCatching { File(dir, REVISION_MARKER).readText().trim() }.getOrNull() == stamp
    private fun diarHostReady(): Boolean =
        File(nemoDir, HOST_DIAR_FILE).length() == DIAR_HOST.bytes && stamped(nemoDir, NEMO_REVISION)
    private fun diarGraphReady(): Boolean =
        File(diarGraphDir, DIAR_GRAPH_FILE).length() == DIAR_GRAPH.bytes && stamped(diarGraphDir, DIAR_GRAPH.sha256)
    /** The chosen diarizer is installed (nothing left to fetch for it). */
    private fun diarReady(): Boolean = diarHostReady() && diarGraphReady()
    /** The graph the diarizer loads: the f380 one once installed, else the f190 stand-in (verified by the old set's
     *  NEMO_REVISION stamp, which [diarHostReady] checks); null when neither is there. */
    private fun diarGraph(): File? = when {
        !diarHostReady() -> null
        diarGraphReady() -> File(diarGraphDir, DIAR_GRAPH_FILE)
        File(nemoDir, STANDIN_DIAR_FILE).length() == STANDIN_DIAR_BYTES -> File(nemoDir, STANDIN_DIAR_FILE)
        else -> null
    }
    private fun hostReady(): Boolean =
        File(hostDir, AsrRegistry.HOST.file).length() == AsrRegistry.HOST.bytes && stamped(hostDir, AsrRegistry.HOST.sha256)
    /** The variant's graph and the shared host weights are in place (size + the stamp written after the SHA check). */
    fun variantReady(v: AsrVariant): Boolean =
        hostReady() && File(variantDir(v), v.tflite.file).length() == v.tflite.bytes && stamped(variantDir(v), v.tflite.sha256)
    private fun legacyReady(): Boolean =
        LEGACY_FILES.all { (name, bytes) -> File(nemoDir, name).length() == bytes } && stamped(nemoDir, NEMO_REVISION)

    /** The files the engine loads for the chosen latency [targetMs]: that variant once installed, else the installed
     *  variant closest to it, else the pre-variant 480 ms pair; null when nothing can run yet. */
    fun asrEngineFiles(targetMs: Int): AsrEngineFiles? {
        val diar = diarGraph() ?: return null
        val pre = preEncodeF32()
        AsrRegistry.runnable(targetMs, ::variantReady)?.let { v ->
            return AsrEngineFiles(NemoModelFiles(File(hostDir, AsrRegistry.HOST.file), File(nemoDir, HOST_DIAR_FILE)),
                NemoLitertFiles(File(variantDir(v), v.tflite.file), diar, pre), v.tokenOffsetMs, v.modelId, v)
        }
        if (legacyReady()) return AsrEngineFiles(NemoModelFiles(File(nemoDir, LEGACY_HOST_FILE), File(nemoDir, HOST_DIAR_FILE)),
            NemoLitertFiles(File(nemoDir, LEGACY_TFLITE_FILE), diar, pre), LEGACY_TOKEN_OFFSET_MS, LEGACY_MODEL_ID, null)
        return null
    }

    /** Builds the LiteRT engine once so XNNPACK writes its weight cache next to the graphs; the next
     *  load maps that cache instead of repacking, which keeps the peak RSS at the ggml level. */
    fun warmLitertCache(targetMs: Int, threads: Int) {
        val f = asrEngineFiles(targetMs) ?: return
        runCatching { NemoStreamEngine(f.host, threads, backend = "litert_host", xasrTflite = f.litert.xasr, diarTflite = f.litert.diar,
            diarPreF32 = f.litert.preEncode, tokenOffsetMs = f.tokenOffsetMs).close() }
            // The chosen variant just loaded (a latency switch's download ends here): the others go now, not at the
            // next recording or import (they stayed on disk until then, ~155 MB each). A stand-in still running has
            // its files open: unlinking them does not disturb it.
            .onSuccess { reclaimLoaded(f, targetMs) }
    }

    /** Some recognizer can run (the chosen one or a stand-in): recording and import need nothing more. */
    fun asrReady(targetMs: Int): Boolean = asrEngineFiles(targetMs) != null
    /** The chosen variant itself is installed (nothing left to fetch for [targetMs]). */
    fun asrTargetReady(targetMs: Int): Boolean = diarReady() && variantReady(AsrRegistry.byLatency(targetMs))

    /** Bytes still to fetch for [targetMs]: the diarizer's host weights and graph, the X-ASR host weights, the variant's
     *  graph, as missing. */
    fun asrDownloadBytes(targetMs: Int): Long {
        val v = AsrRegistry.byLatency(targetMs)
        return (if (diarHostReady()) 0L else DIAR_HOST.bytes) + (if (diarGraphReady()) 0L else DIAR_GRAPH.bytes) +
            (if (hostReady()) 0L else AsrRegistry.HOST.bytes) + (if (variantReady(v)) 0L else v.tflite.bytes)
    }

    /** Remove the model directory so the next run re-downloads a clean copy (load failure recovery). */
    fun deleteAsr() {
        nemoDir.takeIf(File::exists)?.deleteRecursively()
    }

    /** Fetch what the chosen variant [targetMs] still misses (diarizer files, host weights, its graph); every file is
     *  SHA-checked at download and each set stamped once complete, so a pause resumes and a re-pin refetches. */
    suspend fun ensureAsrModels(targetMs: Int, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val v = AsrRegistry.byLatency(targetMs)
        if (asrTargetReady(targetMs)) { reclaimRetired(); onProgress(1f); return@withContext }
        nemoDir.mkdirs()
        val total = asrDownloadBytes(targetMs).coerceAtLeast(1L)
        var done = 0L
        suspend fun fetchSet(dir: File, stamp: String, files: Map<String, Pinned>) {
            dir.mkdirs()
            val marked = beginSet(dir, stamp, files.keys)
            files.forEach { (name, meta) ->
                val dest = File(dir, name)
                if (dest.length() != meta.bytes || !marked) {
                    dest.delete()
                    download(meta.url, dest, meta.sha256) { frac -> onProgress((done + (frac * meta.bytes).toLong()).toFloat() / total) }
                }
                done += meta.bytes
                onProgress(done.toFloat() / total)
            }
            endSet(dir, stamp)
        }
        if (!diarHostReady()) fetchSet(nemoDir, NEMO_REVISION, mapOf(HOST_DIAR_FILE to DIAR_HOST))
        if (!diarGraphReady()) fetchSet(diarGraphDir, DIAR_GRAPH.sha256, mapOf(DIAR_GRAPH_FILE to DIAR_GRAPH))
        if (!hostReady()) AsrRegistry.HOST.let { h -> fetchSet(hostDir, h.sha256, mapOf(h.file to Pinned(h.url, h.bytes, h.sha256))) }
        if (!variantReady(v)) v.tflite.let { t -> fetchSet(variantDir(v), t.sha256, mapOf(t.file to Pinned(t.url, t.bytes, t.sha256))) }
        check(asrTargetReady(targetMs)) { "ASR model files missing after provisioning" }
        reclaimRetired()
        warmLitertCache(targetMs, threads = 4)
    }

    /** An engine built from [f] for the chosen latency [targetMs] has loaded: what it replaces goes. */
    fun reclaimLoaded(f: AsrEngineFiles, targetMs: Int) {
        if (f.variant?.latencyMs == targetMs) runCatching { reclaimOtherAsr(f.variant) }
        // The f380 diarizer loaded: the f190 stand-in and its XNNPACK cache (~100 MB each) go.
        if (f.litert.diar.parentFile == diarGraphDir)
            listOf(STANDIN_DIAR_FILE, "$STANDIN_DIAR_FILE.wcache").forEach { File(nemoDir, it).takeIf(File::exists)?.delete() }
    }

    /** Once [running] (the chosen variant) has loaded: every other recognizer is deleted — the other variants and the
     *  pre-variant 480 ms pair with their XNNPACK caches. Never called with a stand-in, so a working one always remains. */
    fun reclaimOtherAsr(running: AsrVariant) {
        AsrRegistry.ALL.filter { it != running }.forEach { variantDir(it).takeIf(File::exists)?.deleteRecursively() }
        (LEGACY_FILES.keys + LEGACY_FILES.keys.map { "$it.wcache" }).forEach { File(nemoDir, it).takeIf(File::exists)?.delete() }
    }

    /** Only once the new models verify (also when they were already present — seeded or from an
     *  earlier run): reclaim every retired engine's files. */
    private fun reclaimRetired() {
        DROPPED_BACKEND_DIRS.forEach { File(modelsDir, it).takeIf(File::exists)?.deleteRecursively() }
        DROPPED_FILES.forEach { File(modelsDir, it).takeIf(File::exists)?.delete() }
        // The ggml engine's GGUFs (270 MB), replaced by the packed host weights.
        listOf(XASR_FILE, DIAR_FILE).forEach { File(nemoDir, it).takeIf(File::exists)?.delete() }
    }

    /** Superseded reader models, deleted only once the current one verifies (a failed download
     *  never leaves the device without a summarizer). */
    private fun reclaimRetiredLlm() {
        RETIRED_LLM_DIRS.forEach { File(modelsDir, it).takeIf(File::exists)?.deleteRecursively() }
        reclaimReplacedLlm()
    }

    /** Replaced reader models (E4B mobile-v1), deleted once their successor is installed AND has loaded
     *  once (its weight cache exists): until then the reader keeps running the old one. */
    fun reclaimReplacedLlm() {
        LlmRegistry.reclaimable { llmReady(it) && File(llmDir(it), WEIGHT_CACHE).exists() }.forEach { old ->
            llmDir(old).takeIf(File::exists)?.let { android.util.Log.i("primeminutes-models", "deleting replaced ${old.id}"); it.deleteRecursively() }
        }
    }

    /** The reader model to run for the saved choice [id] (see [LlmRegistry.runnable]). */
    fun readerSpec(id: String): LlmSpec = LlmRegistry.runnable(id, ::llmReady)

    /** Bytes [spec] still has to download: its files not yet in its folder and not lent by an installed model. */
    fun llmDownloadBytes(spec: LlmSpec): Long {
        if (llmReady(spec)) return 0L
        fun read(name: String) = runCatching { File(llmDir(spec), name).readText().trim() }.getOrNull()
        // A partial set of this revision keeps its complete files (beginSet); any other is fetched again.
        val marked = read(REVISION_MARKER) == spec.revision || read(FETCHING_MARKER) == spec.revision
        return spec.files.entries.sumOf { (rel, meta) ->
            val have = marked && File(llmDir(spec), rel).length() == meta.first
            if (have || reuseSource(spec, rel, LlmRegistry.ALL, ::llmDir) { llmReady(it) } != null) 0L else meta.first
        }
    }

    // --- LLM: a revision-pinned, multi-file artifact set under its own directory. --------------
    // The summarizer is no longer a single `.litertlm` bundle: it is a LiteRT graph + a PRE-PACKED
    // XNNPACK weight cache + a tokenizer blob. The weight cache is the load-bearing part — without
    // it XNNPACK materialises ~800 MiB of UNRECLAIMABLE anonymous memory at load and the
    // lowmemorykiller takes the app; with it those pages are file-backed and evictable. It is bound
    // to the exact app-shipped libLiteRt.so build (app/src/main/jniLibs/arm64-v8a), so repack and
    // re-pin it whenever that library is upgraded — a stale cache is rejected loudly (the header
    // carries a version) and the engine then falls back to a multi-minute on-device pack.
    fun llmDir(spec: LlmSpec): File = File(modelsDir, spec.dirName)
    fun llmFile(spec: LlmSpec): File = File(llmDir(spec), spec.mainFile)
    fun llmWeightCache(spec: LlmSpec): File? =
        spec.weightCacheFile.takeIf { it.isNotBlank() }?.let { File(llmDir(spec), it) }
    fun llmTokenizer(spec: LlmSpec): File = File(llmDir(spec), spec.tokenizerFile)

    fun llmReady(spec: LlmSpec): Boolean =
        spec.files.all { (rel, meta) -> File(llmDir(spec), rel).length() == meta.first } &&
            runCatching { File(llmDir(spec), REVISION_MARKER).readText().trim() }.getOrNull() == spec.revision

    // No-arg convenience over the default model (used by tests / the device push flow).
    val llmModel: File get() = llmFile(LlmRegistry.byId(LlmRegistry.DEFAULT_ID))
    fun llmReady(): Boolean = llmReady(LlmRegistry.byId(LlmRegistry.DEFAULT_ID))

    // --- Semantic-search embedder (EmbeddingGemma-2, studio.primeminutes.core.embed) ---

    fun embedDir(spec: EmbedSpec): File = File(modelsDir, spec.dirName)

    fun embedReady(spec: EmbedSpec): Boolean =
        spec.files.all { (rel, meta) -> File(embedDir(spec), rel).length() == meta.first } &&
            runCatching { File(embedDir(spec), REVISION_MARKER).readText().trim() }.getOrNull() == spec.revision

    /** Same contract as [ensureLlmModel]: per-file sha256, resumable, revision-stamped. */
    suspend fun ensureEmbed(spec: EmbedSpec, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (embedReady(spec)) { onProgress(1f); return@withContext }
        val dir = embedDir(spec).apply { mkdirs() }
        val marked = beginSet(dir, spec.revision, spec.files.keys)
        val total = spec.totalBytes
        var done = 0L
        spec.files.forEach { (rel, meta) ->
            val (bytes, sha) = meta
            val dest = File(dir, rel).apply { parentFile?.mkdirs() }
            if (dest.length() != bytes || !marked) {
                if (dest.exists()) dest.delete()
                download("${spec.revision}/$rel", dest, sha) { frac ->
                    onProgress((done + (frac * bytes).toLong()).toFloat() / total)
                }
            }
            done += bytes
            onProgress(done.toFloat() / total)
        }
        endSet(dir, spec.revision)
        check(embedReady(spec)) { "${spec.displayName} files missing after provisioning" }
        studio.primeminutes.core.embed.EmbedHost.provisioned.value++
    }

    // --- Speaker voice model (People, opt-in): same contract as the embedder. ---

    fun speakerDir(card: studio.primeminutes.core.people.SpeakerCard): File = File(modelsDir, card.dirName)

    fun speakerReady(card: studio.primeminutes.core.people.SpeakerCard): Boolean =
        card.files.all { (rel, meta) -> File(speakerDir(card), rel).length() == meta.first } &&
            runCatching { File(speakerDir(card), REVISION_MARKER).readText().trim() }.getOrNull() == card.revision

    suspend fun ensureSpeaker(card: studio.primeminutes.core.people.SpeakerCard, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (speakerReady(card)) { onProgress(1f); return@withContext }
        val dir = speakerDir(card).apply { mkdirs() }
        val marked = beginSet(dir, card.revision, card.files.keys)
        var done = 0L
        card.files.forEach { (rel, meta) ->
            val (bytes, sha) = meta
            val dest = File(dir, rel).apply { parentFile?.mkdirs() }
            if (dest.length() != bytes || !marked) {
                if (dest.exists()) dest.delete()
                download("${card.revision}/$rel", dest, sha) { frac -> onProgress((done + (frac * bytes).toLong()).toFloat() / card.totalBytes) }
            }
            done += bytes
            onProgress(done.toFloat() / card.totalBytes)
        }
        endSet(dir, card.revision)
        check(speakerReady(card)) { "${card.displayName} files missing after provisioning" }
    }

    // --- Storage manager: enumerate + delete downloaded models (each re-downloads on next use). ---

    enum class ModelKind { ASR, LLM, EMBED, OTHER }

    /** A model artifact (file or folder) on disk. [delete] reclaims it; it re-downloads on next use. */
    data class StoredModel(val name: String, val kind: ModelKind, val bytes: Long, private val path: File) {
        fun delete(): Boolean = if (path.isDirectory) path.deleteRecursively() else path.delete()
    }

    /** Every model currently on disk under [modelsDir], largest first, with a coarse kind for labels.
     *  Note: a model in use is memory-mapped, so deleting it just unlinks the name — the running
     *  inference keeps its open handle and finishes fine; the space frees once it's released. */
    fun storedModels(): List<StoredModel> =
        (modelsDir.listFiles()?.toList() ?: emptyList())
            .filterNot { it.isFile && (it.name.endsWith(PART_SUFFIX) || it.name.endsWith("$PART_SUFFIX.vld")) }   // hide in-flight/stale temp files
            .map { f -> StoredModel(f.name, kindOf(f.name), dirSize(f), f) }
            .filter { it.bytes > 0L }
            .sortedByDescending { it.bytes }

    /**
     * Delete "<name>.part" temp files (and their ".vld" validators) left by downloads interrupted more
     * than [PART_MAX_AGE_MS] ago. A recent one is kept: the next download of that file RESUMES from it
     * (Range + If-Range, then the sha256 check), so a kill or a network loss on a 2 GB model does not
     * restart it from zero. An old one belongs to a model the user stopped asking for: its space goes back.
     */
    fun sweepStalePartFiles() {
        val cutoff = System.currentTimeMillis() - PART_MAX_AGE_MS
        runCatching {
            modelsDir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(PART_SUFFIX) || it.name.endsWith("$PART_SUFFIX.vld")) && it.lastModified() < cutoff }
                .forEach { it.delete() }
        }
    }

    private fun dirSize(f: File): Long =
        if (f.isDirectory) (f.listFiles()?.sumOf { dirSize(it) } ?: 0L) else f.length()

    private fun kindOf(name: String): ModelKind {
        val n = name.lowercase()
        return when {
            n == NEMO_DIR -> ModelKind.ASR
            LlmRegistry.ALL.any { it.dirName == n } -> ModelKind.LLM
            EmbedRegistry.ALL.any { it.dirName == n } -> ModelKind.EMBED
            // MOSS-TD is an ASR model that happens to ship as a .gguf — classify it before the
            // generic gguf→LLM rule below, or Settings lists it as a summary model.
            n.startsWith("moss-td") || n.startsWith("moss-transcribe") || n.startsWith("moss_td") -> ModelKind.ASR
            n.endsWith(".gguf") || n.endsWith(".litertlm") || n.startsWith("qwen35-") -> ModelKind.LLM
            n.contains("asr") || n.contains("sense-voice") || n.contains("sensevoice") || n.contains("qwen") || n.startsWith("sherpa") -> ModelKind.ASR
            else -> ModelKind.OTHER
        }
    }

    /**
     * Ensure every file of the summarizer artifact set for [spec] is present, the right size and
     * the right sha256, downloading what is missing. Revision-pinned: a directory left over from a
     * different pinned revision is re-fetched rather than half-trusted, because the weight cache
     * and the graph must come from the same export (a cache from another build is rejected at load
     * and costs the user a silent multi-minute on-device repack).
     */
    suspend fun ensureLlmModel(spec: LlmSpec, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (llmReady(spec)) { reclaimRetiredLlm(); onProgress(1f); return@withContext }
        val dir = llmDir(spec).apply { mkdirs() }
        val marked = beginSet(dir, spec.revision, spec.files.keys + WEIGHT_CACHE)
        val total = spec.totalBytes
        var done = 0L
        spec.files.forEach { (rel, meta) ->
            val (bytes, sha) = meta
            val dest = File(dir, rel).apply { parentFile?.mkdirs() }
            if (dest.length() != bytes || !marked) {
                // A file from another revision (same size or not) is replaced, never kept under the new stamp:
                // download() is a no-op on an existing destination.
                if (dest.exists()) dest.delete()
                // XNNPACK's packed-weight cache is keyed by buffer index, not content: weights from another
                // revision would run on the old packed values. Any fetched file invalidates it.
                File(dir, WEIGHT_CACHE).delete()
                // The same file in another installed model (mobile-v2 E4B shares mobile-v1's embedders and
                // tokenizer, byte for byte): copied, checked, and not downloaded again.
                val local = reuseSource(spec, rel, LlmRegistry.ALL, ::llmDir) { llmReady(it) }
                if (local == null || !copyVerified(local, dest, sha)) {
                    download("${spec.revision}/$rel", dest, sha) { frac ->
                        onProgress((done + (frac * bytes).toLong()).toFloat() / total)
                    }
                }
            }
            done += bytes
            onProgress(done.toFloat() / total)
        }
        endSet(dir, spec.revision)
        check(llmReady(spec)) { "${spec.displayName} files missing after provisioning" }
        reclaimRetiredLlm()
    }

    /** No-arg convenience over the default model. */
    suspend fun ensureLlmModel(onProgress: (Float) -> Unit) =
        ensureLlmModel(LlmRegistry.byId(LlmRegistry.DEFAULT_ID), onProgress)

    /**
     * Download to a temp file, verify its SHA-256 (when pinned), then atomically rename into
     * place. A checksum mismatch deletes the temp file and throws — no half-trusted model is
     * ever used. The pins are the FOSS release artifacts' real hashes (see manifest / below).
     */
    private suspend fun download(url: String, dest: File, sha256: String? = null, onProgress: (Float) -> Unit) {
        // Serialize downloads that target the SAME destination. Two coroutines can race to provision
        // the same model — e.g. the pipeline's summarize() and a user-triggered "re-detect names" both
        // call ensureLlmModel for the same GGUF, both see it absent, and both open the shared
        // "<name>.part" temp file (outputStream() truncates on open), interleaving writes into a corrupt
        // model that is then committed and crashes llama.cpp on mmap on every load until app data is
        // cleared. One Mutex per destination + an existence re-check inside it make the loser a no-op.
        val mutex = downloadLocks.computeIfAbsent(dest.absolutePath) { Mutex() }
        mutex.withLock {
            if (dest.exists()) return@withLock
            val tmp = File(dest.parentFile, "${dest.name}$PART_SUFFIX")
            fun clearPartial() { tmp.delete(); File(tmp.parentFile, "${tmp.name}.vld").delete() }
            // Retry transient failures (flaky mobile network, a 5xx, a body that fails the checksum)
            // with linear backoff. A transient failure KEEPS the .part so the next attempt RESUMES
            // from where the stream died (Range request in fetchToFile) — on weak Wi-Fi a large
            // model whose connection keeps dropping would otherwise restart from zero every retry
            // and never complete (observed on-device: a 14 MB file dying "unexpected end of stream"
            // three times in a row). Only a checksum mismatch — corrupt bytes — restarts clean.
            // Abort immediately on user-cancel and on permanent errors (404 / out-of-disk).
            var attempt = 0
            while (true) {
                attempt++
                try {
                    fetchToFile(url, tmp, onProgress)
                    if (sha256 != null) {
                        val actual = sha256Of(tmp)
                        if (!actual.equals(sha256, ignoreCase = true))
                            throw ChecksumMismatch("expected ${sha256.take(12)}..., got ${actual.take(12)}...")
                    }
                    File(tmp.parentFile, "${tmp.name}.vld").delete()   // done — drop the resume validator
                    check(tmp.renameTo(dest)) { "Could not move ${tmp.name} into place" }
                    return@withLock
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    clearPartial(); throw ce
                } catch (e: Exception) {
                    if (e is ChecksumMismatch) clearPartial()   // corrupt bytes — resume can't fix them
                    if (e is ModelNotFound || isOutOfSpace(e) || attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                        // A network that stays down past the retries keeps the partial: the next try
                        // (the queue, a new recording, the next launch) resumes from it.
                        if (e is ModelNotFound || isOutOfSpace(e)) clearPartial()
                        throw java.io.IOException(downloadErrorMessage(e, dest.name, attempt), e)
                    }
                    delay(RETRY_BACKOFF_MS * attempt)
                }
            }
        }
    }

    /** One attempt: HTTP GET [url] → [tmp], checking the status code and cooperating with cancellation
     *  (a blocking read() never self-checks, so the loop must, or Stop can't abort a multi-GB fetch).
     *  A non-empty [tmp] is RESUMED with a Range request (HF/CDNs support it); a server that ignores
     *  the range (plain 200) truncates and starts over, and 416 (our offset is past the end — a
     *  stale .part from a changed upstream file) clears the partial so the retry starts clean. */
    private suspend fun fetchToFile(url: String, tmp: File, onProgress: (Float) -> Unit) {
        val offset = tmp.length()
        // Integrity for unpinned (no-checksum) resumes: a plain Range resume assumes the upstream
        // file is byte-identical to the partial — but a CDN/mirror could serve a changed file,
        // committing head(old)+tail(new). If-Range makes the server honour the range ONLY when the
        // validator (ETag/Last-Modified, captured on the first fetch into a .vld sidecar) still
        // matches; otherwise it returns a full 200 and we truncate + restart clean below.
        val vld = File(tmp.parentFile, "${tmp.name}.vld")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
                vld.takeIf { it.exists() }?.let { setRequestProperty("If-Range", it.readText()) }
            }
        }
        try {
            val code = conn.responseCode
            val resumed = code == 206 && offset > 0
            // Remember the validator for the NEXT resume (only useful when the server supports it).
            (conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"))
                ?.let { runCatching { vld.writeText(it) } }
                ?: runCatching { vld.delete() }
            when {
                code == 206 || code in 200..299 -> {}
                code == 404 -> throw ModelNotFound("HTTP 404")
                code == 416 -> { tmp.delete(); vld.delete(); throw java.io.IOException("server returned HTTP 416 (stale partial cleared)") }
                else -> throw java.io.IOException("server returned HTTP $code")
            }
            conn.inputStream.use { input ->
                val body = conn.contentLengthLong.takeIf { it > 0 }
                val total = if (resumed) body?.plus(offset) else body
                java.io.FileOutputStream(tmp, /* append = */ resumed).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = if (resumed) offset else 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private class ModelNotFound(msg: String) : java.io.IOException(msg)
    private class ChecksumMismatch(msg: String) : java.io.IOException(msg)

    /** A failure waiting cannot fix (file gone from the server, disk full); anything else (network,
     *  a suspended app, a 5xx) is worth resuming from the partial file. */
    fun isPermanent(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) { if (t is ModelNotFound) return true; t = t.cause }
        return isOutOfSpace(e)
    }

    /** True if [e] (or a cause) is an out-of-disk-space failure — retrying the download can't help. */
    private fun isOutOfSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("ENOSPC", ignoreCase = true) || m.contains("No space", ignoreCase = true)) return true
            t = t.cause
        }
        return false
    }

    /** A clear, actionable message for the failure surfaced to the user (TranscriptEvent.Failed). */
    private fun downloadErrorMessage(e: Throwable, name: String, attempts: Int): String {
        val tries = if (attempts > 1) " after $attempts attempts" else ""
        return when {
            e is ChecksumMismatch -> "$name download was corrupted (checksum mismatch)$tries. Please try again."
            e is ModelNotFound -> "$name isn't available on the server (404) — try updating the app."
            isOutOfSpace(e) -> "Not enough storage to download $name. Free up space and try again."
            e is UnknownHostException -> "No internet connection while downloading $name. Reconnect and try again."
            e is SocketTimeoutException -> "Download of $name timed out$tries. Check your connection and try again."
            else -> "Couldn't download $name$tries: ${e.message ?: e.javaClass.simpleName}."
        }
    }

    /** [src] linked (no second copy of a GB file) or else copied to [dest] through a temporary name, kept
     *  only when its sha256 is [sha]. Android refuses app hard links (seen on the Note10+): there it is a
     *  copy, and the space comes back when the old model is deleted. */
    private fun copyVerified(src: File, dest: File, sha: String): Boolean = runCatching {
        val tmp = File(dest.path + ".copy")
        tmp.delete()
        val linked = runCatching { java.nio.file.Files.createLink(tmp.toPath(), src.toPath()); true }.getOrDefault(false)
        if (!linked) src.copyTo(tmp, overwrite = true)
        if (sha256Of(tmp) != sha) { tmp.delete(); return false }
        tmp.renameTo(dest)
    }.getOrDefault(false)

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Written next to a spec's files, recording the pinned revision they came from. */
        const val REVISION_MARKER = ".revision"
        /** Written while a spec's files are being fetched: the revision the complete ones came from. */
        const val FETCHING_MARKER = ".fetching"

        /**
         * A file set for [revision] is about to be provisioned in [dir]. Returns true when the files
         * there that have the right size may be kept: the set is stamped with [revision], or an
         * earlier pass of this same fetch (a paused prefetch that resumes, a retry, a relaunch)
         * completed them. A paused prefetch used to fetch every finished file of the set again (the
         * stamp is only written at the end), so its progress fell back and a 2 GB model restarted.
         * Otherwise the set's [files] (from another revision, or of unknown origin) are deleted
         * before the fetch is marked as begun, so nothing stale can later pass for complete.
         */
        /**
         * A complete copy of [spec]'s file [rel] in another model's folder: a file of [all] with the same
         * size and sha256, in a model that is installed ([ready]: stamped with its revision) and has it
         * at that size. Null when there is none (download it).
         */
        internal fun reuseSource(spec: LlmSpec, rel: String, all: List<LlmSpec>, dirOf: (LlmSpec) -> File, ready: (LlmSpec) -> Boolean): File? {
            val meta = spec.files[rel] ?: return null
            return all.asSequence().filter { it.id != spec.id && it.dirName != spec.dirName }.flatMap { o ->
                o.files.filterValues { it == meta }.keys.asSequence().map { o to File(dirOf(o), it) }
            }.firstOrNull { (o, f) -> f.length() == meta.first && ready(o) }?.second
        }

        internal fun beginSet(dir: File, revision: String, files: Collection<String>): Boolean {
            fun read(name: String) = runCatching { File(dir, name).readText().trim() }.getOrNull()
            if (read(REVISION_MARKER) == revision || read(FETCHING_MARKER) == revision) return true
            files.forEach { File(dir, it).takeIf(File::exists)?.delete() }
            runCatching { File(dir, FETCHING_MARKER).writeText(revision) }
            return false
        }

        /** Every file of the set is in place: stamp it with [revision]. */
        internal fun endSet(dir: File, revision: String) {
            runCatching { File(dir, REVISION_MARKER).writeText(revision) }
            File(dir, FETCHING_MARKER).delete()
        }
        /** XNNPACK packed-weight cache next to a reader model's files (built on first load). */
        const val WEIGHT_CACHE = "weights.xnnpack_cache"

        // Per-destination download locks, shared across ALL ModelManager instances (the UI's
        // detect-names path constructs its own ModelManager), so concurrent first-run downloads of the
        // same file can't interleave-corrupt the shared ".part" temp. See download().
        private val downloadLocks = ConcurrentHashMap<String, Mutex>()

        // Retry transient download failures (flaky network, a 5xx, a body that fails the checksum)
        // with linear backoff before giving up; permanent errors (404 / out-of-disk) abort at once.
        // Attempts are cheap now that retries RESUME the partial file — each one makes forward
        // progress, so more attempts = strictly better odds on a flaky link.
        private const val MAX_DOWNLOAD_ATTEMPTS = 6
        private const val RETRY_BACKOFF_MS = 1500L
        private const val PART_MAX_AGE_MS = 3L * 24 * 3600 * 1000

        /** True if [f] looks like a complete GGUF: starts with the "GGUF" magic and is at least 90% of
         *  [expectedBytes] — catches truncated/corrupt downloads without pinning an exact (upstream-
         *  mutable) hash. Exposed for unit tests; see ensureLlmModel(). */
        internal fun isValidGguf(f: File, expectedBytes: Long): Boolean {
            if (expectedBytes > 0 && f.length() < expectedBytes / 10 * 9) return false
            return runCatching {
                f.inputStream().use { ins ->
                    val magic = ByteArray(4)
                    ins.read(magic) == 4 &&
                        magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                        magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
                }
            }.getOrDefault(false)
        }

        /** Integrity check dispatching on artifact type: `.litertlm` bundles start with the
         *  ASCII magic "LITERTLM"; everything else is a GGUF. */
        internal fun isValidLlmFile(f: File, expectedBytes: Long): Boolean {
            if (!f.name.endsWith(".litertlm")) return isValidGguf(f, expectedBytes)
            if (expectedBytes > 0 && f.length() < expectedBytes / 10 * 9) return false
            return runCatching {
                f.inputStream().use { ins ->
                    val magic = ByteArray(8)
                    ins.read(magic) == 8 && magic.toString(Charsets.US_ASCII) == "LITERTLM"
                }
            }.getOrDefault(false)
        }

        // Mirrors models/manifest.json. All FOSS-licensed. LLM specs live in LlmRegistry.
        /** Suffix of the temp file a download streams into before it's verified and renamed into place. */
        private const val PART_SUFFIX = ".part"

        /** Dirs of backends dropped in 2026-07 (SenseVoice LiteRT + sherpa, Qwen3) and 2026-08
         *  (Nemotron — a held-out zh-TW bench showed it ~2x worse CER than X-ASR, and the app
         *  targets zh-TW meetings only, not the 25-language coverage Nemotron traded accuracy
         *  for), reclaimed on upgrade. */
        private val DROPPED_BACKEND_DIRS = listOf(
            "nemotron-litert",
            "sensevoice-litert",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            // TurboQuant TQ3 summarizer, retired with Gemma 4: the engine existed ONLY to run
            // Gemma 4 E2B on low-RAM devices, and Qwen3.5-0.8B now does that job strictly better
            // (3.4 vs ~1 tok/s, 874 MB of artifacts vs 6.9 GB, and it is not LMK-killed). This is
            // the single biggest reclaim in the app's history — existing installs that ever opted
            // in are carrying ~6.9 GiB of dead weight.
            "tq3-litert",
            // LiteRT summarizer artifact set (graph + pre-packed XNNPACK weight cache +
            // tokenizer, ~874 MB), retired when the summarizer moved back to llama.cpp/GGUF.
            // Gemma 4 was the only reason LiteRT-LM was worth its costs and Gemma 4 is gone;
            // with it go the baked-in context length, the hand-written NEON int8-KV kernel and
            // the weight cache that had to exist because XNNPACK materialised ~800 MiB of
            // unreclaimable anonymous memory. llama.cpp mmaps a single 533 MB GGUF instead.
            "qwen35-litert",
            // LiteRT X-ASR + Silero VAD + pyannote + CAM++, replaced 2026-09 by the streaming
            // nemo-x-asr-diarizer (same X-ASR model family, now on ggml, plus Nemotron-3 diarization).
            "xasr-litert",
            // MiniCPM5 CURSOR summarizer + Granite verifier, replaced 2026-09 by the Gemma-4-E2B
            // meeting agent (core/reader).
            "minicpm5-cursor-gguf", "granite-verifier-gguf",
        )

        /** Earlier meeting-agent versions (v3 root GGUF, v5), replaced by v11; reclaimed after v11 verifies. */
        private val RETIRED_LLM_DIRS = listOf("gemma4-meeting-agent-gguf", "gemma4-meeting-agent", "gemma4-meeting-agent-v5-gguf", "gemma4-meeting-agent-v11-gguf")

        /** Retired by the nemo switch; reclaimed only after the new models verify. */
        private val LITERT_RETIRED = setOf(
            "xasr-litert", "silero-vad.tflite", "pyannote-segmentation.tflite",
            "campplus_cn_common_500f.tflite",
        )

        /** Single files from removed engines, reclaimed at construction — the whole
         *  ggml/GGUF-era stack plus retired ONNX models (old installs carry up to
         *  ~1.5 GB of these; seen live on the Boox). The silero .onnx served sherpa
         *  only; the .tflite VAD is NOT listed (X-ASR/SenseVoice use it). */
        private val DROPPED_FILES = listOf(
            "silero_vad.onnx",
            // Retired summarizer bundles. Gemma 4 E2B/E4B were removed outright (E2B could not
            // load at all on a 3.7 GB device, at any nCtx — WEIGHTS, not KV, set that floor), and
            // the Qwen3-0.6B fine-tune went with them when Qwen3.5-0.8B became the sole
            // summarizer. Together up to ~6.9 GB on an install that tried all three.
            "gemma-4-e2b-it.litertlm", "gemma-4-e4b-it.litertlm",
            "primeminutes-qwen3-0.6b_q8_ekv32768.litertlm",
            "qwen3.5-0.8b.gguf", "qwen3-0.6b.gguf", "gemma-3-1b.gguf",
            // MOSS-TD dropped from this ANDROID app 2026-08 (kept on desktop, where it is
            // fast and the most accurate backend — this is a phone-specific call): RTF ~4.4x on
            // the OPPO reference device made a 60-min meeting a ~4.4-hour transcription, and its
            // accuracy edge (7.74 vs X-ASR's 12.25 CER) didn't justify that on this hardware.
            // Root cause is memory-bandwidth-bound decode on a 2-big-core mobile SoC — already
            // running every available core, so no thread/scheduling fix was possible.
            // Diarization survives via the separate pyannote+CAM++ pipeline (still X-ASR's).
            "moss-td-zhtw-v7-q4_k_m.gguf", "moss-td-zhtw-v61-q4_k_m.gguf",
            "moss-transcribe-base-q4mix.gguf", "moss_td_decoder_q4b32_ekv2560.tflite",
            "moss_td_encoder_q8.tflite", "moss_td_embedder_q8.tflite",
            "moss_td_decoder_v2_q4b32_ekv2560.tflite", "moss_td_vocab.json", "moss_td_merges.txt",
            "campplus-cn-common.gguf", "campplus_zh_en.onnx", "campplus_zh_en_fp16.onnx",
            "pyannote_segmentation_3_0.onnx", "wespeaker_emb_fp16.tflite",
            "speaker_embedding.onnx",
            // Base Qwen3.5-0.8B, superseded by the Prime Minutes meeting fine-tune. Same dirName and the
            // same Q4_K_M recipe, but a different FILENAME, so provisioning writes the new GGUF
            // beside the old one instead of over it — 508 MB stranded on every existing install.
            "qwen35-gguf/Qwen3.5-0.8B-Q4_K_M.gguf",
            "silero-vad.tflite", "pyannote-segmentation.tflite", "campplus_cn_common_500f.tflite",
            "sherpa-onnx-zipformer-zh-en-2023-11-22",
            "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03",
        )

        private data class Pinned(val url: String, val bytes: Long, val sha256: String)

        private const val NEMO_DIR = "nemo"
        private const val XASR_FILE = "x-asr-zh-en-q8_0.gguf"
        private const val DIAR_FILE = "nemotron-3-diarization-q8_0.gguf"
        private const val XASR_REV = "acb1a95eac809719a2c86d1048471f96fc6444ad"
        private const val DIAR_REV = "647d39feaa0e91dca5ce355a95403837b76dff56"
        /** Stamped into nemo/[REVISION_MARKER] when the diarizer host weights (before the f380 graph, with the f190 graph;
         *  before the variants, also the X-ASR pair) verified. Kept unchanged so existing installs need no refetch. */
        private const val LITERT_REV = "ec1b218f4d152e09bf30733de0836afc3f6b686b"
        private const val HOST_REV = "37d2f9c8056f3427af324d0c1fc7910c70b65375"
        private const val NEMO_REVISION = "$LITERT_REV+$HOST_REV"
        /** The 190-frame graph (speaker cache 128) of installs before the f380 one: no longer downloaded, it runs until
         *  the f380 graph has loaded once ([reclaimLoaded]). */
        private const val STANDIN_DIAR_FILE = "sortformer_int8_f190.tflite"
        private const val STANDIN_DIAR_BYTES = 101_279_632L
        private const val PRE_ENCODE_F32 = "pre_encode_512x1024.f32"
        private const val PRE_ENCODE_F32_BYTES = 512L * 1024 * 4
        private const val HOST_DIAR_FILE = "diar_host.bin"
        private const val HOST_DIR = "xasr-punct-host"
        // Host weights of the ggml-free engine (tools/nemo-host/*_pack.py): the Sortformer mel bank + silence embedding;
        // the neural graph is the .tflite.
        private val DIAR_HOST = Pinned(
            "https://huggingface.co/Luigi/primeminutes-nemo-litert/resolve/$HOST_REV/$HOST_DIAR_FILE",
            133_648L, "5286c71593db47460a51d319605432d3103eaf1c4e99fbdebf53d5bf60e62c36",
        )
        // The diarizer graph with a 380-frame window: the checkpoint's trained 264-frame speaker cache + 50-frame chunk
        // + 12 right context fit (DiarHost derives the cache from the graph). Same weights as the f190 graph, exported
        // --frames 380. 2026-10-22: Note10+ real time (rtf 1.00, label lag p95 0.34 s), zh miss 39.0 -> 33.2 %.
        private const val DIAR_GRAPH_DIR = "sortformer-f380"
        private const val DIAR_GRAPH_FILE = "sortformer_int8_f380.tflite"
        private const val DIAR_GRAPH_REV = "693b752bd7a9e5128e174df09830aab5fceb8c22"
        private val DIAR_GRAPH = Pinned(
            "https://huggingface.co/Luigi/primeminutes-nemo-litert/resolve/$DIAR_GRAPH_REV/$DIAR_GRAPH_FILE",
            101_324_560L, "9aa577325f7258345e7f7ff407ebdb105d365f8e0383aa2da2d833a5d255a52f",
        )
        /** The pre-variant recognizer (X-ASR 480 ms, no punct model), never downloaded any more: it runs on installs that
         *  have it until the chosen variant is installed and has loaded once (then [reclaimOtherAsr]). */
        private const val LEGACY_TFLITE_FILE = "xasr_stream_q8_octav.tflite"
        private const val LEGACY_HOST_FILE = "xasr_host.bin"
        private const val LEGACY_TOKEN_OFFSET_MS = 300.0
        private const val LEGACY_MODEL_ID = studio.primeminutes.core.config.TranscriptionConfig.ASR_MODEL_ID
        private val LEGACY_FILES = linkedMapOf(LEGACY_TFLITE_FILE to 152_653_712L, LEGACY_HOST_FILE to 16_497_008L)
    }
}
