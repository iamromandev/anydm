package dev.anydm.engine.http

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The longest wait a `Retry-After` gives; the queue caps it far lower (1 h). */
private const val MAX_SECONDS = 1_000_000_000L

private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val imfFixdate = Regex("""[A-Za-z]{3}, (\d{2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT""")
private val rfc850 = Regex("""[A-Za-z]+, (\d{2})-([A-Za-z]{3})-(\d{2}) (\d{2}):(\d{2}):(\d{2}) GMT""")
private val asctime = Regex("""[A-Za-z]{3} ([A-Za-z]{3}) ( \d|\d{2}) (\d{2}):(\d{2}):(\d{2}) (\d{4})""")

/**
 * How long a `Retry-After` [value] asks to wait from [now] (RFC 9110 §10.2.3): delta-seconds, or an HTTP-date in any
 * of the three forms §5.6.7 has recipients accept. A date already past waits nothing; anything unparseable is null,
 * and the queue falls back to its own backoff. The weekday isn't checked.
 */
internal fun retryAfterOf(
    value: String?,
    now: Instant,
): Duration? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.all { it in '0'..'9' }) return (text.toLongOrNull()?.coerceAtMost(MAX_SECONDS) ?: MAX_SECONDS).seconds
    val date = dateOf(text, now) ?: return null
    return (date - now).coerceAtLeast(Duration.ZERO)
}

private fun dateOf(
    text: String,
    now: Instant,
): Instant? {
    imfFixdate.matchEntire(text)?.destructured?.let { (d, mon, y, h, min, s) ->
        return instantOf(y.toInt(), mon, d.trim().toInt(), h.toInt(), min.toInt(), s.toInt())
    }
    rfc850.matchEntire(text)?.destructured?.let { (d, mon, yy, h, min, s) ->
        val nowYear = yearOf(now.epochSeconds.floorDiv(SECONDS_PER_DAY))
        val year = (2000 + yy.toInt()).let { if (it > nowYear + 50) it - 100 else it }
        return instantOf(year, mon, d.toInt(), h.toInt(), min.toInt(), s.toInt())
    }
    asctime.matchEntire(text)?.destructured?.let { (mon, d, h, min, s, y) ->
        return instantOf(y.toInt(), mon, d.trim().toInt(), h.toInt(), min.toInt(), s.toInt())
    }
    return null
}

private const val SECONDS_PER_DAY = 86_400L

private fun instantOf(
    year: Int,
    monthName: String,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
): Instant? {
    val month = months.indexOf(monthName) + 1
    if (month == 0 || day !in 1..daysIn(year, month) || hour > 23 || minute > 59 || second > 60) return null
    val seconds = daysFromCivil(year, month, day) * SECONDS_PER_DAY + hour * 3600L + minute * 60L + minOf(second, 59)
    return Instant.fromEpochSeconds(seconds)
}

private fun daysIn(
    year: Int,
    month: Int,
): Int =
    when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

/** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's days_from_civil). */
private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

/** The year of [days] since 1970-01-01 (the year part of civil_from_days). */
private fun yearOf(days: Long): Int {
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = if (mp < 10) mp + 3 else mp - 9
    return (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
}
