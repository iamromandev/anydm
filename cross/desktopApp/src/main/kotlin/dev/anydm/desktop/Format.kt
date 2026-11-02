package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.PauseReason

private val ByteUnits = listOf("B", "KB", "MB", "GB", "TB")

/** Bytes in 1024-based units: `512 B`, `1.5 MB`. */
internal fun formatBytes(bytes: Long): String {
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < ByteUnits.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else "%.1f %s".format(value, ByteUnits[unit])
}

internal fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

/** How long [remaining] bytes take at [speed] bytes per second: `1h 02m`, `3m 05s`, `12s`; null when it can't be told. */
internal fun formatEta(
    remaining: Long,
    speed: Long,
): String? {
    if (speed <= 0 || remaining < 0) return null
    return formatDuration((remaining + speed - 1) / speed)
}

/** A span of [seconds] as `1h 02m`, `3m 05s` or `12s`. */
internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "${s}s"
    }
}

/** Done as a fraction from 0 to 1, or null when the total is unknown. */
internal fun progress(s: DownloadSnapshot): Float? {
    val total = s.total ?: return null
    if (total <= 0) return if (s.download.status == DownloadStatus.COMPLETED) 1f else null
    return (s.downloaded.toFloat() / total).coerceIn(0f, 1f)
}

/** Seconds a row still needs: only while it downloads, at a speed, with a known total; null otherwise. */
internal fun etaSeconds(s: DownloadSnapshot): Long? {
    if (s.download.status != DownloadStatus.DOWNLOADING) return null
    val total = s.total ?: return null
    val remaining = (total - s.downloaded).coerceAtLeast(0)
    return if (s.speed <= 0) null else (remaining + s.speed - 1) / s.speed
}

/** The ETA a row shows: only while it downloads, at a speed, with a known total. */
internal fun eta(s: DownloadSnapshot): String? = etaSeconds(s)?.let(::formatDuration)

/** What a row is called: its file name, else the address it comes from, else its number. */
internal fun displayName(s: DownloadSnapshot): String =
    s.download.fileName
        ?: s.mirrors.firstOrNull()?.url
        ?: s.download.sourceUrl
        ?: "Download #${s.download.id.value}"

internal fun statusLabel(s: DownloadSnapshot): String {
    val status = s.download.status
    val label =
        status.name
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    return when {
        status == DownloadStatus.PAUSED && PauseReason.GLOBAL in s.download.pauseReasons -> "Paused (all)"
        status == DownloadStatus.PAUSED && PauseReason.LOW_DISK in s.download.pauseReasons -> "Paused (disk full)"
        else -> label
    }
}

/** A speed in the unit the user chose: 1024-based bytes (`4.5 MB/s`) or decimal bits (`36.0 Mbps`). */
internal fun formatRate(
    bytesPerSecond: Long,
    unit: SpeedUnit,
): String {
    if (unit == SpeedUnit.BYTES) return formatSpeed(bytesPerSecond)
    var value = bytesPerSecond * 8.0
    val units = listOf("bps", "kbps", "Mbps", "Gbps")
    var index = 0
    while (value >= 1000 && index < units.lastIndex) {
        value /= 1000
        index++
    }
    return if (index == 0) "${value.toLong()} bps" else "%.1f %s".format(value, units[index])
}

/** How long before [now] [then] was: `just now`, `5 min ago`, `3 h ago`, `2 d ago`. */
internal fun formatAgo(
    now: kotlin.time.Instant,
    then: kotlin.time.Instant,
): String {
    val seconds = (now - then).inWholeSeconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

/** Seconds as `m:ss`; never below `0:00`. */
internal fun formatCountdown(seconds: Long): String {
    val left = seconds.coerceAtLeast(0)
    return "${left / 60}:${(left % 60).toString().padStart(2, '0')}"
}
