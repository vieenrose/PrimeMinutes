package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.models.DownloadPlan.Action
import studio.primeminutes.core.models.DownloadPlan.Consent

/** The first-use download prompt: asked before any byte unless the user agreed for this network. */
class DownloadPlanTest {
    private val both = DownloadPlan.pending(false, 270_000_000, false, 2_211_000_000)
    private val none = DownloadPlan.pending(true, 270_000_000, true, 2_211_000_000)

    @Test fun pendingCountsOnlyWhatIsMissing() {
        assertEquals(2_481_000_000, both.total)
        assertEquals(2_211_000_000, DownloadPlan.pending(true, 270_000_000, false, 2_211_000_000).total)
        assertFalse(none.any)
    }

    @Test fun nothingToDownloadNeverAsks() {
        Consent.values().forEach { c -> listOf(true, false).forEach { w -> assertEquals(Action.PROCEED, DownloadPlan.decide(c, w, none)) } }
    }

    @Test fun unsetAlwaysAsks() {
        assertEquals(Action.ASK, DownloadPlan.decide(Consent.UNSET, true, both))
        assertEquals(Action.ASK, DownloadPlan.decide(Consent.UNSET, false, both))
    }

    @Test fun wifiOnlyAsksOnMobileData() {
        assertEquals(Action.PROCEED, DownloadPlan.decide(Consent.WIFI_ONLY, true, both))
        assertEquals(Action.ASK, DownloadPlan.decide(Consent.WIFI_ONLY, false, both))
    }

    @Test fun anyNetworkProceeds() {
        assertEquals(Action.PROCEED, DownloadPlan.decide(Consent.ANY_NETWORK, false, both))
    }

    @Test fun prefetchOnlyWithConsentForThisNetwork() {
        assertFalse(DownloadPlan.mayPrefetch(Consent.UNSET, true, both))
        assertFalse(DownloadPlan.mayPrefetch(Consent.WIFI_ONLY, false, both))
        assertTrue(DownloadPlan.mayPrefetch(Consent.WIFI_ONLY, true, both))
        assertTrue(DownloadPlan.mayPrefetch(Consent.ANY_NETWORK, false, both))
        assertFalse(DownloadPlan.mayPrefetch(Consent.ANY_NETWORK, true, none))
    }
}
