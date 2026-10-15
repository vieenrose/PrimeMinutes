package studio.primeminutes.core.hw

import android.content.ComponentCallbacks2
import studio.primeminutes.core.ask.AskEngine
import studio.primeminutes.core.embed.EmbedHost

/**
 * What the app gives back when Android asks (onTrimMemory, from the activity and the service).
 * Only the models that sit idle between uses: the search embedder (reloads in under a second) as
 * soon as the UI is hidden or memory runs low, and the Ask reader (several seconds to reload)
 * only when the process is about to be killed for it. The pipeline's engines are never touched —
 * a running job needs them, and they are freed when it ends.
 */
object MemoryPressure {
    @Suppress("DEPRECATION")   // RUNNING_* are still delivered below API 34
    fun onTrim(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) EmbedHost.unloadAsync()
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) AskEngine.trim()
    }
}
