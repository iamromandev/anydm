package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.net.TrafficBatch
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SqlTrafficStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : TrafficStore {
    private val queries = database.trafficQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun add(
        batch: TrafficBatch,
        pruneBefore: Long,
    ) = withContext(writeContext) {
        database.transaction {
            for ((key, bytes) in batch.days) {
                queries.ensureDay(key.first, key.second, key.third.name)
                queries.addDay(bytes = bytes, day = key.first, network = key.second, kind = key.third.name)
            }
            for ((id, bytes) in batch.downloads) {
                queries.ensureDownload(id.value)
                queries.addDownload(bytes = bytes, id = id.value)
            }
            for ((day, speed) in batch.speedDays) {
                queries.ensureSpeedDay(day)
                queries.addSpeedDay(bytes = speed.bytes, peak = speed.peak, active = speed.activeSeconds, day = day)
            }
            for ((minute, speed) in batch.speedMinutes) {
                queries.ensureSpeedMinute(minute)
                queries.addSpeedMinute(bytes = speed.bytes, peak = speed.peak, minute = minute)
            }
            queries.pruneMinutes(pruneBefore)
        }
    }

    override suspend fun trafficByDay(
        from: String,
        to: String,
    ): List<DayTraffic> =
        withContext(io) {
            queries.daysBetween(from, to).executeAsList().map { DayTraffic(it.day, it.network, kindOf(it.kind), it.bytes) }
        }

    override suspend fun speedDays(
        from: String,
        to: String,
    ): List<SpeedDay> =
        withContext(io) {
            queries.speedDaysBetween(from, to).executeAsList().map { SpeedDay(it.day, it.bytes, it.peak, it.active_seconds) }
        }

    override suspend fun speedMinutes(since: Long): List<SpeedMinute> =
        withContext(io) { queries.minutesSince(since).executeAsList().map { SpeedMinute(it.minute, it.bytes, it.peak) } }

    override suspend fun downloadTraffic(id: DownloadId): Long =
        withContext(io) { queries.downloadBytes(id.value).executeAsOneOrNull() ?: 0L }

    private fun kindOf(name: String): DownloadKind =
        DownloadKind.entries.firstOrNull { it.name == name }
            ?: throw IncompatibleStoreException("traffic_day.kind holds '$name', which this version of anydm doesn't know")
}
