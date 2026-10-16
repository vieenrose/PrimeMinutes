package studio.primeminutes.core.models

import java.util.Locale

/**
 * The one way the app writes a download or storage size. Decimal units (what a phone's storage and
 * a carrier's data plan count in), always with a dot and the same precision, whatever the locale:
 * Settings showed "3272 MB" in one place and "5,5 GB" in another for the same kind of number.
 * Sizes come from the model registry ([LlmSpec.totalBytes], [ModelManager.asrDownloadBytes]), never
 * from hand-written figures in the strings.
 */
object SizeFormat {
    private const val KB = 1_000L
    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L

    /** "2.2 GB", "270 MB", "4 KB" (one decimal from 1 GB on; whole MB and KB below, rounded half up). */
    fun bytes(n: Long): String = when {
        n >= GB -> String.format(Locale.ROOT, "%.1f GB", n.toDouble() / GB)
        n >= MB -> "${(n + MB / 2) / MB} MB"
        n > 0L -> "${maxOf(1L, (n + KB / 2) / KB)} KB"
        else -> "0 MB"
    }
}
