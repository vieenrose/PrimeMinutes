package studio.primeminutes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.asr.NemoStreamEngine
import studio.primeminutes.core.events.TranscriptEvent.Utterance
import studio.primeminutes.core.llm.TranscriptFormat
import studio.primeminutes.core.reader.ReaderProtocol
import studio.primeminutes.core.session.PrimeMinutesSession
import java.util.Locale

/**
 * Machine-read text (the reader's timestamps, the LRC export, the native segment encoding) must not
 * follow the default locale: a decimal comma (fr_FR, de_DE) or non-ASCII digits (ar_EG) broke it.
 */
class LocaleSafeFormatTest {
    private val saved = Locale.getDefault()

    @After fun restore() = Locale.setDefault(saved)

    private val locales = listOf(Locale.FRANCE, Locale.GERMANY, Locale("ar", "EG"))

    private fun eachLocale(block: () -> Unit) = locales.forEach { Locale.setDefault(it); block() }

    @Test fun defaultLocaleReallyDiffers() {
        // the premise: under these locales a plain format would not give "0.50"/ASCII digits
        Locale.setDefault(Locale.FRANCE); assertEquals("0,50", "%.2f".format(0.5))
        Locale.setDefault(Locale("ar", "EG")); assertTrue("%d".format(5) != "5")
    }

    @Test fun readerTimestamps() = eachLocale {
        assertEquals("1:02:05", ReaderProtocol.formatTs(3725))
        assertEquals("0:07", ReaderProtocol.formatTs(7))
        assertEquals(3725, ReaderProtocol.parseTs(ReaderProtocol.formatTs(3725)))
    }

    @Test fun transcriptForTheModel() = eachLocale {
        val t = TranscriptFormat.format(listOf(Utterance(0, "hello", 65.4, 66.0, 0), Utterance(1, "bye", 3725.0, 3726.0, 1)))
        assertEquals("[1:05] S1: hello\n[1:02:05] S2: bye", t)
    }

    @Test fun lrcExport() = eachLocale {
        assertEquals("[01:05.40]hello", PrimeMinutesSession.lrc(listOf(Utterance(0, "hello", 65.4, 66.0))))
    }

    @Test fun nativeSegmentEncodingParses() = eachLocale {
        // what nemo_jni writes (fixed_fmt.h: "." whatever the C locale), read back under the default locale
        val u = NemoStreamEngine.parseSegments("0\u001f0.500\u001f1.250\u001fhello\u001e")
        assertEquals(0.5, u.single().startSec, 0.0)
        assertEquals(1.25, u.single().endSec, 0.0)
    }
}
