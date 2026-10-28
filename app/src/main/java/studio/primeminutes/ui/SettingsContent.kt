package studio.primeminutes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.platform.testTag
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.embed.EmbedHost
import studio.primeminutes.core.embed.EmbedRegistry
import studio.primeminutes.core.embed.LibraryIndexer
import androidx.compose.runtime.collectAsState
import studio.primeminutes.core.power.BackgroundReliability
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.BuildConfig
import studio.primeminutes.R
import studio.primeminutes.core.config.AppLanguage
import studio.primeminutes.core.config.SummaryScript
import studio.primeminutes.core.config.ThemeMode
import studio.primeminutes.core.config.TranscriptionConfig
import kotlin.math.roundToInt
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.SizeFormat
import studio.primeminutes.core.update.UpdateChecker
import studio.primeminutes.core.update.UpdateInfo
import studio.primeminutes.ui.theme.LocalThemeController
import studio.primeminutes.ui.theme.LocalLanguageController
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.PrimeMinutesPalette
import studio.primeminutes.ui.theme.primeMinutesSliderColors
import studio.primeminutes.ui.theme.primeMinutesSwitchColors
import studio.primeminutes.ui.theme.Spacing

/**
 * Settings: appearance, Chinese script, the live speaker delay, stored models (with the summary
 * model's download state — there is only one, so no picker), background reliability and About.
 * [readyLlm] carries which summary models are on disk. Edits report via [onChange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    config: TranscriptionConfig,
    readyLlm: Set<String>,
    enabled: Boolean = true,
    onChange: (TranscriptionConfig) -> Unit,
    onUpdateFound: (UpdateInfo) -> Unit = {},
    onOpenPeople: (() -> Unit)? = null,
) {
    val pal = LocalPrimeMinutesPalette.current
    Column(Modifier.padding(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // The first-use model download in progress, with Cancel: Settings covers the library while it runs.
        val prefetch by studio.primeminutes.core.models.ModelPrefetch.status.collectAsState()
        prefetch?.let { st -> studio.primeminutes.ui.components.ModelDownloadCard(st, onCancel = { studio.primeminutes.core.models.ModelPrefetch.cancel() }) }
        // Everyday choices first (look, language, the notes model), tuning and housekeeping after.
        Section(stringResource(R.string.settings_appearance)) {
        AppearanceSelector(enabled)
        FontScaleSlider(enabled)
        var hwOn by remember { mutableStateOf(studio.primeminutes.core.config.ThemeStore.loadHwMonitor(ctx)) }
        SwitchRow(stringResource(R.string.settings_hw_monitor), hwOn, enabled) {
            hwOn = it; studio.primeminutes.core.config.ThemeStore.saveHwMonitor(ctx, it)
        }
        Text(
            stringResource(R.string.settings_hw_monitor_hint),
            style = MaterialTheme.typography.labelSmall,
            color = pal.Slate400,
        )
        }

        Section(stringResource(R.string.settings_language)) {
        val lang = LocalLanguageController.current
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                AppLanguage.AUTO to stringResource(R.string.lang_auto),
                AppLanguage.EN to "English",
                AppLanguage.ZH_TW to "繁體中文",
                AppLanguage.ZH_CN to "简体中文",
            ).forEach { (code, label) ->
                FilterChip(
                    selected = lang.code == code,
                    enabled = enabled,
                    onClick = {
                        lang.set(code)
                        val script = AppLanguage.scriptFor(code, SummaryScript.fromId(config.summaryScript))
                        if (script.id != config.summaryScript) onChange(config.copy(summaryScript = script.id))
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                        selectedLabelColor = pal.Sky,
                        labelColor = pal.Slate400,
                    ),
                )
            }
        }
        Text(
            stringResource(R.string.settings_language_hint),
            style = MaterialTheme.typography.labelSmall,
            color = pal.Slate400,
            modifier = Modifier.padding(top = 2.dp),
        )
        }

        // The people directory (names shared by all meetings; voice recognition lives there too).
        if (onOpenPeople != null) Section(stringResource(R.string.people_title)) {
            val people by studio.primeminutes.core.people.PeopleStore.doc.collectAsState()
            androidx.compose.material3.ListItem(
                headlineContent = { Text(stringResource(R.string.people_settings_open, people.people.size)) },
                supportingContent = { Text(stringResource(R.string.people_settings_desc)) },
                leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                colors = settingsListItemColors(),
                modifier = Modifier.bleed().fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onOpenPeople)
                    .testTag("settings-people"),
            )
        }

        Section(stringResource(R.string.settings_reader_model)) {
        ReaderModelSelector(config.llmModelId, enabled) { onChange(config.copy(llmModelId = it)) }
        }

        Section(stringResource(R.string.settings_recording)) {
        SliderRow(
            stringResource(R.string.settings_speaker_delay),
            config.speakerDelaySec.toFloat(),
            TranscriptionConfig.SPEAKER_DELAY_MIN.toFloat(), TranscriptionConfig.SPEAKER_DELAY_MAX.toFloat(),
            enabled,
            steps = 4,
            format = { stringResource(R.string.settings_seconds, it.roundToInt()) },
        ) { onChange(config.copy(speakerDelaySec = it.roundToInt())) }
        Text(
            stringResource(R.string.settings_speaker_delay_hint),
            style = MaterialTheme.typography.bodySmall,
            color = pal.Slate400,
        )
        AsrLatencySlider(config.asrLatencyMs, enabled) { ms ->
            onChange(config.copy(asrLatencyMs = ms, asrModelId = studio.primeminutes.core.models.AsrRegistry.byLatency(ms).modelId))
        }
        }

        Section(stringResource(R.string.settings_inference)) {
            InferencePanel(enabled)
            if (BuildConfig.DEBUG) AiBenchmark(enabled)   // its 60 s clip ships in debug builds only
        }

        Section(stringResource(R.string.settings_experimental)) {
        SwitchRow(stringResource(R.string.settings_show_actions), config.showActionItems, enabled) {
            onChange(config.copy(showActionItems = it))
        }
        Text(
            stringResource(R.string.settings_show_actions_hint),
            style = MaterialTheme.typography.bodySmall,
            color = pal.Slate400,
        )
        }

        Section(stringResource(R.string.ask_title)) {
        SwitchRow(stringResource(R.string.settings_ask), config.askEnabled, enabled) {
            onChange(config.copy(askEnabled = it))
        }
        Text(
            stringResource(R.string.settings_ask_hint),
            style = MaterialTheme.typography.bodySmall,
            color = pal.Slate400,
        )
        }

        Section(stringResource(R.string.settings_storage)) {
        StoragePanel(enabled, summaryReady = LlmRegistry.byId(config.llmModelId).id in readyLlm, llmId = config.llmModelId)
        }

        Section(stringResource(R.string.settings_background)) {
            BackgroundReliabilityPanel(enabled)
        }

        Section(stringResource(R.string.settings_about)) {
            AboutContent(onUpdateFound)
        }
    }
}

/** Four-way theme picker (Auto / Light / Dark / E-ink) wired to [LocalThemeController]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSelector(enabled: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val theme = LocalThemeController.current
    val options = listOf(
        ThemeMode.AUTO to R.string.theme_auto,
        ThemeMode.LIGHT to R.string.theme_light,
        ThemeMode.DARK to R.string.theme_dark,
        ThemeMode.EINK to R.string.theme_eink,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (mode, labelRes) ->
            FilterChip(
                selected = theme.mode == mode,
                enabled = enabled,
                onClick = { theme.setMode(mode) },
                label = { Text(stringResource(labelRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                    selectedLabelColor = pal.Sky,
                    labelColor = pal.Slate400,
                ),
            )
        }
    }
    Text(
        stringResource(R.string.theme_eink_hint),
        style = MaterialTheme.typography.labelSmall,
        color = pal.Slate400,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** The speech recognizer's chunk (AsrRegistry): four stops from the most responsive to the most accurate. Saved on
 *  release; the chosen model downloads in the background while the installed one keeps transcribing. */
@Composable
private fun AsrLatencySlider(latencyMs: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val stops = studio.primeminutes.core.models.AsrRegistry.LATENCIES
    var draft by remember(latencyMs) { mutableFloatStateOf(stops.indexOf(latencyMs).coerceAtLeast(0).toFloat()) }
    val hints = listOf(R.string.settings_asr_latency_fastest, R.string.settings_asr_latency_fast,
        R.string.settings_asr_latency_balanced, R.string.settings_asr_latency_accurate)
    Column(Modifier.padding(top = Spacing.sm).testTag("settings-asr-latency")) {
        SliderRow(
            stringResource(R.string.settings_asr_latency), draft, 0f, (stops.size - 1).toFloat(), enabled,
            steps = stops.size - 2,
            format = { i ->
                val k = i.roundToInt().coerceIn(0, stops.size - 1)
                stringResource(R.string.settings_asr_latency_value, "%.2f".format(stops[k] / 1000f), stringResource(hints[k]))
            },
            onChangeFinished = { stops[draft.roundToInt().coerceIn(0, stops.size - 1)].let { if (it != latencyMs) onSelect(it) } },
        ) { draft = it }
        Text(
            stringResource(R.string.settings_asr_latency_hint),
            style = MaterialTheme.typography.bodySmall,
            color = pal.Slate400,
        )
    }
}

/** App-wide text size, 85–150 % of the system font size, in 5 % steps; applies live. */
@Composable
private fun FontScaleSlider(enabled: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val font = studio.primeminutes.ui.theme.LocalFontScaleController.current
    val min = studio.primeminutes.core.config.ThemeStore.FONT_MIN
    val max = studio.primeminutes.core.config.ThemeStore.FONT_MAX
    // Apply on release, not per drag step: every change re-lays out the whole app under the thumb.
    var draft by remember(font.scale) { mutableFloatStateOf(font.scale) }
    Column(Modifier.padding(top = Spacing.sm)) {
        SliderRow(
            stringResource(R.string.settings_font_size), draft, min, max, enabled,
            steps = ((max - min) / 0.05f).roundToInt() - 1,
            format = { "${(it * 100).roundToInt()}%" },
            onChangeFinished = { font.set((draft * 20).roundToInt() / 20f) },
        ) { draft = it }
        Text(
            stringResource(R.string.settings_font_size_hint),
            style = MaterialTheme.typography.labelSmall,
            color = pal.Slate400,
        )
    }
}

/** The AI-notes model: E2B, or E4B on 8 GB phones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderModelSelector(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val bigRam = remember {
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        mi.totalMem >= LlmRegistry.E4B_MIN_RAM
    }
    // A replaced model's choice shows as its successor (E4B mobile-v1 -> mobile-v2): only those are offered.
    val current = LlmRegistry.current(selected).id
    val options = listOf(
        LlmRegistry.E2B_MOBILE_ID to R.string.reader_model_e2b,
        LlmRegistry.E4B_MOBILE_V2_ID to R.string.reader_model_e4b,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, labelRes) ->
            FilterChip(
                selected = current == id,
                enabled = enabled && (id !in LlmRegistry.E4B_IDS || bigRam),
                onClick = { onSelect(id) },
                label = { Text(stringResource(labelRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                    selectedLabelColor = pal.Sky,
                    labelColor = pal.Slate400,
                ),
            )
        }
    }
    Text(
        stringResource(
            R.string.reader_model_hint,
            SizeFormat.bytes(LlmRegistry.byId(LlmRegistry.E2B_MOBILE_ID).totalBytes),
            SizeFormat.bytes(LlmRegistry.byId(LlmRegistry.E4B_MOBILE_V2_ID).totalBytes),
        ) + if (bigRam) "" else "\n" + stringResource(R.string.reader_model_e4b_ram),
        style = MaterialTheme.typography.bodySmall,
        color = pal.Slate400,
    )
}

/** Lists downloaded models with sizes and a per-item delete (re-downloads on next use). */
@Composable
private fun StoragePanel(enabled: Boolean, summaryReady: Boolean, llmId: String) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelManager.StoredModel>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) {
        models = withContext(Dispatchers.IO) { ModelManager(context).storedModels() }
    }
    if (!summaryReady) {
        // The one summary model is fetched on first use — say so where its size would be listed.
        val mb = SizeFormat.bytes(LlmRegistry.byId(llmId).totalBytes)
        Text(
            stringResource(R.string.storage_model_pending, mb),
            style = MaterialTheme.typography.bodySmall, color = pal.Slate400,
            modifier = Modifier.padding(bottom = Spacing.xs),
        )
    }
    SearchModelPanel(enabled, onChanged = { version++ })
    if (models.isEmpty()) {
        Text(stringResource(R.string.storage_none), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
        return
    }
    val fmt = { b: Long -> SizeFormat.bytes(b) }
    Text(
        stringResource(R.string.storage_total, fmt(models.sumOf { it.bytes })),
        style = MaterialTheme.typography.bodySmall, color = pal.Slate400,
        modifier = Modifier.padding(bottom = Spacing.xs),
    )
    // One tap used to delete a model outright — 3.3 GB to download again for the summary model.
    var confirm by remember { mutableStateOf<ModelManager.StoredModel?>(null) }
    confirm?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.storage_delete_title)) },
            text = { Text(stringResource(R.string.storage_delete_body, kindLabel(m.kind), fmt(m.bytes))) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = null
                    scope.launch { withContext(Dispatchers.IO) { m.delete() }; version++ }
                }) { Text(stringResource(R.string.action_delete), color = LocalPrimeMinutesPalette.current.WarningText) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    models.forEach { m ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kindLabel(m.kind), style = MaterialTheme.typography.bodyMedium, color = pal.Slate200)
                Text("${prettyModelName(m.name)} · ${fmt(m.bytes)}", style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
            }
            IconButton(
                enabled = enabled,
                onClick = { confirm = m },
            ) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.storage_delete), tint = pal.Slate400)
            }
        }
    }
}

/** Semantic search model: download it here, or index the meetings recorded before it arrived.
 *  The indexer itself runs in the background (never during a recording or a transcription). */
@Composable
private fun SearchModelPanel(enabled: Boolean, onChanged: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(EmbedHost.ready(context)) }
    var download by remember { mutableStateOf<Float?>(null) }
    var stale by remember { mutableIntStateOf(-1) }
    val pending by LibraryIndexer.pending.collectAsState()
    val indexing by LibraryIndexer.indexing.collectAsState()
    LaunchedEffect(ready, pending, indexing) {
        if (ready) stale = withContext(Dispatchers.IO) { LibraryIndexer.staleIds(context).size }
    }
    val spec = EmbedRegistry.byId(EmbedRegistry.DEFAULT_ID)
    if (!ready) {
        val mb = SizeFormat.bytes(spec.totalBytes)
        val dl = download
        androidx.compose.material3.TextButton(
            enabled = enabled && dl == null,
            onClick = {
                download = 0f
                scope.launch {
                    val r = runCatching { ModelManager(context).ensureEmbed(spec) { f -> download = f } }
                    download = null
                    ready = EmbedHost.ready(context)
                    if (r.isSuccess) { LibraryIndexer.catchUp(context); onChanged() }
                }
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(
                if (dl == null) stringResource(R.string.settings_search_model_get, mb)
                else stringResource(R.string.search_semantic_downloading, (dl * 100).toInt()),
                color = pal.Sky,
            )
        }
        return
    }
    val busy = pending > 0 || indexing.isNotEmpty()
    if (stale == 0 && !busy) {
        Text(stringResource(R.string.settings_index_all_done), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.TextButton(
            enabled = enabled && !busy && stale > 0,
            onClick = { LibraryIndexer.catchUp(context) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text(stringResource(R.string.settings_index_old), color = pal.Sky) }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            if (busy) stringResource(R.string.studio_indexing) + " " + stringResource(R.string.settings_index_pending, pending + indexing.size)
            else if (stale > 0) stringResource(R.string.settings_index_pending, stale) else "",
            style = MaterialTheme.typography.bodySmall, color = pal.Slate400,
        )
    }
}

/** Human-readable storage row: the raw XNNPACK compile-cache filename
 *  (`model.litertlm.xnnpack_cache_<epoch>_<bytes>`) wraps over two lines and reads
 *  as noise — show it as "<model> (compile cache)" instead. Other files keep their
 *  real names (they double as side-loading documentation). */
@Composable
private fun prettyModelName(name: String): String {
    val i = name.indexOf(".xnnpack_cache")
    if (i > 0) return stringResource(R.string.storage_compile_cache, name.substring(0, i))
    if (name == "nemo") return "X-ASR + Nemotron-3"
    // A reader model folder: its chip name (行動版 E2B / 行動版 E4B), not the folder.
    when (LlmRegistry.ALL.firstOrNull { it.dirName == name }?.id) {
        LlmRegistry.E2B_MOBILE_ID -> return stringResource(R.string.reader_model_e2b)
        LlmRegistry.E4B_MOBILE_ID -> return stringResource(R.string.reader_model_e4b_v1)
        LlmRegistry.E4B_MOBILE_V2_ID -> return stringResource(R.string.reader_model_e4b)
    }
    return name.removeSuffix("-gguf")
}

@Composable
private fun kindLabel(kind: ModelManager.ModelKind): String = stringResource(
    when (kind) {
        ModelManager.ModelKind.ASR -> R.string.model_kind_asr
        ModelManager.ModelKind.LLM -> R.string.model_kind_llm
        ModelManager.ModelKind.EMBED -> R.string.model_kind_embed
        ModelManager.ModelKind.OTHER -> R.string.model_kind_other
    }
)

/**
 * Battery-optimization exemption (portable, one tap) plus — on battery-aggressive OEMs — a deep-link
 * to the auto-start / auto-freeze screen the app can't toggle itself. Polls the exemption state so it
 * updates when the user returns from the system dialog. See [BackgroundReliability].
 */
@Composable
private fun BackgroundReliabilityPanel(enabled: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BackgroundReliability.isIgnoringBatteryOptimizations(context)) }
    // Cheap re-check while this panel is visible, so returning from the system dialog reflects here.
    LaunchedEffect(Unit) {
        while (true) {
            exempt = BackgroundReliability.isIgnoringBatteryOptimizations(context)
            delay(1500)
        }
    }
    Text(
        stringResource(R.string.bg_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = pal.Slate400,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Text(
        stringResource(if (exempt) R.string.bg_battery_exempt else R.string.bg_battery_optimized),
        style = MaterialTheme.typography.bodyMedium,
        color = if (exempt) pal.Sky else pal.Slate200,
    )
    if (!exempt) {
        OutlinedButton(
            enabled = enabled,
            onClick = { BackgroundReliability.requestIgnoreBatteryOptimizations(context) },
            modifier = Modifier.padding(top = Spacing.xs),
        ) { Text(stringResource(R.string.bg_allow)) }
    }
    if (BackgroundReliability.isAggressiveOem()) {
        Text(
            stringResource(R.string.bg_oem_hint, Build.MANUFACTURER.replaceFirstChar { it.uppercase() }),
            style = MaterialTheme.typography.bodySmall,
            color = pal.Slate400,
            modifier = Modifier.padding(top = 10.dp),
        )
        OutlinedButton(
            enabled = enabled,
            onClick = { BackgroundReliability.openOemAutoStartSettings(context) },
            modifier = Modifier.padding(top = Spacing.xs),
        ) { Text(stringResource(R.string.bg_open_oem)) }
    }
}

/** Version + GPL notice + a manual update check + the open-source components + repo link. */
@Composable
private fun AboutContent(onUpdateFound: (UpdateInfo) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkState by remember { mutableStateOf<String?>(null) }   // inline status next to the button
    Text(
        "Prime Minutes v${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodyMedium,
        color = pal.Slate200,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        stringResource(R.string.about_license),
        style = MaterialTheme.typography.bodySmall,
        color = pal.Slate400,
    )
    Spacer(Modifier.height(Spacing.sm))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            scope.launch {
                checkState = context.getString(R.string.update_checking)
                runCatching { UpdateChecker.checkNow(context) }
                    .onSuccess { info ->
                        if (info != null) { checkState = null; onUpdateFound(info) }
                        else checkState = context.getString(R.string.update_up_to_date)
                    }
                    .onFailure { e -> checkState = context.getString(R.string.update_check_failed) + (e.userMessage(context)?.let { " — $it" } ?: "") }
            }
        }) { Text(stringResource(R.string.update_check)) }
        checkState?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.about_components),
        style = MaterialTheme.typography.labelSmall,
        color = pal.Slate400,
    )
    Column(Modifier.padding(top = Spacing.xs)) {
        COMPONENT_LICENSES.forEach { (nameRes, license) ->
            val name = stringResource(nameRes)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(name, style = MaterialTheme.typography.bodySmall,
                    color = pal.Slate200, modifier = Modifier.weight(1f))
                Text(license, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
            }
        }
    }
    Spacer(Modifier.height(Spacing.sm))
    androidx.compose.material3.ListItem(
        headlineContent = { Text("github.com/vieenrose/PrimeMinutes") },
        leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
        colors = settingsListItemColors(headline = pal.Sky),
        modifier = Modifier.bleed().fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { uriHandler.openUri("https://github.com/vieenrose/PrimeMinutes") },
    )
}

private val COMPONENT_LICENSES = listOf(
    R.string.lic_nemo to "Apache-2.0",
    R.string.lic_crispasr to "MIT",
    R.string.lic_audiocpp to "Apache-2.0",
    R.string.lic_ggml to "Apache-2.0",
    R.string.lic_xasr to "Apache-2.0",
    R.string.lic_nemotron to "OpenMDW-1.1",
    R.string.lic_opencc to "Apache-2.0",
    R.string.lic_newpipe to "GPL-3.0",
    R.string.lic_compose to "Apache-2.0",
)

/** A titled card: one topic per card, so the page reads as a few groups rather than one long list. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(pal.InsetSurface).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Section title marked as a heading: TalkBack's heading swipe jumps card to card on a page
        // that is a dozen cards long (no heading was set anywhere in the app until now).
        Text(title, style = MaterialTheme.typography.titleSmall, color = pal.Sky, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        content()
    }
}

/** Title only, for the hidden backend picker inside the Inference card. */
@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = LocalPrimeMinutesPalette.current.Sky,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Spacing.sm).semantics { heading() })
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = pal.Slate200)
        content()
    }
}

/**
 * A [androidx.compose.material3.ListItem] keeps its own 16 dp side padding; inside a settings card
 * (already padded) that indented the rows past the card's other text. Bleed the row over the card
 * padding so the label lines up and the ripple spans the card.
 */
private fun Modifier.bleed(x: androidx.compose.ui.unit.Dp = 16.dp) = layout { m, c ->
    val px = x.roundToPx()
    val p = m.measure(c.copy(minWidth = c.minWidth + 2 * px, maxWidth = c.maxWidth + 2 * px))
    layout(p.width - 2 * px, p.height) { p.place(-px, 0) }
}

/** The settings rows are M3 list items on the card surface: transparent container, palette text. */
@Composable
private fun settingsListItemColors(headline: androidx.compose.ui.graphics.Color = LocalPrimeMinutesPalette.current.Slate200) =
    LocalPrimeMinutesPalette.current.let { pal ->
        androidx.compose.material3.ListItemDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            headlineColor = headline, supportingColor = pal.Slate400,
            leadingIconColor = pal.Slate400, trailingIconColor = pal.Slate400,
        )
    }

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.material3.ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        // The Switch has no callback of its own: the whole row is ONE toggleable node (role Switch),
        // so tapping the label toggles it and TalkBack reads "label, switch, on" as a single item.
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = primeMinutesSwitchColors()) },
        colors = settingsListItemColors(),
        modifier = Modifier.bleed().fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch) { on ->
            if (on) haptic.toggleOn() else haptic.toggleOff()
            onChange(on)
        },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(
    label: String, value: Float, from: Float, to: Float, enabled: Boolean,
    steps: Int = 0,
    format: @Composable (Float) -> String = { "%.2f".format(it) },
    onChangeFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Text(
            "$label: ${format(value)}",
            style = MaterialTheme.typography.bodyMedium,
            color = pal.Slate200,
            modifier = Modifier.wrapContentWidth(),
        )
        val colors = primeMinutesSliderColors()
        val interaction = remember { MutableInteractionSource() }
        Slider(value = value, onValueChange = onChange, valueRange = from..to, enabled = enabled, steps = steps,
            onValueChangeFinished = onChangeFinished, colors = colors, interactionSource = interaction,
            // A 48 dp touch target needs a real handle, not the default 4 dp sliver.
            thumb = { SliderDefaults.Thumb(interaction, colors = colors, enabled = enabled, thumbSize = DpSize(20.dp, 32.dp)) })
    }
}

/**
 * Times each AI component on this phone: the ASR and diarizer on a shipped 60 s meeting clip, and the
 * summarizer on a fixed prompt. The components run one after the other, so each is measured alone.
 */
@Composable
private fun AiBenchmark(enabled: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    Text(stringResource(R.string.settings_ai_bench_hint), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
    OutlinedButton(
        enabled = enabled && !running,
        onClick = {
            running = true; lines = emptyList()
            scope.launch {
                lines = runCatching {
                    val asr = studio.primeminutes.core.bench.ComponentBench.asr(ctx)
                    val sum = studio.primeminutes.core.bench.ComponentBench.summarizer(ctx)
                    listOf(
                        "ASR  RTF %.3f · %.1f s audio in %.1f s · first partial %.2f s · piece p95 %.0f ms"
                            .format(asr.rtf, asr.audioSec, asr.wallSec, asr.firstPartialSec, asr.pieceP95Ms),
                        "Diarizer  %.1f s compute (%.0f%% of wall) · %d speakers · %d turns"
                            .format(asr.diarSec, 100 * asr.diarSec / asr.wallSec, asr.speakers, asr.turns),
                        "ASR graph  %.1f s · load %.1f s · peak RSS %.0f MB"
                            .format(asr.asrSec, asr.loadSec, asr.peakRssMb),
                        "Summarizer  prefill %.0f tok/s (%d tok, %.1f s) · decode %.1f tok/s (%d tok, %.1f s)"
                            .format(sum.prefillTps, sum.prefilled, sum.prefillSec, sum.decodeTps, sum.generated, sum.decodeSec),
                    )
                }.getOrElse { listOf(ctx.getString(R.string.settings_ai_bench_failed, it.message ?: it.javaClass.simpleName)) }
                running = false
            }
        },
    ) { Text(stringResource(if (running) R.string.settings_ai_bench_running else R.string.settings_ai_bench_run)) }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = pal.Slate400) }
}

/** Detected hardware, the thread count the engines use, a manual override and the benchmark. */
@Composable
private fun InferencePanel(enabled: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var profile by remember { mutableStateOf(studio.primeminutes.core.hw.HwInfo.profile(ctx)) }
    var running by remember { mutableStateOf(false) }
    var benchFailed by remember { mutableStateOf(false) }
    // A slider from 2 to all the cores; moving it is a manual choice. "Run benchmark" goes back to Auto: it
    // measures every count, picks the best one and moves the slider there.
    var draft by remember(profile.threads) { mutableFloatStateOf(profile.threads.toFloat()) }
    if (profile.cores > 2) SliderRow(
        stringResource(R.string.settings_threads), draft, 2f, profile.cores.toFloat(), enabled,
        steps = profile.cores - 3,
        format = { "${it.roundToInt()}" },
        onChangeFinished = {
            val n = draft.roundToInt()
            studio.primeminutes.core.hw.HwInfo.setMode(ctx, studio.primeminutes.core.hw.ThreadMode.entries.first { it.fixed == n })
            profile = studio.primeminutes.core.hw.HwInfo.profile(ctx)
        },
    ) { draft = it }
    if (profile.capped) Text(stringResource(R.string.settings_inference_capped), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
    androidx.compose.material3.OutlinedButton(
        enabled = enabled && !running,
        onClick = {
            running = true
            // A failed run must not silently drop a manual choice to Auto: remember it, restore it.
            val priorMode = studio.primeminutes.core.hw.HwInfo.mode(ctx)
            scope.launch {
                studio.primeminutes.core.hw.HwInfo.setMode(ctx, studio.primeminutes.core.hw.ThreadMode.AUTO)
                runCatching { studio.primeminutes.core.hw.HwInfo.ensureBench(ctx, force = true) }
                    .onSuccess { profile = it; benchFailed = false }
                    .onFailure {
                        studio.primeminutes.core.hw.HwInfo.setMode(ctx, priorMode)
                        profile = studio.primeminutes.core.hw.HwInfo.profile(ctx)
                        draft = profile.threads.toFloat()
                        benchFailed = true
                    }
                running = false
            }
        },
    ) { Text(stringResource(if (running) R.string.settings_inference_running else R.string.settings_inference_run)) }
    if (benchFailed) Text(stringResource(R.string.settings_inference_failed), style = MaterialTheme.typography.bodySmall, color = PrimeMinutesPalette.Red)
    Text(
        stringResource(R.string.settings_inference_hint),
        style = MaterialTheme.typography.labelSmall,
        color = pal.Slate400,
    )
}
