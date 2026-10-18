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

    /** What a first-launch download needs: the parts still missing, in bytes. [readerUpgrade]: the
     *  missing AI notes model replaces one that is installed and keeps working meanwhile (a model
     *  migration), so nothing waits for it. */
    data class Pending(val speechBytes: Long, val readerBytes: Long, val readerUpgrade: Boolean = false, val speechUpgrade: Boolean = false) {
        val total: Long get() = speechBytes + readerBytes
        val any: Boolean get() = total > 0L
        /** What a recording or an import has to wait for. */
        val blocking: Long get() = (if (speechUpgrade) 0L else speechBytes) + if (readerUpgrade) 0L else readerBytes
    }

    /** [speechStandIn]: the chosen recognizer is missing but another one is installed and runs meanwhile (a latency
     *  switch, or the move from the pre-variant 480 ms model): its download blocks nothing. */
    fun pending(speechReady: Boolean, speechBytes: Long, readerReady: Boolean, readerBytes: Long, replacedReady: Boolean = false,
                speechStandIn: Boolean = false) =
        Pending(if (speechReady) 0L else speechBytes, if (readerReady) 0L else readerBytes, !readerReady && replacedReady,
            !speechReady && speechStandIn)

    /** Before a recording or an import: ask only for what it cannot run without. */
    fun decide(consent: Consent, unmetered: Boolean, pending: Pending): Action = when {
        pending.blocking <= 0L -> Action.PROCEED
        consent == Consent.ANY_NETWORK -> Action.PROCEED
        consent == Consent.WIFI_ONLY && unmetered -> Action.PROCEED
        else -> Action.ASK
    }

    /** A background prefetch may run: consent given for the current network. A model upgrade alone
     *  (the old model still works) waits for Wi-Fi whatever the consent: it is GBs nobody waits for. */
    fun mayPrefetch(consent: Consent, unmetered: Boolean, pending: Pending): Boolean =
        pending.any && (consent == Consent.ANY_NETWORK || consent == Consent.WIFI_ONLY && unmetered) &&
            (unmetered || pending.blocking > 0L)
}
