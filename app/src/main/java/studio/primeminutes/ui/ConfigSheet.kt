package studio.primeminutes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading

/**
 * Settings — a full-screen page (large collapsing top bar, back arrow) hosting [SettingsContent],
 * opened by the header gear. It used to be a bottom sheet: a long preferences page is a destination.
 * Download-readiness is probed once off the main thread (cheap File.exists checks) so the
 * model rows can show a downloaded vs will-download badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheet(
    config: TranscriptionConfig,
    enabled: Boolean,
    onChange: (TranscriptionConfig) -> Unit,
    onDismiss: () -> Unit,
    onUpdateFound: (studio.primeminutes.core.update.UpdateInfo) -> Unit = {},
    onOpenPeople: (() -> Unit)? = null,
) {
    val pal = LocalPrimeMinutesPalette.current
    val context = LocalContext.current
    var readyLlm by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        val res = withContext(Dispatchers.IO) {
            val m = ModelManager(context)
            LlmRegistry.ALL.filter { runCatching { m.llmReady(it) }.getOrDefault(false) }.map { it.id }.toSet()
        }
        readyLlm = res
    }

    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val barScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(barScroll.nestedScrollConnection).testTag("settings-screen"),
        containerColor = pal.Slate800,   // the cards (InsetSurface) were drawn for this surface
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pal.Slate800, scrolledContainerColor = pal.Slate800,
                    titleContentColor = pal.Slate200, navigationIconContentColor = pal.Slate200,
                ),
                scrollBehavior = barScroll,
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
        ) {
            SettingsContent(config, readyLlm, enabled, onChange, onUpdateFound, onOpenPeople)
        }
    }
}
