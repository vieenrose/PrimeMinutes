package studio.primeminutes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.primeminutes.core.config.SummaryScript
import studio.primeminutes.core.text.ScreenScript

/** The app's Han script for People, Ask and the channel page; null (= stored text) until its dictionaries load. */
val LocalScreenScript = staticCompositionLocalOf<ScreenScript?> { null }

/** The [ScreenScript] of the Settings script id, loaded off the main thread. */
@Composable
fun rememberScreenScript(summaryScript: String?): ScreenScript? {
    val context = LocalContext.current
    val v by produceState<ScreenScript?>(null, summaryScript) {
        value = withContext(Dispatchers.IO) {
            runCatching { ScreenScript.of(context, SummaryScript.scriptFor(summaryScript, context)) }.getOrNull()
        }
    }
    return v
}

/** Generated / published text (titles, answers, channel and episode names) in the screen's script. */
@Composable fun shownGen(t: String): String = LocalScreenScript.current?.gen?.invoke(t) ?: t
/** What was said (Ask excerpts) in the screen's script. */
@Composable fun shownTr(t: String): String = LocalScreenScript.current?.tr?.invoke(t) ?: t
