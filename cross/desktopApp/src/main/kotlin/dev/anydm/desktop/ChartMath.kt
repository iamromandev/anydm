package dev.anydm.desktop

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** One gridline of the throughput chart: where it is in bytes per second and what it says. */
data class SpeedTick(
    val value: Double,
    val label: String,
)

/** The chart's scale: [top] bytes per second at the top edge, a gridline at each of [ticks]. */
data class SpeedAxis(
    val top: Double,
    val ticks: List<SpeedTick>,
)

// Bytes per second in each unit formatRate reads in.
private val UnitBytes =
    mapOf(
        "B/s" to 1.0,
        "KB/s" to 1024.0,
        "MB/s" to 1024.0 * 1024,
        "GB/s" to 1024.0 * 1024 * 1024,
        "TB/s" to 1024.0 * 1024 * 1024 * 1024,
        "bps" to 1.0 / 8,
        "kbps" to 1e3 / 8,
        "Mbps" to 1e6 / 8,
        "Gbps" to 1e9 / 8,
    )

/**
 * A scale for speeds up to [max] bytes per second: two or three gridlines at round steps (1, 2, 2.5 or 5 times a power of
 * ten) in the unit [formatRate] reads [max] in, the top one just clearing [max], each labelled with it.
 */
fun speedAxis(
    max: Long,
    unit: SpeedUnit,
): SpeedAxis {
    if (max <= 0) return SpeedAxis(1.0, emptyList())
    val label = formatRate(max, unit).substringAfter(' ')
    val size = UnitBytes[label] ?: return SpeedAxis(max.toDouble(), emptyList())
    val third = max / size / 3
    val power = 10.0.pow(floor(log10(third)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * power }.first { it >= third }
    val count = ceil(max / size / step).toInt().coerceAtLeast(1)
    val ticks =
        (1..count).map { i ->
            val value = Math.round(i * step * 100) / 100.0
            SpeedTick(value * size, "${number(value)} $label")
        }
    return SpeedAxis(count * step * size, ticks)
}

private fun number(value: Double): String = if (value == floor(value)) value.toLong().toString() else value.toString()
