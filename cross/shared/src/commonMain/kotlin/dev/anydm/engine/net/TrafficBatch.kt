package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind

/** Bytes, the largest one-second total, and seconds with any bytes, added to a day or minute of speed history. */
data class SpeedDelta(
    val bytes: Long,
    val peak: Long,
    val activeSeconds: Long = 0,
)

/** What a [TrafficMeter] counted since its last save (NET-04): added to the stored totals in one go. */
data class TrafficBatch(
    /** (local day, network, kind) to bytes. */
    val days: Map<Triple<String, String, DownloadKind>, Long> = emptyMap(),
    val downloads: Map<DownloadId, Long> = emptyMap(),
    /** Local day to speed. */
    val speedDays: Map<String, SpeedDelta> = emptyMap(),
    /** Epoch minute to speed. */
    val speedMinutes: Map<Long, SpeedDelta> = emptyMap(),
    /** The same keys as [days] and [downloads], for the bytes sent (TOR-09). */
    val daysUp: Map<Triple<String, String, DownloadKind>, Long> = emptyMap(),
    val downloadsUp: Map<DownloadId, Long> = emptyMap(),
) {
    /** Bytes received and sent on [network] on the local days [from]..[to] (`YYYY-MM-DD`, inclusive). */
    fun networkBytes(
        network: String,
        from: String,
        to: String,
    ): Long = networkTotal(days, daysUp, network, from, to)

    val isEmpty: Boolean
        get() =
            days.isEmpty() &&
                downloads.isEmpty() &&
                speedDays.isEmpty() &&
                speedMinutes.isEmpty() &&
                daysUp.isEmpty() &&
                downloadsUp.isEmpty()
}

/** The sum of both maps' entries for [network] on days [from]..[to]; days compare as `YYYY-MM-DD` text. */
internal fun networkTotal(
    days: Map<Triple<String, String, DownloadKind>, Long>,
    daysUp: Map<Triple<String, String, DownloadKind>, Long>,
    network: String,
    from: String,
    to: String,
): Long =
    (days.entries.asSequence() + daysUp.entries.asSequence())
        .filter { (key, _) -> key.second == network && key.first >= from && key.first <= to }
        .sumOf { it.value }
