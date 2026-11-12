package dev.anydm.desktop

import java.math.BigDecimal
import java.math.MathContext
import java.time.DayOfWeek
import java.util.Locale
import kotlin.math.roundToLong

enum class DataPeriod(
    val label: String,
) {
    Day("Day"),
    Week("Week"),
    Month("Month"),
}

/** Saved state, always in bytes. */
data class Schedule(
    val enabled: Boolean,
    val days: Set<DayOfWeek>,
    val start: String,
    val end: String,
)

data class NetworkLimits(
    val speed: Long?,
    val data: Long?,
    val period: DataPeriod,
    val used: Long,
)

data class Limits(
    val total: Long?,
    val slowOn: Boolean,
    val slow: Long,
    val concurrency: Int,
    val schedule: Schedule,
    val networks: Map<String, NetworkLimits>,
)

/** Editable state, in the display unit. */
data class LimitField(
    val on: Boolean,
    val text: String,
    val kept: Kept? = null,
)

/** The saved value of a field and how it was displayed; while the text still equals [text], the saved [bytes] are reused. */
data class Kept(
    val text: String,
    val bytes: Long,
)

data class GeneralDraft(
    val total: LimitField,
    val slowOn: Boolean,
    val slowText: String,
    val concurrency: Int,
    val schedule: Schedule,
    val slowKept: Kept? = null,
)

data class NetworkDraft(
    val speed: LimitField,
    val data: LimitField,
    val period: DataPeriod,
    val used: Long,
)

data class LimitsDraft(
    val general: GeneralDraft,
    val networks: Map<String, NetworkDraft>,
)

const val MAX_LIMIT = 100_000.0
const val MIN_CONCURRENCY = 1
const val MAX_CONCURRENCY = 10
const val DEFAULT_CONCURRENCY = 2
const val DEFAULT_SLOW_MBPS = 2.0
const val GENERAL_PANE = "general"
const val DAYS_ERROR = "Pick at least one day"
const val TIME_FORMAT_ERROR = "Use the HH:mm format"

val DEFAULT_SCHEDULE =
    Schedule(
        false,
        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        "09:00",
        "17:00",
    )

private const val BYTES_PER_MB = 1_048_576.0
private const val BYTES_PER_GB = 1_073_741_824.0
private val amountPattern = Regex("""\d+(\.\d)?""")
private val timePattern = Regex("""\d{2}:\d{2}""")

fun parseAmount(text: String): Double? {
    if (!amountPattern.matches(text)) return null
    val v = text.toDouble()
    return v.takeIf { it > 0 && it <= MAX_LIMIT }
}

fun parseTime(text: String): Int? {
    if (!timePattern.matches(text)) return null
    val h = text.substring(0, 2).toInt()
    val m = text.substring(3).toInt()
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

fun scheduleError(s: Schedule): String? {
    if (!s.enabled) return null
    if (s.days.isEmpty()) return DAYS_ERROR
    val start = parseTime(s.start)
    val end = parseTime(s.end)
    return when {
        start == null || end == null -> TIME_FORMAT_ERROR
        end <= start -> "End time must be after start time"
        else -> null
    }
}

fun badStart(s: Schedule): Boolean = scheduleError(s) != null && parseTime(s.start) == null

fun badEnd(s: Schedule): Boolean {
    val error = scheduleError(s)
    return error != null && error != DAYS_ERROR && (parseTime(s.end) == null || error != TIME_FORMAT_ERROR)
}

fun unitLabel(unit: SpeedUnit): String = if (unit == SpeedUnit.MBps) "MB/s" else "Mbps"

private fun speedToBytes(
    value: Double,
    unit: SpeedUnit,
): Long = (if (unit == SpeedUnit.MBps) value * BYTES_PER_MB else value * BYTES_PER_MB / 8).roundToLong()

private fun bytesToSpeed(
    bytes: Long,
    unit: SpeedUnit,
): Double = if (unit == SpeedUnit.MBps) bytes / BYTES_PER_MB else bytes * 8 / BYTES_PER_MB

private fun display(v: Double): String {
    val text = String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")
    // A tiny value would round to 0, which cannot be typed; show two significant digits instead.
    return if (v > 0 && text == "0") BigDecimal(v).round(MathContext(2)).toPlainString() else text
}

private fun keptOf(
    bytes: Long,
    v: Double,
) = display(v).let { Kept(it, bytes) }

private fun speedField(
    bytes: Long?,
    unit: SpeedUnit,
) = if (bytes == null) LimitField(false, "") else keptOf(bytes, bytesToSpeed(bytes, unit)).let { LimitField(true, it.text, it) }

private fun dataField(bytes: Long?) =
    if (bytes == null) LimitField(false, "") else keptOf(bytes, bytes / BYTES_PER_GB).let { LimitField(true, it.text, it) }

fun draftOf(
    limits: Limits,
    unit: SpeedUnit,
    slowOn: Boolean,
): LimitsDraft =
    LimitsDraft(
        GeneralDraft(
            speedField(limits.total, unit),
            slowOn,
            display(bytesToSpeed(limits.slow, unit)),
            limits.concurrency,
            limits.schedule,
            keptOf(limits.slow, bytesToSpeed(limits.slow, unit)),
        ),
        limits.networks.mapValues { (_, n) ->
            NetworkDraft(speedField(n.speed, unit), dataField(n.data), n.period, n.used)
        },
    )

/**
 * Re-expresses a draft written in [from] in [to]: the amounts stay the same, only their display changes, so an unsaved draft
 * survives a change of unit. An untouched value keeps its exact saved bytes; a typed one that does not parse is left as typed.
 */
fun rebaseDraft(
    draft: LimitsDraft,
    from: SpeedUnit,
    to: SpeedUnit,
): LimitsDraft {
    if (from == to) return draft
    val g = draft.general
    val slowBytes = if (g.slowUnedited()) g.slowKept?.bytes else parseAmount(g.slowText)?.let { speedToBytes(it, from) }
    val slow = slowBytes?.let { keptOf(it, bytesToSpeed(it, to)) }
    return LimitsDraft(
        g.copy(
            total = g.total.rebased(from, to),
            slowText = slow?.text ?: g.slowText,
            slowKept = slow ?: g.slowKept,
        ),
        draft.networks.mapValues { (_, n) -> n.copy(speed = n.speed.rebased(from, to)) },
    )
}

private fun LimitField.rebased(
    from: SpeedUnit,
    to: SpeedUnit,
): LimitField {
    if (!on) return this
    val bytes = speedBytes(this, from) ?: return this
    return keptOf(bytes, bytesToSpeed(bytes, to)).let { LimitField(true, it.text, it) }
}

private fun GeneralDraft.slowUnedited() = slowKept != null && slowKept.text == slowText

fun slowBad(g: GeneralDraft) = !g.slowUnedited() && parseAmount(g.slowText) == null

private fun LimitField.unedited() = kept != null && kept.text == text

private fun speedBytes(
    f: LimitField,
    unit: SpeedUnit,
): Long? =
    when {
        !f.on -> null
        f.unedited() -> f.kept?.bytes
        else -> parseAmount(f.text)?.let { speedToBytes(it, unit) }
    }

private fun dataBytes(f: LimitField): Long? =
    when {
        !f.on -> null
        f.unedited() -> f.kept?.bytes
        else -> parseAmount(f.text)?.let { (it * BYTES_PER_GB).roundToLong() }
    }

private fun slowBytes(
    g: GeneralDraft,
    unit: SpeedUnit,
): Long {
    val previous = g.slowKept?.bytes ?: speedToBytes(DEFAULT_SLOW_MBPS, SpeedUnit.MBps)
    return when {
        g.slowUnedited() -> previous
        else -> parseAmount(g.slowText)?.let { speedToBytes(it, unit) } ?: previous
    }
}

fun limitsOf(
    draft: LimitsDraft,
    unit: SpeedUnit,
): Limits? {
    if (errorPanes(draft).isNotEmpty()) return null
    val g = draft.general
    return Limits(
        speedBytes(g.total, unit),
        g.slowOn,
        slowBytes(g, unit),
        g.concurrency,
        g.schedule,
        draft.networks.mapValues { (_, n) ->
            NetworkLimits(speedBytes(n.speed, unit), dataBytes(n.data), n.period, n.used)
        },
    )
}

private fun LimitField.bad() = on && !unedited() && parseAmount(text) == null

fun errorPanes(draft: LimitsDraft): Set<String> {
    val g = draft.general
    val result = mutableSetOf<String>()
    if (g.total.bad() || (g.slowOn && slowBad(g)) || scheduleError(g.schedule) != null) result += GENERAL_PANE
    draft.networks.forEach { (id, n) -> if (n.speed.bad() || n.data.bad()) result += id }
    return result
}

fun summaryGeneral(
    d: GeneralDraft,
    unit: SpeedUnit,
): String =
    when {
        d.slowOn -> "Slow · ${d.slowText} ${unitLabel(unit)}"
        d.total.on -> if (d.total.bad()) "Invalid" else "${d.total.text} ${unitLabel(unit)}"
        else -> "No limit"
    }

fun summaryNetwork(
    d: NetworkDraft,
    unit: SpeedUnit,
): String {
    val parts =
        listOfNotNull(
            if (d.speed.on) "${d.speed.text} ${unitLabel(unit)}" else null,
            if (d.data.on) "${d.data.text} GB/${d.period.label}" else null,
        )
    return if (parts.isEmpty()) "No limit" else parts.joinToString(" · ")
}

fun defaultGeneral(unit: SpeedUnit) =
    GeneralDraft(
        LimitField(false, ""),
        false,
        display(bytesToSpeed(speedToBytes(DEFAULT_SLOW_MBPS, SpeedUnit.MBps), unit)),
        DEFAULT_CONCURRENCY,
        DEFAULT_SCHEDULE,
        keptOf(speedToBytes(DEFAULT_SLOW_MBPS, SpeedUnit.MBps), bytesToSpeed(speedToBytes(DEFAULT_SLOW_MBPS, SpeedUnit.MBps), unit)),
    )

fun removeLimits(d: NetworkDraft) = d.copy(speed = d.speed.copy(on = false), data = d.data.copy(on = false))

fun initialLimits(
    networks: List<Network>,
    usage: Map<String, Long>,
) = Limits(
    null,
    false,
    speedToBytes(DEFAULT_SLOW_MBPS, SpeedUnit.MBps),
    DEFAULT_CONCURRENCY,
    DEFAULT_SCHEDULE,
    networks.associate { it.id to NetworkLimits(null, null, DataPeriod.Month, usage[it.id] ?: 0L) },
)
