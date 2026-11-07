package dev.anydm.desktop

import java.util.Locale

private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

private fun one(v: Double) = String.format(Locale.ROOT, "%.1f", v)

fun formatBytes(bytes: Long): String =
    when {
        bytes >= GB -> "${one(bytes / GB)} GB"
        bytes >= MB -> "${one(bytes / MB)} MB"
        bytes >= KB -> "${one(bytes / KB)} KB"
        else -> "$bytes B"
    }

fun formatSpeed(
    bytesPerSec: Long,
    unit: SpeedUnit,
): String =
    when {
        bytesPerSec <= 0 -> "0 B/s"
        unit == SpeedUnit.MBps -> "${one(bytesPerSec / MB)} MB/s"
        else -> "${one(bytesPerSec * 8 / MB)} Mbps"
    }

fun formatEta(seconds: Long): String =
    when {
        seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
