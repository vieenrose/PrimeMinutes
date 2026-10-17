package studio.primeminutes.core.models

/**
 * A selectable on-device summarization model.
 *
 * The shape is [ModelManager]'s revision-pinned, multi-file artifact set (the same one the ASR
 * model sets use). A GGUF is a SINGLE self-contained file — weights, tokenizer and chat template
 * all live inside it — so a GGUF spec simply has one entry in [files] and leaves
 * [weightCacheFile] / [tokenizerFile] empty. The multi-file machinery is kept rather than
 * special-cased because it is what gives us per-file sha256 verification and resumable,
 * revision-pinned downloads for free.
 */
data class LlmSpec(
    val id: String,
    val displayName: String,
    /** ModelManager subdirectory holding this model's files. */
    val dirName: String,
    /** HF `resolve/<commit>` base URL — commit-pinned, never `main`. */
    val revision: String,
    /** relative path -> (sizeBytes, sha256). Downloaded and verified file by file. */
    val files: Map<String, Pair<Long, String>>,
    /** Relative path of the model inside [dirName]. */
    val mainFile: String,
    /** Relative path of a pre-packed weight cache, or "" — GGUF needs none. llama.cpp mmaps the
     *  file, so the weights are file-backed and evictable from the start; the ~800 MiB of
     *  UNRECLAIMABLE anonymous memory XNNPACK materialised (and the pre-packed cache built to
     *  avoid it) has no analogue here. */
    val weightCacheFile: String = "",
    /** Relative path of a separate tokenizer blob, or "" — a GGUF embeds its own. */
    val tokenizerFile: String = "",
    val shortName: String = "",
    /** The context the graph is loaded with, in tokens (the mobile graphs run at 4k). */
    val maxCtx: Int,
    /** The reader's system prompt, shipped next to the weights (they must stay paired). */
    val systemPromptFile: String = "",
    /** How to drive these weights (model_cards/<name>.json): chat template, sampler, budgets,
     *  the reader protocol and the title/prose/Ask prompts. */
    val card: ModelCard = ModelCard.DEFAULT,
    /** The model that replaces this one, or null. A replaced model is not offered any more; it stays
     *  listed so a phone that has it keeps reading with it until its successor is installed (then its
     *  files are deleted), and so meetings and notes it wrote keep their provenance. */
    val replacedBy: String? = null,
) {
    val totalBytes: Long get() = files.values.sumOf { it.first }
    val template: ChatTemplate get() = card.template
    val sampler: SamplerProfile get() = card.sampler
}

/** The reader's sampler (integration note §13.4: top-k 40, top-p 0.95, T 0.2 in the shipped card). */
data class SamplerProfile(val topK: Int, val topP: Float, val temp: Float)

/**
 * The on-device summarizer: the Gemma-4-E2B meeting agent (QAT, Q4_0, Apache-2.0), fine-tuned as a
 * live READING AGENT (github.com/vieenrose/meeting-summarizer): it reads a meeting window by window
 * and writes short, typed, cited notes; the minutes are those notes grouped by type
 * ([studio.primeminutes.core.reader]). The protocol lives in `system_prompt.txt`, shipped next to the
 * weights and used verbatim — the model was fine-tuned on it.
 *
 * Measured upstream (v11) on 38 held-out zh-TW meetings: coverage 0.89, 72 % of gold decisions
 * recalled, 71 % of listed decisions really decided, 17 % of statements contradicted by the transcript. Live on a Reno7 (Dimensity 900): 67 s median
 * lag after each ~4 min window.
 */
object LlmRegistry {
    const val E2B_MOBILE_ID = "gemma4-e2b-meeting-agent-zh-mobile-v1"
    /** E4B mobile-v1: replaced by [E4B_MOBILE_V2_ID] (2026-10-18), kept only to migrate phones that have it. */
    const val E4B_MOBILE_ID = "gemma4-e4b-meeting-agent-zh-mobile"
    /** E4B mobile-v2 (2026-10): fewer contradicted notes and better summaries than mobile-v1 E4B, which
     *  it replaces (docs/model-eval-mobile-v2.md); its card sets its note cap and prose budget. */
    const val E4B_MOBILE_V2_ID = "gemma4-e4b-meeting-agent-zh-mobile-v2"
    /** E2B mobile-v1 on the LiteRT engine; it replaced the llama.cpp v11 GGUF (2026-10-03). */
    const val DEFAULT_ID = E2B_MOBILE_ID

    val ALL: List<LlmSpec> = listOf(
        // The mobile graphs (integration note §12, §13): Google's Gemma-4 mobile weights carrying our
        // fine-tune, run by the forked LiteRT engine on the CPU at 4k (cpp/mfa). The same system
        // prompt as v11, byte for byte.
        mobile(
            id = E2B_MOBILE_ID, displayName = "Gemma-4-E2B meeting agent mobile-v1 (zh)", shortName = "E2B",
            dirName = "gemma4-meeting-agent-e2b-mobile-v1",
            repo = "Luigi/gemma-4-E2B-meeting-agent-zh-GGUF", rev = "f47086170552f9b8e8719e9884af9fb9b56156d1",
            dir = "mobile-v1/mfa", prompt = "mobile-v1/system_prompt.txt",
            sizes = listOf(
                103_811_720L to "280327ee5720663acd2268c2e5d42caad33f70ba7931eef8c8b3018b4e2a4980",
                1_284_518_392L to "dca1e5553b4159558b17073c94fcc7ff16646e99a56a0614728435ac4b571720",
                818_394_320L to "6a7555ccc349be490fca4ed63ebf7fdafd8e4a4510012f3ae9c38f8009b5fcae",
            ),
        ),
        mobile(
            id = E4B_MOBILE_ID, displayName = "Gemma-4-E4B meeting agent (zh)", shortName = "E4B",
            dirName = "gemma4-meeting-agent-e4b-mobile",
            repo = "Luigi/gemma-4-E4B-meeting-agent-zh-LiteRT", rev = "70e095dc5db8d4edac901578a6e0f04d994ad95e",
            dir = "mfa", prompt = "system_prompt.txt",
            sizes = listOf(
                170_920_584L to "94cf45ffd3d0dd7040d27b22e70845cf1054db90613bf31d8ffc1623d23d4a40",
                836_778_512L to "d35fe41db89fa0128f5da9530886a12581cee715369e1b9ed9a3644e43d16a6a",
                2_260_210_576L to "34858194f4f596fae132470a2e0f2f0f276e540c7af8502e4ef7cca99e871424",
            ),
            replacedBy = E4B_MOBILE_V2_ID,
        ),
        // mobile-v2 (integration note 05b0d33b §10): a new main graph and system prompt; the embedders and
        // tokenizer are byte-identical to mobile-v1 E4B (reused on device, not downloaded again).
        mobile(
            id = E4B_MOBILE_V2_ID, displayName = "Gemma-4-E4B meeting agent mobile-v2 (zh)", shortName = "E4B",
            dirName = "gemma4-meeting-agent-e4b-mobile-v2",
            repo = "Luigi/gemma-4-E4B-meeting-agent-zh-LiteRT", rev = "389bc2f944f1c5a4d74bc30348e32e13e931d987",
            dir = "mobile-v2/mfa", prompt = "mobile-v2/system_prompt.txt",
            sizes = listOf(
                170_920_584L to "94cf45ffd3d0dd7040d27b22e70845cf1054db90613bf31d8ffc1623d23d4a40",
                836_778_512L to "d35fe41db89fa0128f5da9530886a12581cee715369e1b9ed9a3644e43d16a6a",
                2_260_210_576L to "2d346d0e5f4c1a91ae3ab9df7cea2e29992f4d60a7c661ccbc886e964fe618d0",
            ),
            promptSha = MOBILE_V2_PROMPT_SHA,
            card = ModelCard.load("gemma4-meeting-agent-zh-v2"),
        ),
    )

    /** The large E4B models (offered on 8 GB phones only). */
    val E4B_IDS = setOf(E4B_MOBILE_ID, E4B_MOBILE_V2_ID)

    /** The models Settings offers: the ones nothing replaces. */
    val OFFERED: List<LlmSpec> get() = ALL.filter { it.replacedBy == null }

    /** The model a saved choice [id] stands for today: [id], or what replaced it (followed to the end). */
    fun current(id: String): LlmSpec {
        var s = byId(id)
        repeat(ALL.size) { s = s.replacedBy?.let(::byId) ?: return s }
        return s
    }

    /**
     * The model to run for the choice [id]: the [current] one when it is installed ([ready]); while it is
     * not (its download pending or running), an installed model it replaced, so a phone being migrated
     * keeps reading; else the current one (to be downloaded).
     */
    fun runnable(id: String, ready: (LlmSpec) -> Boolean): LlmSpec {
        val want = current(id)
        if (ready(want)) return want
        return ALL.firstOrNull { it.replacedBy != null && it.id != want.id && current(it.id).id == want.id && ready(it) } ?: want
    }

    /** Replaced models whose successor ([current]) passes [installed]: their files can go. */
    fun reclaimable(installed: (LlmSpec) -> Boolean): List<LlmSpec> =
        ALL.filter { it.replacedBy != null && current(it.id).let { c -> c.id != it.id && installed(c) } }

    private const val MOBILE_V1_PROMPT_SHA = "406040c70270b5b9d47a4222138fcf2177f361dcbfb79fba164ca2559e0ffbf3"
    private const val MOBILE_V2_PROMPT_SHA = "044150e8e18c05f807c88ca88358953f81fcc74fe85959184d66c65d81fd4843"

    /** E4B takes ~3 GB next to the ASR engine: offered on 8 GB phones only (totalMem reads ~7.3 GiB there). */
    const val E4B_MIN_RAM = 7L * 1024 * 1024 * 1024

    /** embedder, per-layer embedder and the fused graph, in that order; plus the shared tokenizer. */
    private fun mobile(
        id: String, displayName: String, shortName: String, dirName: String, repo: String, rev: String,
        dir: String, prompt: String, sizes: List<Pair<Long, String>>,
        promptSha: String = MOBILE_V1_PROMPT_SHA, card: ModelCard = ModelCard.DEFAULT, replacedBy: String? = null,
    ) = LlmSpec(
        id = id, displayName = displayName, shortName = shortName, dirName = dirName,
        revision = "https://huggingface.co/$repo/resolve/$rev",
        files = mapOf(
            "$dir/Section1_SP_Tokenizer.spiece" to (4_689_013L to "e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03"),
            "$dir/Section2_TFLiteModel_tf_lite_embedder.tflite" to sizes[0],
            "$dir/Section3_TFLiteModel_tf_lite_per_layer_embedder.tflite" to sizes[1],
            "$dir/prefill_decode_fused.tflite" to sizes[2],
            prompt to (1_686L to promptSha),
        ),
        mainFile = "$dir/prefill_decode_fused.tflite",
        tokenizerFile = "$dir/Section1_SP_Tokenizer.spiece",
        maxCtx = 4096,
        systemPromptFile = prompt,
        card = card,
        replacedBy = replacedBy,
    )

    fun byId(id: String): LlmSpec =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
