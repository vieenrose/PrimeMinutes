package studio.primeminutes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.power.BackgroundReliability
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import studio.primeminutes.core.update.UpdateChecker
import studio.primeminutes.core.update.UpdateInfo
import studio.primeminutes.ui.theme.LocalThemeController
import studio.primeminutes.ui.theme.LocalLanguageController
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.primeMinutesSliderColors
import studio.primeminutes.ui.theme.primeMinutesSwitchColors

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
) {
    val pal = LocalPrimeMinutesPalette.current
    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
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

/** App-wide text size, 85–150 % of the system font size, in 5 % steps; applies live. */
@Composable
private fun FontScaleSlider(enabled: Boolean) {
    val pal = LocalPrimeMinutesPalette.current
    val font = studio.primeminutes.ui.theme.LocalFontScaleController.current
    val min = studio.primeminutes.core.config.ThemeStore.FONT_MIN
    val max = studio.primeminutes.core.config.ThemeStore.FONT_MAX
    // Apply on release, not per drag step: every change re-lays out the whole app under the thumb.
    var draft by remember(font.scale) { mutableFloatStateOf(font.scale) }
    Column(Modifier.padding(top = 8.dp)) {
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
    val current = LlmRegistry.byId(selected).id
    val options = listOf(
        LlmRegistry.E2B_MOBILE_ID to R.string.reader_model_e2b,
        LlmRegistry.E4B_MOBILE_ID to R.string.reader_model_e4b,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, labelRes) ->
            FilterChip(
                selected = current == id,
                enabled = enabled && (id != LlmRegistry.E4B_MOBILE_ID || bigRam),
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
            (LlmRegistry.byId(LlmRegistry.E2B_MOBILE_ID).totalBytes / 1_000_000).toInt(),
            (LlmRegistry.byId(LlmRegistry.E4B_MOBILE_ID).totalBytes / 1_000_000).toInt(),
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
        val mb = LlmRegistry.byId(llmId).totalBytes / 1_000_000
        Text(
            stringResource(R.string.storage_model_pending, mb),
            style = MaterialTheme.typography.bodySmall, color = pal.Slate400,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    if (models.isEmpty()) {
        Text(stringResource(R.string.storage_none), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
        return
    }
    val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(context, b) }
    Text(
        stringResource(R.string.storage_total, fmt(models.sumOf { it.bytes })),
        style = MaterialTheme.typography.bodySmall, color = pal.Slate400,
        modifier = Modifier.padding(bottom = 4.dp),
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
                }) { Text(stringResource(R.string.action_delete)) }
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
        LlmRegistry.E4B_MOBILE_ID -> return stringResource(R.string.reader_model_e4b)
    }
    return name.removeSuffix("-gguf")
}

@Composable
private fun kindLabel(kind: ModelManager.ModelKind): String = stringResource(
    when (kind) {
        ModelManager.ModelKind.ASR -> R.string.model_kind_asr
        ModelManager.ModelKind.LLM -> R.string.model_kind_llm
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
            modifier = Modifier.padding(top = 4.dp),
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
            modifier = Modifier.padding(top = 4.dp),
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
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            scope.launch {
                checkState = context.getString(R.string.update_checking)
                runCatching { UpdateChecker.checkNow(context) }
                    .onSuccess { info ->
                        if (info != null) { checkState = null; onUpdateFound(info) }
                        else checkState = context.getString(R.string.update_up_to_date)
                    }
                    .onFailure { checkState = context.getString(R.string.update_check_failed) }
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
    Column(Modifier.padding(top = 4.dp)) {
        COMPONENT_LICENSES.forEach { (nameRes, license) ->
            val name = stringResource(nameRes)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(name, style = MaterialTheme.typography.bodySmall,
                    color = pal.Slate200, modifier = Modifier.weight(1f))
                Text(license, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "github.com/vieenrose/PrimeMinutes",
        style = MaterialTheme.typography.bodySmall,
        color = pal.Sky,
        modifier = Modifier
            .clickable { uriHandler.openUri("https://github.com/vieenrose/PrimeMinutes") }
            .padding(vertical = 4.dp),
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.InsetSurface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = pal.Sky, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** Title only, for the hidden backend picker inside the Inference card. */
@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = LocalPrimeMinutesPalette.current.Sky,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = pal.Slate200)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Row(
        // The whole row toggles: tapping the label did nothing, only the small switch did.
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = pal.Slate200,
            modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = primeMinutesSwitchColors(),
            // Name the switch after its row. A bare Row contributes no semantics node, so the label
            // and the switch land as unrelated siblings of the scrolling Column: TalkBack announced
            // an unlabelled "switch", and nothing could tell one row's switch from another's.
            // (Merging the Row does NOT fix it — a Switch is itself a merging node, so it stays
            // separate instead of folding into the row.)
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
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
    Column(Modifier.padding(vertical = 4.dp)) {
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
            scope.launch {
                studio.primeminutes.core.hw.HwInfo.setMode(ctx, studio.primeminutes.core.hw.ThreadMode.AUTO)
                profile = runCatching { studio.primeminutes.core.hw.HwInfo.ensureBench(ctx, force = true) }.getOrDefault(profile)
                running = false
            }
        },
    ) { Text(stringResource(if (running) R.string.settings_inference_running else R.string.settings_inference_run)) }
    Text(
        stringResource(R.string.settings_inference_hint),
        style = MaterialTheme.typography.labelSmall,
        color = pal.Slate400,
    )
}
