package studio.primeminutes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.primeminutes.core.embed.EmbedHost
import studio.primeminutes.core.embed.EmbedRegistry
import studio.primeminutes.core.embed.Embedder
import studio.primeminutes.core.embed.LibraryIndexer
import studio.primeminutes.core.embed.LibrarySearch
import studio.primeminutes.core.embed.SessionIndex
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.data.SpeakerName
import java.io.File
import kotlin.math.sqrt

/**
 * Semantic search on the device (plan §12). Needs the EmbeddingGemma files in
 * `files/models/embed-gemma2-270m/` of the app under test (seeded by test-on-device.sh via
 * PRIMEMINUTES_SEED_MODELS); skipped otherwise. The revision marker is written here so the
 * seeded copy counts as "ready" without a download.
 */
@RunWith(AndroidJUnit4::class)
class EmbedDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val spec = EmbedRegistry.byId(EmbedRegistry.DEFAULT_ID)
    private val dir get() = ModelManager(ctx).embedDir(spec)

    private fun assumeModel() {
        val present = spec.files.all { (rel, meta) -> File(dir, rel).length() == meta.first }
        assumeTrue("search model not pushed", present)
        File(dir, ModelManager.REVISION_MARKER).writeText(spec.revision)
        assertTrue(EmbedHost.ready(ctx))
        LibraryIndexer.ignoreBusy = true
    }

    @After fun tearDown() = runBlocking<Unit> {
        LibraryIndexer.ignoreBusy = false
        EmbedHost.unload()
        // `-e keep true` leaves the seeded meeting in place for a manual look at the app.
        if (InstrumentationRegistry.getArguments().getString("keep") != "true") SessionFixture.cleanUp()
    }

    private fun cos(a: FloatArray, b: FloatArray): Float {
        var d = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return d / (sqrt(na) * sqrt(nb))
    }

    /** T6: the device vectors match the host LiteRT vectors (same int4 model) — cos ≥ 0.999; logs latency per bucket. */
    @Test fun parityWithHost() {
        assumeModel()
        val refs = JSONArray(InstrumentationRegistry.getInstrumentation().context.assets.open("embed_ref.json").use { it.readBytes().decodeToString() })
        Embedder.load(dir, 2).use { e ->
            for (i in 0 until refs.length()) {
                val r = refs.getJSONObject(i)
                val text = r.getString("text")
                val ref = r.getJSONArray("vec").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                val t0 = System.nanoTime()
                val v = e.embed(text)
                val ms = (System.nanoTime() - t0) / 1e6
                val c = cos(v, ref)
                android.util.Log.i("EmbedDeviceTest", "tokens %d cos %.4f %.0f ms  %s".format(r.getInt("tokens"), c, ms, text.take(40)))
                assertEquals("token count", r.getInt("tokens"), e.tokens(text) + 2)
                assertTrue("parity cos=$c for '$text'", c >= 0.999f)
            }
        }
        assertEquals("handles released", 0, Embedder.live.get())
    }

    /** T5: index built for a seeded meeting, valid on disk, reused on rebuild; a rename re-embeds only that speaker's chunks;
     *  a corrupt file reads as absent; a leftover tmp file never shadows the index. */
    @Test fun indexLifecycle() = runBlocking<Unit> {
        assumeModel()
        SessionFixture.clear()
        val entry = SessionFixture.seed()
        assertTrue(LibraryIndexer.index(ctx, entry))
        val f = SessionIndex.file(entry.dir)
        assertTrue("index written", f.exists())
        val idx = requireNotNull(SessionIndex.read(entry.dir))
        val loaded = requireNotNull(PrimeMinutesSession.peek(entry.sessionFile))
        assertEquals("model id", spec.id, idx.modelId)
        assertEquals("fingerprint", SessionIndex.fingerprint(loaded).joinToString("") { "%02x".format(it) }, idx.fingerprint.joinToString("") { "%02x".format(it) })
        assertTrue(SessionIndex.isCurrent(entry.dir, spec.id, SessionIndex.fingerprint(loaded)))
        assertTrue("meeting + chunk vectors", idx.vectors.any { it.kind == SessionIndex.Kind.MEETING } && idx.vectors.any { it.kind == SessionIndex.Kind.CHUNK })

        // Rebuild with nothing changed: current, so no write and no embedding call.
        val calls0 = EmbedHost.calls.get()
        assertFalse(LibraryIndexer.index(ctx, SessionLibrary.byId(ctx, entry.id)!!))
        assertEquals("unchanged meeting: no embed call", calls0, EmbedHost.calls.get())

        // Rename Bob → only the chunks that carry his name change their text hash.
        val chunksBefore = idx.vectors.filter { it.kind == SessionIndex.Kind.CHUNK }.associate { it.a to it.textHash }
        SessionLibrary.attachResults(
            ctx, entry, SessionFixture.SEEDED,
            speakerNames = mapOf(0 to SpeakerName("Alice", "user", ""), 1 to SpeakerName("Robert", "user", "")),
            summary = "• seeded summary", actionItems = "- seeded action",
            title = SessionFixture.TITLE, notes = null, asrModelId = "x-asr", asrBackend = "x-asr", llmModelId = "gemma",
        )
        val e2 = SessionLibrary.byId(ctx, entry.id)!!
        assertTrue(LibraryIndexer.index(ctx, e2))
        val idx2 = requireNotNull(SessionIndex.read(e2.dir))
        val chunksAfter = idx2.vectors.filter { it.kind == SessionIndex.Kind.CHUNK }.associate { it.a to it.textHash }
        val bobChunks = idx2.vectors.filter { it.kind == SessionIndex.Kind.CHUNK }.map { it.a }.filter { a ->
            (a..idx2.vectors.first { it.kind == SessionIndex.Kind.CHUNK && it.a == a }.b).any { SessionFixture.SEEDED[it].speaker == 1 }
        }.toSet()
        for ((a, h) in chunksAfter) {
            if (a in bobChunks) assertTrue("chunk $a re-embedded", chunksBefore[a] != h)
            else assertEquals("chunk $a reused", chunksBefore[a], h)
        }

        // Corruption → absent; a stale tmp file is ignored and the real file still reads.
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull("truncated index reads as absent", SessionIndex.read(e2.dir))
        f.writeBytes(bytes)
        File(e2.dir, SessionIndex.FILE_NAME + ".tmp").writeBytes(ByteArray(10))
        assertNotNull(SessionIndex.read(e2.dir))
        assertFalse("still current despite the tmp file", LibraryIndexer.index(ctx, e2))
        assertNotNull(SessionIndex.read(e2.dir))
        EmbedHost.unload()
        assertEquals("handles released", 0, Embedder.live.get())
    }

    /** Hybrid search over the seeded meeting: lexical finds the exact line; the dense pass finds it by meaning, with
     *  the "≈" marker on a passage the lexical pass did not match. */
    @Test fun hybridSearch() = runBlocking<Unit> {
        assumeModel()
        SessionFixture.clear()
        val entry = SessionFixture.seed()
        assertTrue(LibraryIndexer.index(ctx, entry))
        val entries = SessionLibrary.list(ctx)
        LibrarySearch.refresh(ctx, entries)
        val lex = LibrarySearch.lexical(ctx, entries, "seeded summary")
        assertEquals(1, lex.size)
        assertTrue(lex[0].passages.none { it.semanticOnly })
        val qv = requireNotNull(EmbedHost.use(ctx, 1) { it.embed(Embedder.Prompts.query("the reply that was seeded")) })
        val dense = LibrarySearch.dense(ctx, entries, qv)
        assertTrue("dense finds the meeting: $dense", dense.any { it.entry.id == entry.id })
        val fused = LibrarySearch.fuse(LibrarySearch.lexical(ctx, entries, "zzz-no-such-term"), dense)
        assertTrue("semantic-only passages carry the marker", fused.first().passages.all { it.semanticOnly })
        val both = LibrarySearch.fuse(lex, dense)
        assertEquals(entry.id, both.first().entry.id)
    }

    /** T3: dense pass over 1000 meetings × 30 vectors stays well under a frame budget once the vectors are cached. */
    @Test fun denseLatencyOver1000Meetings() = runBlocking<Unit> {
        val root = File(ctx.cacheDir, "embed-latency").apply { deleteRecursively(); mkdirs() }
        val rnd = java.util.Random(7)
        val entries = (0 until 1000).map { i ->
            val d = File(root, "m$i").apply { mkdirs() }
            val vecs = (0 until 30).map { j ->
                SessionIndex.Vector(SessionIndex.Kind.CHUNK, j, j, j.toLong(), ByteArray(SessionIndex.DIM) { (rnd.nextInt(255) - 127).toByte() })
            }
            SessionIndex.write(d, SessionIndex.Index(spec.id, ByteArray(32), vecs))
            SessionLibrary.Entry("m$i", d, "m$i", 0L, 60, SessionLibrary.Status.DONE)
        }
        val q = FloatArray(SessionIndex.DIM) { rnd.nextFloat() - 0.5f }.let { v -> val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(v.size) { v[it] / n } }
        LibrarySearch.dense(ctx, entries, q)   // cold: reads 1000 files
        val t0 = System.nanoTime()
        LibrarySearch.dense(ctx, entries, q)
        val ms = (System.nanoTime() - t0) / 1e6
        android.util.Log.i("EmbedDeviceTest", "dense over 1000×30 vectors: %.1f ms".format(ms))
        assertTrue("dense pass %.0f ms".format(ms), ms < 300)
        root.deleteRecursively()
    }

    /** T4: the shared embedder unloads after use and leaves no native handle behind. */
    @Test fun hostUnloadsCleanly() = runBlocking<Unit> {
        assumeModel()
        val v = EmbedHost.use(ctx, 1) { it.embed(Embedder.Prompts.query("hello")) }
        assertNotNull(v); assertEquals(SessionIndex.DIM, v!!.size)
        assertTrue(EmbedHost.loaded)
        EmbedHost.unload()
        assertTrue(!EmbedHost.loaded)
        assertEquals(0, Embedder.live.get())
    }
}
