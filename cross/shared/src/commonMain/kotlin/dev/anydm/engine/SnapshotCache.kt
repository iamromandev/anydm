package dev.anydm.engine

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.collectionStatus
import dev.anydm.engine.core.itemCounts
import dev.anydm.engine.torrent.TorrentLive
import dev.anydm.engine.torrent.TorrentSummary

/**
 * The engine's picture of every job, put together from what the queue reports. Not thread-safe: the engine calls it
 * from one coroutine at a time. A removed job is remembered, so a late report about it can't bring it back.
 */
internal class SnapshotCache {
    private class Entry(
        var download: Download,
    ) {
        val attempts = mutableMapOf<Long, Attempt>()
        var mirrors: List<Mirror> = emptyList()
        var files: List<FileSnapshot> = emptyList()
        var live: Pair<Long, Long?>? = null

        /** A torrent in the session (TOR-07): its summary and the bytes done of each file. */
        var torrent: TorrentSummary? = null
        var liveDone: List<Long>? = null
        var speed = 0L
        val samples = ArrayDeque<Long>()
    }

    private val entries = mutableMapOf<Long, Entry>()
    private val removed = mutableSetOf<DownloadId>()

    fun load(
        jobs: List<Download>,
        attempts: Map<DownloadId, List<Attempt>>,
        mirrors: Map<DownloadId, List<Mirror>>,
        files: Map<DownloadId, List<FileSnapshot>>,
    ) {
        for (job in jobs) {
            val entry = Entry(job)
            attempts[job.id].orEmpty().forEach { entry.attempts[it.id.value] = it }
            entry.mirrors = mirrors[job.id].orEmpty()
            entry.files = files[job.id].orEmpty()
            entries[job.id.value] = entry
        }
    }

    fun jobChanged(download: Download) {
        if (download.id in removed) return
        val entry = entries[download.id.value]
        if (entry == null) entries[download.id.value] = Entry(download) else entry.download = download
    }

    fun jobRemoved(id: DownloadId) {
        removed += id
        entries.remove(id.value)
    }

    fun attemptChanged(attempt: Attempt) {
        entries[attempt.downloadId.value]?.attempts?.put(attempt.id.value, attempt)
    }

    fun mirrorsChanged(
        id: DownloadId,
        mirrors: List<Mirror>,
    ) {
        entries[id.value]?.mirrors = mirrors
    }

    fun filesChanged(
        id: DownloadId,
        files: List<FileSnapshot>,
    ) {
        entries[id.value]?.files = files
    }

    fun progress(
        id: DownloadId,
        downloaded: Long,
        total: Long?,
    ) {
        entries[id.value]?.live = downloaded to total
    }

    /** One more one-second sample; the window keeps the last [SpeedWindow.SIZE]. */
    fun speed(
        id: DownloadId,
        sample: Long,
    ) {
        val entry = entries[id.value] ?: return
        entry.speed = sample
        entry.samples.addLast(sample)
        while (entry.samples.size > SpeedWindow.SIZE) entry.samples.removeFirst()
    }

    /** It no longer runs: no live progress, no speed; its window stays. */
    fun stoppedRunning(id: DownloadId) {
        val entry = entries[id.value] ?: return
        entry.live = null
        entry.speed = 0
    }

    /** The jobs that have a torrent summary now. */
    fun liveTorrentIds(): Set<DownloadId> =
        entries.values
            .filter { it.torrent != null }
            .map { it.download.id }
            .toSet()

    /**
     * The latest of a torrent in the session (its summary, and the bytes done of each file while it is there), or null
     * when it left it. True when the list would change.
     */
    fun torrentLive(
        id: DownloadId,
        live: TorrentLive?,
    ): Boolean {
        val entry = entries[id.value] ?: return false
        if (entry.torrent == live?.summary && entry.liveDone == live?.fileDone) return false
        entry.torrent = live?.summary
        entry.liveDone = live?.fileDone
        return true
    }

    /** The stored row of job [id] as last reported; null when it is not known (or was removed). */
    fun downloadOf(id: DownloadId): Download? = entries[id.value]?.download

    fun contains(id: DownloadId): Boolean = id.value in entries

    fun kindOf(id: DownloadId): DownloadKind? = entries[id.value]?.download?.kind

    /**
     * Every job's snapshot. A collection's is derived from its items (MED-16): their states give its status and counts,
     * and their bytes add up; the total is known only when every item's is.
     */
    fun list(): List<DownloadSnapshot> {
        val all = snapshots()
        val byParent = all.filter { it.download.parentId != null }.groupBy { it.download.parentId }
        return all.map { snapshot ->
            if (!snapshot.download.isCollection) {
                snapshot
            } else {
                val children = byParent[snapshot.download.id].orEmpty()
                val status = collectionStatus(children.map { it.download.status })
                snapshot.copy(
                    download =
                        snapshot.download.copy(
                            status = status,
                            pauseReasons = if (status == DownloadStatus.PAUSED) snapshot.download.pauseReasons else emptySet(),
                        ),
                    downloaded = children.sumOf { it.downloaded },
                    total = if (children.isEmpty() || children.any { it.total == null }) null else children.sumOf { it.total!! },
                    items = itemCounts(children.map { it.download.status }),
                )
            }
        }
    }

    private fun snapshots(): List<DownloadSnapshot> =
        entries.keys.sorted().map { key ->
            val entry = entries.getValue(key)
            val attempts = entry.attempts.values.sortedBy { it.id.value }
            // While a torrent is in the session its files show what it reports (when it names every file we hold).
            val done = entry.liveDone?.takeIf { it.size == entry.files.size }
            val files = if (done == null) entry.files else entry.files.mapIndexed { i, file -> file.copy(done = done[i]) }
            DownloadSnapshot(
                download = entry.download,
                // Only the files the job wants: a torrent's skipped files hold some bytes of the pieces they share.
                downloaded = entry.live?.first ?: files.filter { it.file.selected }.sumOf { it.written },
                total = entry.live?.second ?: entry.download.size,
                speed = entry.speed,
                speedSamples = entry.samples.toList(),
                lastFailure = attempts.lastOrNull { it.endedAt != null && (it.errorClass != null || it.message != null) },
                attempts = attempts,
                mirrors = entry.mirrors,
                files = files,
                torrent = entry.torrent,
            )
        }
}
