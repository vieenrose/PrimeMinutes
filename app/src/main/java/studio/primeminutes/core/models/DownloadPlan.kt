package studio.primeminutes.core.models

/**
 * Whether the first-use model downloads (speech engine, AI notes model) may start now — pure, so it
 * is unit-tested. The user is told the total size before any byte is fetched and chooses: download
 * now (any network), only on Wi-Fi (an unmetered network), or later (asked again at the next
 * recording or import that needs the models).
 */
object DownloadPlan {
    enum class Consent { UNSET, ANY_NETWORK, WIFI_ONLY }

    enum class Action {
        /** Nothing to download, or the user already agreed for this kind of network. */
        PROCEED,
        /** Show the size (and the mobile-data warning when metered) and let the user choose. */
        ASK,
    }

    /** What a first-launch download needs: the parts still missing, in bytes. */
    data class Pending(val speechBytes: Long, val readerBytes: Long) {
        val total: Long get() = speechBytes + readerBytes
        val any: Boolean get() = total > 0L
    }

    fun pending(speechReady: Boolean, speechBytes: Long, readerReady: Boolean, readerBytes: Long) =
        Pending(if (speechReady) 0L else speechBytes, if (readerReady) 0L else readerBytes)

    fun decide(consent: Consent, unmetered: Boolean, pending: Pending): Action = when {
        !pending.any -> Action.PROCEED
        consent == Consent.ANY_NETWORK -> Action.PROCEED
        consent == Consent.WIFI_ONLY && unmetered -> Action.PROCEED
        else -> Action.ASK
    }

    /** A background prefetch may run: consent given for the current network. */
    fun mayPrefetch(consent: Consent, unmetered: Boolean, pending: Pending): Boolean =
        pending.any && (consent == Consent.ANY_NETWORK || consent == Consent.WIFI_ONLY && unmetered)
}
