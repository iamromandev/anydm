package dev.anydm.engine.core

import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.store.NewDownload
import kotlinx.io.files.Path

/** Why a download counts as a duplicate (FIL-05). */
enum class DuplicateReason {
    /** Another download has the same link, once tracking parameters are gone. */
    SAME_URL,

    /** Another download has the same name and size, and the same ETag when both know one. */
    SAME_FILE,

    /** A finished download with that link, or that name and size, is in the history. */
    IN_HISTORY,

    /** A file with that name and size is already in the save folder. */
    ON_DISK,
}

/** What a duplicate matched: the other download, or null when it matched a file on disk. */
data class DuplicateFound(
    val existing: DownloadId?,
    val reason: DuplicateReason,
)

/**
 * Asked twice about the same job (FIL-05). [atAdd] sees only the links, before anything is stored; a hit stops the add
 * unless the caller said to add anyway (FIL-06). [afterProbe] sees the name and size the probe learned, which is what
 * the other checks need. A job added anyway skips both. Neither reports the job itself.
 */
interface DuplicateCheck {
    suspend fun atAdd(new: NewDownload): DuplicateFound?

    suspend fun afterProbe(
        download: Download,
        probe: ProbeResult,
        target: Path,
    ): DuplicateFound?

    data object None : DuplicateCheck {
        override suspend fun atAdd(new: NewDownload): DuplicateFound? = null

        override suspend fun afterProbe(
            download: Download,
            probe: ProbeResult,
            target: Path,
        ): DuplicateFound? = null
    }
}
