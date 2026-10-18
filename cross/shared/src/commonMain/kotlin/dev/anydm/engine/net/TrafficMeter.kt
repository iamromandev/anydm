package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import kotlin.math.max
import kotlin.time.Instant

/** The epoch minute [at] falls in: speed_minute's key. */
internal fun epochMinute(at: Instant): Long = at.epochSeconds.floorDiv(60)

/**
 * Counts received bytes (NET-04) once a second, from the engine's speed sampler: by local day, network and kind, by
 * download, and as speed per day and per minute (bytes, the largest one-second total, the seconds with any bytes).
 * [drain] hands over what was counted since the last save. Not thread-safe: the engine uses it from one thread.
 */
internal class TrafficMeter(
    private val dayOf: (Instant) -> String = ::localDayOf,
) {
    /** What one running download received in a second. */
    data class Sample(
        val id: DownloadId,
        val kind: DownloadKind,
        val bytes: Long,
    )

    private val days = mutableMapOf<Triple<String, String, DownloadKind>, Long>()
    private val downloads = mutableMapOf<DownloadId, Long>()
    private val speedDays = mutableMapOf<String, SpeedDelta>()
    private val speedMinutes = mutableMapOf<Long, SpeedDelta>()

    /** The second ending at [at], on [network]: each running download's bytes in it. A second with none adds nothing. */
    fun second(
        at: Instant,
        network: String,
        samples: List<Sample>,
    ) {
        val total = samples.sumOf { it.bytes }
        if (total <= 0) return
        val day = dayOf(at)
        for (sample in samples) {
            if (sample.bytes <= 0) continue
            days.mergeWith(Triple(day, network, sample.kind), sample.bytes, Long::plus)
            downloads.mergeWith(sample.id, sample.bytes, Long::plus)
        }
        speedDays.mergeWith(day, SpeedDelta(total, total, 1), ::combined)
        speedMinutes.mergeWith(epochMinute(at), SpeedDelta(total, total), ::combined)
    }

    /** What was counted since the last drain; the meter starts empty again. */
    fun drain(): TrafficBatch {
        val batch = TrafficBatch(days.toMap(), downloads.toMap(), speedDays.toMap(), speedMinutes.toMap())
        days.clear()
        downloads.clear()
        speedDays.clear()
        speedMinutes.clear()
        return batch
    }

    /** Puts back a batch that couldn't be saved, so the next save has it too. */
    fun restore(batch: TrafficBatch) {
        batch.days.forEach { (key, bytes) -> days.mergeWith(key, bytes, Long::plus) }
        batch.downloads.forEach { (id, bytes) -> downloads.mergeWith(id, bytes, Long::plus) }
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
