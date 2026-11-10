package io.github.ayatinene.sonycompanion.data

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalQueries
import java.util.Locale

/**
 * Parses a camera-reported capture-date string into epoch milliseconds for UI sorting/display. A
 * value without an explicit zone is interpreted in [zone] (device default); unusable dates return
 * null. The raw [CameraFile.date] is never modified because it is the queue identity key.
 */
fun captureTimeMillis(date: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val text = date.trim()
    if (text.isEmpty()) return null
    return parseRawDate(text) ?: parseIsoDate(text, zone) ?: parsePtpCompact(text, zone)
}

/** `java.util.Date.toString()` without its zone token: English abbreviations, 4-digit year. */
private val RAW_LOCAL_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern("EEE MMM dd HH:mm:ss uuuu")
    .toFormatter(Locale.US)
    .withResolverStyle(ResolverStyle.STRICT)

/** Sony's compact PTP timestamp with optional fractional seconds and an RFC-822 offset (`+0800`). */
private val PTP_COMPACT_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("uuuuMMdd'T'HHmmss")
    .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
    .optionalStart().appendOffset("+HHmm", "Z").optionalEnd()
    .toFormatter(Locale.US)
    .withResolverStyle(ResolverStyle.STRICT)

/** `Date.toString()` is exactly six single-spaced tokens; isolate the zone token positionally. */
private fun parseRawDate(text: String): Long? {
    val tokens = text.split(' ').filter { it.isNotEmpty() }
    if (tokens.size != 6) return null
    val zone = attempt { ZoneId.of(tokens[4], ZoneId.SHORT_IDS) } ?: return null
    val localText = "${tokens[0]} ${tokens[1]} ${tokens[2]} ${tokens[3]} ${tokens[5]}"
    val local = attempt { LocalDateTime.parse(localText, RAW_LOCAL_FORMAT) } ?: return null
    return attempt { local.atZone(zone).toInstant().toEpochMilli() }
}

private fun parseIsoDate(text: String, zone: ZoneId): Long? =
    attempt { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
        ?: attempt { LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone).toInstant().toEpochMilli() }
        ?: attempt { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(zone).toInstant().toEpochMilli() }

private fun parsePtpCompact(text: String, zone: ZoneId): Long? {
    val parsed = attempt { PTP_COMPACT_FORMAT.parse(text) } ?: return null
    val local = attempt { LocalDateTime.from(parsed) } ?: return null
    val offset = parsed.query(TemporalQueries.offset())
    return attempt {
        (if (offset != null) local.toInstant(offset) else local.atZone(zone).toInstant()).toEpochMilli()
    }
}

private inline fun <T> attempt(block: () -> T): T? =
    try { block() } catch (_: DateTimeException) { null } catch (_: ArithmeticException) { null }
