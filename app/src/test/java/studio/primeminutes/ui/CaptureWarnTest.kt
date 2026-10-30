package studio.primeminutes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.primeminutes.core.hw.HwSample

class CaptureWarnTest {
    private val ok = HwSample(cpuPct = 30, ramPct = 50, batteryPct = 80, charging = false, tempC = 30)

    @Test fun allFine() = assertNull(captureWarn(ok))

    @Test fun lowStorage() {
        assertEquals(CaptureWarn.STORAGE, captureWarn(ok, freeBytes = 499L * 1024 * 1024))
        assertNull(captureWarn(ok, freeBytes = 501L * 1024 * 1024))
    }

    @Test fun micLostOutranksEverything() =
        assertEquals(CaptureWarn.MIC, captureWarn(ok.copy(thermalSevere = true), freeBytes = 0, micLost = true))

    @Test fun storageOutranksHeat() =
        assertEquals(CaptureWarn.STORAGE, captureWarn(ok.copy(thermalSevere = true), freeBytes = 10))
}
