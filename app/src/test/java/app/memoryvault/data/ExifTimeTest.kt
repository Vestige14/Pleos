package app.memoryvault.data

import org.junit.Assert.*
import org.junit.Test

class ExifTimeTest {
    // Falling back to the device zone or ignoring the EXIF offset shifts matching by nine hours.
    @Test fun missingOffset_usesSeoul() {
        assertEquals(1794114300000L, ExifTime.parse("2026:11:08 14:05:00", null))
    }

    @Test fun explicitOffset_winsOverSeoul() {
        assertEquals(1794114300000L, ExifTime.parse("2026:11:08 05:05:00", "+00:00"))
        assertEquals(1794114300000L, ExifTime.parse("2026:11:08 19:05:00", "+14:00"))
    }

    @Test fun invalidDatesAndOffsets_doNotBecomeCandidates() {
        listOf(null, "", "0000:00:00 00:00:00", "0000:01:01 12:00:00",
            "2026:02:30 12:00:00", "2026:11:08 25:00:00", "garbled").forEach {
            assertNull("date=$it", ExifTime.parse(it, null))
        }
        assertNull(ExifTime.parse("2026:11:08 14:05:00", "+25:00"))
        assertNull(ExifTime.parse("2026:11:08 14:05:00", "bad"))
    }
}
