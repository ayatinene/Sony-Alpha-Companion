package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.CameraFile
import io.github.ayatinene.sonycompanion.data.captureTimeMillis
import io.github.ayatinene.sonycompanion.data.matchCurrentFile
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** Exercises [captureTimeMillis] for RAW, JPEG and PTP compact dates without touching the raw string. */
class CameraFileTimeTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun parsesTheRawDateToStringFormAndHonoursTheEmbeddedZone() {
        val text = "Sat Oct 24 13:00:00 GMT+08:00 2026"
        assertEquals(1792818000000L, captureTimeMillis(text, ZoneId.of("UTC")))
        assertEquals(1792818000000L, captureTimeMillis(text, shanghai))
        assertEquals(1792818000000L, captureTimeMillis("Sat Oct 24 05:00:00 UTC 2026", ZoneId.of("UTC")))
    }

    @Test fun parsesIso8601OffsetsAndTreatsEquivalentOffsetsEqually() {
        assertEquals(1792818000000L, captureTimeMillis("2026-10-24T05:00:00Z"))
        assertEquals(1792818000000L, captureTimeMillis("2026-10-24T13:00:00+08:00"))
        assertEquals(1792818000000L, captureTimeMillis("2026-10-24T13:00:00.000+08:00"))
    }

    @Test fun parsesIsoLocalDateTimeAndDateOnlyInTheProvidedZone() {
        assertEquals(1792818000000L, captureTimeMillis("2026-10-24T13:00:00", shanghai))
        assertEquals(1792771200000L, captureTimeMillis("2026-10-24", shanghai))
        assertEquals(1792818000000L, captureTimeMillis("2026-10-24T05:00:00", ZoneId.of("UTC")))
    }

    @Test fun parsesPtpCompactWithOptionalFractionAndOffset() {
        assertEquals(1792818000000L, captureTimeMillis("20261024T130000+0800"))
        assertEquals(1792818000000L, captureTimeMillis("20261024T130000", shanghai))
        assertEquals(1792818000000L, captureTimeMillis("20261024T050000Z"))
        assertEquals(1792818000123L, captureTimeMillis("20261024T130000.123+0800"))
    }

    @Test fun crossesYearMonthAndWeekBoundaries() {
        assertTrue(captureTimeMillis("2025-12-31T23:59:59Z")!! < captureTimeMillis("2026-01-01T00:00:00Z")!!)
        assertTrue(captureTimeMillis("2026-02-28T23:59:59Z")!! < captureTimeMillis("2026-03-01T00:00:00Z")!!)
        assertTrue(captureTimeMillis("2026-10-24T00:00:00Z")!! < captureTimeMillis("2026-10-25T00:00:00Z")!!)
        assertTrue(captureTimeMillis("2026-10-25T00:00:00Z")!! < captureTimeMillis("2026-10-26T00:00:00Z")!!)
    }

    @Test fun rejectsMissingGarbledAndImpossibleDates() {
        assertNull(captureTimeMillis(""))
        assertNull(captureTimeMillis("   "))
        assertNull(captureTimeMillis("not-a-date"))
        // Strict resolution: no SimpleDateFormat-style rollover of an impossible day or month.
        assertNull(captureTimeMillis("2026-02-30T10:00:00Z"))
        assertNull(captureTimeMillis("2026-13-01T00:00:00Z"))
        assertNull(captureTimeMillis("20261024T256000"))
        assertNull(captureTimeMillis("Sat Feb 30 13:00:00 GMT+08:00 2026"))
        // Year beyond the epoch-millis range must not leak an ArithmeticException from toEpochMilli.
        assertNull(captureTimeMillis("+999999999-12-31T23:59:59Z"))
    }

    @Test fun aMissingZoneUsesTheProvidedZoneInsteadOfDroppingIt() {
        assertEquals(1792818000000L, captureTimeMillis("2026-10-24T13:00:00", shanghai))
        assertEquals(1792846800000L, captureTimeMillis("2026-10-24T13:00:00", ZoneId.of("UTC")))
    }

    @Test fun parsingLeavesTheQueueIdentityDateUntouched() {
        val raw = "20261024T130000+0800"
        val file = CameraFile("1", "DSC00001.ARW", date = raw, parent = "0/1", size = 100, raw = true, handle = 1)
        val millis = captureTimeMillis(file.date, ZoneId.of("UTC"))
        assertNotNull(millis)
        assertEquals(raw, file.date)
        assertNotNull(matchCurrentFile(file, listOf(file)))
    }
}
