package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.store.db.AnydmDb
import dev.anydm.engine.torrent.SeedRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SqlTorrentStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : TorrentStore {
    private val queries = database.torrentQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun get(id: DownloadId): TorrentRow? =
        withContext(io) {
            queries.selectByDownload(id).executeAsOneOrNull()?.let {
                TorrentRow(
                    it.download_id,
                    it.metadata,
                    decodePriorities(it.priorities),
                    it.sequential,
                    it.resume,
                    ratioRule(it.seed_ratio),
                    minutesRule(it.seed_minutes),
                    decodeFileDone(it.file_done),
                )
            }
        }

    override suspend fun setMetadata(
        id: DownloadId,
        bytes: ByteArray,
    ): Boolean = withContext(writeContext) { queries.setMetadata(bytes, id).value > 0 }

    override suspend fun setPriorities(
        id: DownloadId,
        priorities: List<Int>,
    ): Boolean {
        require(priorities.all { it in 0..7 }) { "file priorities are 0 to 7: $priorities" }
        return withContext(writeContext) { queries.setPriorities(priorities.joinToString(","), id).value > 0 }
    }

    override suspend fun setSequential(
        id: DownloadId,
        on: Boolean,
    ): Boolean = withContext(writeContext) { queries.setSequential(on, id).value > 0 }

    override suspend fun setResume(
        id: DownloadId,
        bytes: ByteArray?,
    ): Boolean = withContext(writeContext) { queries.setResume(bytes, id).value > 0 }

    override suspend fun setFileDone(
        id: DownloadId,
        done: List<Long>?,
    ): Boolean = withContext(writeContext) { queries.setFileDone(done?.let(::encodeFileDone), id).value > 0 }

    override suspend fun setSeedRules(
        id: DownloadId,
        ratio: SeedRule<Double>,
        minutes: SeedRule<Int>,
    ): Boolean {
        requireSeedRules(ratio, minutes)
        return withContext(writeContext) { queries.setSeedRules(storedRatio(ratio), storedMinutes(minutes), id).value > 0 }
    }
}

private const val FILE_DONE_BYTES = 8

/** Eight bytes per file, big-endian. */
internal fun encodeFileDone(done: List<Long>): ByteArray {
    val out = ByteArray(done.size * FILE_DONE_BYTES)
    done.forEachIndexed { i, value ->
        for (b in 0 until FILE_DONE_BYTES) out[i * FILE_DONE_BYTES + b] = (value ushr (8 * (FILE_DONE_BYTES - 1 - b))).toByte()
    }
    return out
}

/** Empty for nothing stored, for a length that is not a whole number of files, and for a value below zero. */
internal fun decodeFileDone(stored: ByteArray?): List<Long> {
    if (stored == null || stored.isEmpty() || stored.size % FILE_DONE_BYTES != 0) return emptyList()
    val done =
        List(stored.size / FILE_DONE_BYTES) { i ->
            var value = 0L
            for (b in 0 until FILE_DONE_BYTES) value = (value shl 8) or (stored[i * FILE_DONE_BYTES + b].toLong() and 0xFF)
            value
        }
    return if (done.any { it < 0 }) emptyList() else done
}

// NULL takes the global setting, 0 is no limit, anything above is the limit.
internal fun storedRatio(rule: SeedRule<Double>): Double? =
    when (rule) {
        SeedRule.Inherit -> null
        SeedRule.Off -> 0.0
        is SeedRule.At -> rule.value
    }

internal fun storedMinutes(rule: SeedRule<Int>): Long? =
    when (rule) {
        SeedRule.Inherit -> null
        SeedRule.Off -> 0L
        is SeedRule.At -> rule.value.toLong()
    }

private fun ratioRule(stored: Double?): SeedRule<Double> =
    when {
        stored == null -> SeedRule.Inherit
        stored > 0 -> SeedRule.At(stored)
        else -> SeedRule.Off
    }

private fun minutesRule(stored: Long?): SeedRule<Int> =
    when {
        stored == null -> SeedRule.Inherit
        stored > 0 -> SeedRule.At(stored.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        else -> SeedRule.Off
    }

// '' is no choice; a value this version can't read counts as no choice too: a stored row must not stop the engine.
private fun decodePriorities(text: String): List<Int> =
    if (text.isEmpty()) emptyList() else text.split(',').map { it.toIntOrNull()?.takeIf { n -> n in 0..7 } ?: return emptyList() }
