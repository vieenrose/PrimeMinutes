package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** One size format everywhere (Settings showed "3272 MB" and "5,5 GB" side by side). */
class SizeFormatTest {
    @Test fun unitsAndPrecision() {
        assertEquals("2.2 GB", SizeFormat.bytes(2_211_420_000))
        assertEquals("3.3 GB", SizeFormat.bytes(3_272_600_000))
        assertEquals("1.0 GB", SizeFormat.bytes(1_000_000_000))
        assertEquals("270 MB", SizeFormat.bytes(270_400_000))
        assertEquals("1 MB", SizeFormat.bytes(1_000_000))
        assertEquals("2 KB", SizeFormat.bytes(1_686))
        assertEquals("0 MB", SizeFormat.bytes(0))
    }

    @Test fun localeNeverChangesTheDecimalMark() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("5.5 GB", SizeFormat.bytes(5_500_000_000))
        } finally { Locale.setDefault(before) }
    }

    @Test fun registrySizesReadTheSameEverywhere() {
        assertEquals("2.2 GB", SizeFormat.bytes(LlmRegistry.byId(LlmRegistry.E2B_MOBILE_ID).totalBytes))
        assertEquals("3.3 GB", SizeFormat.bytes(LlmRegistry.byId(LlmRegistry.E4B_MOBILE_ID).totalBytes))
    }
}
