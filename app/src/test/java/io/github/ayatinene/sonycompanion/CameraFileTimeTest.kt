package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.CameraFile
import io.github.ayatinene.sonycompanion.data.captureTimeMillis
import io.github.ayatinene.sonycompanion.data.matchCurrentFile
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/** Exercises [captureTimeMillis] for RAW, JPEG and PTP compact dates without touching the raw string. */
class CameraFileTimeTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** The exact string `Date.toString()` writes on a device whose default zone is [zoneId]. */
    private fun dateToString(zoneId: String, epochMillis: Long): String {
        val previous = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            java.util.Date(epochMillis).toString()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

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

    @Test fun rawDateAbbreviationResolvesToTheCallersOwnZoneInsteadOfTheAmbiguousShortIds() {
        // A device in Asia/Shanghai writes "CST"; ZoneId.SHORT_IDS alone would wrongly pick America/Chicago.
        val instant = Instant.parse("2026-10-24T05:00:00Z")
        val text = dateToString("Asia/Shanghai", instant.toEpochMilli())
        assertTrue(text, text.contains("CST"))
        assertEquals(instant.toEpochMilli(), captureTimeMillis(text, shanghai))
    }

    @Test fun rawDateAbbreviationFollowsDaylightSavingInAmericaChicago() {
        val chicago = ZoneId.of("America/Chicago")
        val winter = Instant.parse("2026-01-15T18:00:00Z")
        val summer = Instant.parse("2026-07-15T17:00:00Z")
        val winterText = dateToString("America/Chicago", winter.toEpochMilli())
        val summerText = dateToString("America/Chicago", summer.toEpochMilli())
        assertTrue(winterText, winterText.contains("CST"))
        assertTrue(summerText, summerText.contains("CDT"))
        assertEquals(winter.toEpochMilli(), captureTimeMillis(winterText, chicago))
        assertEquals(summer.toEpochMilli(), captureTimeMillis(summerText, chicago))
    }

    @Test fun rawDateWithAnExplicitOffsetIgnoresTheCallersZone() {
        val text = "Sat Oct 24 13:00:00 GMT+08:00 2026"
        assertEquals(1792818000000L, captureTimeMillis(text, ZoneId.of("America/Chicago")))
        assertEquals(1792818000000L, captureTimeMillis(text, ZoneId.of("UTC")))
        assertEquals(1792818000000L, captureTimeMillis(text, shanghai))
    }

    @Test fun aRawAbbreviationThatDoesNotNameTheCallersZoneFallsBackToShortIds() {
        val text = "Sat Jan 10 13:00:00 CST 2026"
        val chicago = ZonedDateTime.of(2026, 1, 10, 13, 0, 0, 0, ZoneId.of("America/Chicago")).toInstant().toEpochMilli()
        // UTC never writes "CST", so the legacy SHORT_IDS mapping (CST -> America/Chicago) is preserved.
        assertEquals(chicago, captureTimeMillis(text, ZoneId.of("UTC")))
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
