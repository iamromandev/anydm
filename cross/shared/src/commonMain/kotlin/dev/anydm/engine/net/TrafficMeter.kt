package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import kotlin.math.max
import kotlin.time.Instant

/** The epoch minute [at] falls in: speed_minute's key. */
internal fun epochMinute(at: Instant): Long = at.epochSeconds.floorDiv(60)

/**
 * Counts received and sent bytes (NET-04, TOR-09) once a second, from the engine's speed sampler: by local day, network
 * and kind, by download, and as speed per day and per minute (bytes, the largest one-second total, the seconds with any
 * bytes). Only received bytes make speed; sent bytes are totals. [drain] hands over what was counted since the last save.
 * Not thread-safe: the engine uses it from one thread.
 */
internal class TrafficMeter(
    private val dayOf: (Instant) -> String = ::localDayOf,
) {
    /** What one running download received, and sent ([bytesUp]), in a second. */
    data class Sample(
        val id: DownloadId,
        val kind: DownloadKind,
        val bytes: Long,
        val bytesUp: Long = 0,
    )

    private val days = mutableMapOf<Triple<String, String, DownloadKind>, Long>()
    private val downloads = mutableMapOf<DownloadId, Long>()
    private val daysUp = mutableMapOf<Triple<String, String, DownloadKind>, Long>()
    private val downloadsUp = mutableMapOf<DownloadId, Long>()
    private val speedDays = mutableMapOf<String, SpeedDelta>()
    private val speedMinutes = mutableMapOf<Long, SpeedDelta>()

    /** The second ending at [at], on [network]: each running download's bytes in it. A second with none adds nothing. */
    fun second(
        at: Instant,
        network: String,
        samples: List<Sample>,
    ) {
        val total = samples.sumOf { it.bytes }
        val day = dayOf(at)
        add(day, network, samples)
        if (total <= 0) return
        speedDays.mergeWith(day, SpeedDelta(total, total, 1), ::combined)
        speedMinutes.mergeWith(epochMinute(at), SpeedDelta(total, total), ::combined)
    }

    /**
     * Bytes that arrived after a download's last sampled second (its run just ended): counted toward the day, the
     * network, the download and the speed totals, but not as a second of their own, so peaks and active time stay true.
     */
    fun bytes(
        at: Instant,
        network: String,
        samples: List<Sample>,
    ) {
        val total = samples.sumOf { it.bytes }
        val day = dayOf(at)
        add(day, network, samples)
        if (total <= 0) return
        speedDays.mergeWith(day, SpeedDelta(total, 0), ::combined)
        speedMinutes.mergeWith(epochMinute(at), SpeedDelta(total, 0), ::combined)
    }

    private fun add(
        day: String,
        network: String,
        samples: List<Sample>,
    ) {
        for (sample in samples) {
            if (sample.bytes > 0) {
                days.mergeWith(Triple(day, network, sample.kind), sample.bytes, Long::plus)
                downloads.mergeWith(sample.id, sample.bytes, Long::plus)
            }
            if (sample.bytesUp > 0) {
                daysUp.mergeWith(Triple(day, network, sample.kind), sample.bytesUp, Long::plus)
                downloadsUp.mergeWith(sample.id, sample.bytesUp, Long::plus)
            }
        }
    }

    /** Bytes received and sent on [network] on the local days [from]..[to] (`YYYY-MM-DD`, inclusive) that are not drained yet. */
    fun pendingBytes(
        network: String,
        from: String,
        to: String,
    ): Long = networkTotal(days, daysUp, network, from, to)

    /** What was counted since the last drain; the meter starts empty again. */
    fun drain(): TrafficBatch {
        val batch =
            TrafficBatch(days.toMap(), downloads.toMap(), speedDays.toMap(), speedMinutes.toMap(), daysUp.toMap(), downloadsUp.toMap())
        days.clear()
        downloads.clear()
        daysUp.clear()
        downloadsUp.clear()
        speedDays.clear()
        speedMinutes.clear()
        return batch
    }

    /** Puts back a batch that couldn't be saved, so the next save has it too. */
    fun restore(batch: TrafficBatch) {
        batch.days.forEach { (key, bytes) -> days.mergeWith(key, bytes, Long::plus) }
        batch.downloads.forEach { (id, bytes) -> downloads.mergeWith(id, bytes, Long::plus) }
        batch.daysUp.forEach { (key, bytes) -> daysUp.mergeWith(key, bytes, Long::plus) }
        batch.downloadsUp.forEach { (id, bytes) -> downloadsUp.mergeWith(id, bytes, Long::plus) }
        batch.speedDays.forEach { (day, speed) -> speedDays.mergeWith(day, speed, ::combined) }
        batch.speedMinutes.forEach { (minute, speed) -> speedMinutes.mergeWith(minute, speed, ::combined) }
    }

    private fun combined(
        a: SpeedDelta,
        b: SpeedDelta,
    ) = SpeedDelta(a.bytes + b.bytes, max(a.peak, b.peak), a.activeSeconds + b.activeSeconds)
}

/** Puts [value] at [key], or [combine]s it with what is there (Map.merge is JVM-only). */
private fun <K, V> MutableMap<K, V>.mergeWith(
    key: K,
    value: V,
    combine: (V, V) -> V,
) {
    val old = this[key]
    this[key] = if (old == null) value else combine(old, value)
}
