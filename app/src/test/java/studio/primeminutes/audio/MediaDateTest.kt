package studio.primeminutes.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.primeminutes.core.audio.MediaDate
import java.time.ZoneId

/** An import's creation date from its metadata: the forms MediaMetadataRetriever gives, and what is refused. */
class MediaDateTest {
    private val now = 1_792_400_000_000L   // 2026-10-19
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test fun mp4CreationTimeIsUtc() =
        assertEquals(1_792_311_480_000L, MediaDate.parse("20261018T081800.000Z", now, taipei))   // 2026-10-18 08:18 UTC

    @Test fun isoFormsWithAndWithoutZone() {
        assertEquals(1_792_311_480_000L, MediaDate.parse("2026-10-18T08:18:00Z", now, taipei))
        assertEquals(1_792_311_480_000L, MediaDate.parse("2026-10-18T16:18:00+08:00", now, taipei))
        assertEquals(1_792_311_480_000L, MediaDate.parse("2026-10-18 16:18", now, taipei))   // no zone: the device's
    }

    @Test fun noDateOrNoTimeOfDayOrImplausibleGivesNull() {
        assertNull(MediaDate.parse(null, now, taipei))
        assertNull(MediaDate.parse("", now, taipei))
        assertNull(MediaDate.parse("2026", now, taipei))                    // an MP3 year tag
        assertNull(MediaDate.parse("19040101T000000.000Z", now, taipei))    // MP4 creation_time left at zero
        assertNull(MediaDate.parse("19700101T000000.000Z", now, taipei))
        assertNull(MediaDate.parse("20271018T081800.000Z", now, taipei))    // in the future
    }
}
