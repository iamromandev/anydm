package dev.anydm.engine.files

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DuplicateCheck
import dev.anydm.engine.core.DuplicateFound
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.store.DownloadStore
import dev.anydm.engine.store.NewDownload
import kotlinx.io.files.Path

/**
 * The real [DuplicateCheck] (FIL-05, FIL-06). A cancelled download is gone for this purpose; every other one counts,
 * finished or not. The file-on-disk check only runs on a job's first prepare: after that its own placed file would
 * look like a duplicate of itself.
 */
class FilesDuplicateCheck(
    private val downloads: DownloadStore,
    private val fileSystem: FileSystem,
) : DuplicateCheck {
    override suspend fun atAdd(new: NewDownload): DuplicateFound? {
        if (new.allowDuplicate || new.mirrors.isEmpty()) return null
        val live = live().map { it.id }.toSet()
        val match =
            downloads.allMirrors().firstOrNull { mirror ->
                mirror.downloadId in live && new.mirrors.any { DuplicateRules.sameUrl(it, mirror.url) }
            }
        return match?.let { DuplicateFound(it.downloadId, DuplicateReason.SAME_URL) }
    }

    override suspend fun afterProbe(
        download: Download,
        probe: ProbeResult,
        target: Path,
    ): DuplicateFound? {
        if (download.allowDuplicate) return null
        val sameFile =
            live().firstOrNull { other ->
                other.id != download.id &&
                    other.fileName != null &&
                    DuplicateRules.sameFile(target.name, probe.size, probe.validator, other.fileName, other.size, other.sourceValidator)
            }
        if (sameFile != null) return DuplicateFound(sameFile.id, DuplicateReason.SAME_FILE)
        val firstPrepare = download.size == null && download.sourceValidator == null
        if (firstPrepare && probe.size != null) {
            val onDisk = fileSystem.size(target)
            if (onDisk is FileResult.Ok && onDisk.value == probe.size) return DuplicateFound(null, DuplicateReason.ON_DISK)
        }
        return null
    }

    private suspend fun live(): List<Download> = downloads.list().filter { it.status != DownloadStatus.CANCELLED }
}
