package dev.anydm.engine.files

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DuplicateCheck
import dev.anydm.engine.core.DuplicateFound
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.http.cleanUrl
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.store.DownloadStore
import dev.anydm.engine.store.HistoryStore
import dev.anydm.engine.store.NewDownload
import kotlinx.io.files.Path

/**
 * The real [DuplicateCheck] (FIL-05, FIL-06). A cancelled download is gone for this purpose; every other one counts,
 * finished or not, and so does everything in the history. Every check runs only on a job's first prepare (it has no file rows yet): after that its own
 * file, or the job it was added anyway beside, would make it a duplicate.
 */
class FilesDuplicateCheck(
    private val downloads: DownloadStore,
    private val history: HistoryStore,
    private val fileSystem: FileSystem,
) : DuplicateCheck {
    override suspend fun atAdd(new: NewDownload): DuplicateFound? {
        if (new.allowDuplicate || new.mirrors.isEmpty()) return null
        val live = live().map { it.id }.toSet()
        val match =
            downloads.allMirrors().firstOrNull { mirror ->
                mirror.downloadId in live && new.mirrors.any { DuplicateRules.sameUrl(it, mirror.url) }
            }
        if (match != null) return DuplicateFound(match.downloadId, DuplicateReason.SAME_URL)
        val finished = new.mirrors.firstNotNullOfOrNull { history.findByUrl(cleanUrl(it)) }
        return finished?.let { DuplicateFound(null, DuplicateReason.IN_HISTORY) }
    }

    override suspend fun atAddAll(news: List<NewDownload>): List<DuplicateFound?> {
        if (news.none { !it.allowDuplicate && it.mirrors.isNotEmpty() }) return news.map { null }
        // The live jobs' links by their cleaned form, first one wins, as atAdd's first match does: read once for every link.
        val live = live().map { it.id }.toSet()
        val byUrl = HashMap<String, Mirror>()
        for (mirror in downloads.allMirrors()) if (mirror.downloadId in live) byUrl.putIfAbsent(cleanUrl(mirror.url), mirror)
        return news.map { new ->
            if (new.allowDuplicate || new.mirrors.isEmpty()) {
                null
            } else {
                val match = new.mirrors.mapNotNull { byUrl[cleanUrl(it)] }.firstOrNull()
                if (match != null) {
                    DuplicateFound(match.downloadId, DuplicateReason.SAME_URL)
                } else {
                    new.mirrors
                        .firstNotNullOfOrNull {
                            history.findByUrl(
                                cleanUrl(it),
                            )
                        }?.let { DuplicateFound(null, DuplicateReason.IN_HISTORY) }
                }
            }
        }
    }

    override suspend fun afterProbe(
        download: Download,
        probe: ProbeResult,
        target: Path,
    ): DuplicateFound? {
        // A job prepared before has file rows; its own earlier work must not make it a duplicate of itself.
        if (download.allowDuplicate || downloads.files(download.id).isNotEmpty()) return null
        val sameFile =
            live().firstOrNull { other ->
                other.id != download.id &&
                    other.fileName != null &&
                    DuplicateRules.sameFile(target.name, probe.size, probe.validator, other.fileName, other.size, other.sourceValidator)
            }
        if (sameFile != null) return DuplicateFound(sameFile.id, DuplicateReason.SAME_FILE)
        val size = probe.size
        if (size != null && history.findByFile(target.name, size) != null) return DuplicateFound(null, DuplicateReason.IN_HISTORY)
        if (probe.size != null) {
            val onDisk = fileSystem.size(target)
            if (onDisk is FileResult.Ok && onDisk.value == probe.size) return DuplicateFound(null, DuplicateReason.ON_DISK)
        }
        return null
    }

    private suspend fun live(): List<Download> = downloads.list().filter { it.status != DownloadStatus.CANCELLED }
}
