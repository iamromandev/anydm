package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.net.TrafficBatch

/** Bytes received on [network] by downloads of [kind] on local [day], and the bytes they sent ([bytesUp]). */
data class DayTraffic(
    val day: String,
    val network: String,
    val kind: DownloadKind,
    val bytes: Long,
    val bytesUp: Long = 0,
)

/** A local day's bytes, its largest one-second total (B/s) and the seconds anything was received. */
data class SpeedDay(
    val day: String,
    val bytes: Long,
    val peak: Long,
    val activeSeconds: Long,
)

/** An epoch minute's bytes and its largest one-second total (B/s); kept for 24 hours. */
data class SpeedMinute(
    val minute: Long,
    val bytes: Long,
    val peak: Long,
)

/**
 * Traffic and speed history (NET-04): daily traffic by network and kind, speed per day (kept) and per minute (24 h),
 * and each download's total (removed with it). Every call is one transaction.
 */
interface TrafficStore {
    /** Adds [batch] to the totals and drops speed minutes before [pruneBefore] (an epoch minute). */
    suspend fun add(
        batch: TrafficBatch,
        pruneBefore: Long,
    )

    /** Days [from]..[to] (`YYYY-MM-DD`, inclusive), by day, network and kind. */
    suspend fun trafficByDay(
        from: String,
        to: String,
    ): List<DayTraffic>

    /** Bytes received and sent on [network] on the days [from]..[to] (`YYYY-MM-DD`, inclusive), all kinds; 0 when none. */
    suspend fun networkBytes(
        network: String,
        from: String,
        to: String,
    ): Long

    suspend fun speedDays(
        from: String,
        to: String,
    ): List<SpeedDay>

    /** Minutes from [since] on, oldest first. */
    suspend fun speedMinutes(since: Long): List<SpeedMinute>

    /** Every byte [id] received; 0 when none or it is gone. */
    suspend fun downloadTraffic(id: DownloadId): Long

    /** Every byte [id] sent; 0 when none or it is gone. */
    suspend fun downloadUpload(id: DownloadId): Long
}
