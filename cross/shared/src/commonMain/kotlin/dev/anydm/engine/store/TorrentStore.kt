package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.torrent.SeedRule

private const val MAX_PRIORITY = 7

/** A limit has to be above zero; the stored 0 is [SeedRule.Off]. */
internal fun requireSeedRules(
    ratio: SeedRule<Double>,
    minutes: SeedRule<Int>,
) {
    require(ratio !is SeedRule.At || ratio.value > 0) { "a seed ratio must be > 0: $ratio" }
    require(minutes !is SeedRule.At || minutes.value > 0) { "seed minutes must be > 0: $minutes" }
}

/**
 * What a torrent job starts with: its metadata if known, a priority per file (empty: all at the default), the order, and
 * its own seeding limits (TOR-05).
 */
class NewTorrent(
    val metadata: ByteArray? = null,
    val priorities: List<Int> = emptyList(),
    val sequential: Boolean = false,
    val seedRatio: SeedRule<Double> = SeedRule.Inherit,
    val seedMinutes: SeedRule<Int> = SeedRule.Inherit,
) {
    init {
        require(priorities.all { it in 0..MAX_PRIORITY }) { "file priorities are 0 to $MAX_PRIORITY: $priorities" }
        requireSeedRules(seedRatio, seedMinutes)
    }
}

/**
 * A torrent job's stored options (TOR-02, TOR-03, TOR-05, TOR-06). [priorities]: one per file in torrent order, 0 skips
 * it; empty means all. [resume]: libtorrent's resume data, null until some is saved.
 */
class TorrentRow(
    val downloadId: DownloadId,
    val metadata: ByteArray?,
    val priorities: List<Int>,
    val sequential: Boolean,
    val resume: ByteArray? = null,
    val seedRatio: SeedRule<Double> = SeedRule.Inherit,
    val seedMinutes: SeedRule<Int> = SeedRule.Inherit,
    /** Bytes done of each file in torrent order, as of the last save; empty when unknown (TOR-07). */
    val fileDone: List<Long> = emptyList(),
)

interface TorrentStore {
    suspend fun get(id: DownloadId): TorrentRow?

    /** False if the job has no torrent row. */
    suspend fun setMetadata(
        id: DownloadId,
        bytes: ByteArray,
    ): Boolean

    /** Each priority 0 to 7, or [IllegalArgumentException]. False if the job has no torrent row. */
    suspend fun setPriorities(
        id: DownloadId,
        priorities: List<Int>,
    ): Boolean

    suspend fun setSequential(
        id: DownloadId,
        on: Boolean,
    ): Boolean

    /** Saves libtorrent's resume data; null clears it. False if the job has no torrent row. */
    suspend fun setResume(
        id: DownloadId,
        bytes: ByteArray?,
    ): Boolean

    /** Saves the bytes done of each file; null clears them. False if the job has no torrent row. */
    suspend fun setFileDone(
        id: DownloadId,
        done: List<Long>?,
    ): Boolean

    /** The torrent's own seeding limits; an [SeedRule.At] has to be above 0, or [IllegalArgumentException]. False if the job has no torrent row. */
    suspend fun setSeedRules(
        id: DownloadId,
        ratio: SeedRule<Double>,
        minutes: SeedRule<Int>,
    ): Boolean
}
