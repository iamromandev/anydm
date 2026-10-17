package dev.anydm.engine

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.File
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.Segment

/**
 * One job as a UI shows it (FND-11): its stored row, what it is doing now, and everything stored about it.
 *
 * - [downloaded]: while it runs, the latest progress (published at most every 200 ms); otherwise what its checkpoints
 *   hold. [total]: the run's planned total when known, else [Download.size].
 * - [speed]: bytes per second over the last second while it runs, else 0. [speedSamples]: the last 60 one-second
 *   samples, oldest first, kept after it stops; empty if it never ran in this engine.
 * - [lastFailure]: the latest ended Attempt that has an error class or a message.
 * - [attempts] oldest first, [mirrors] by position, [files] by index with their segments as of the last checkpoint.
 */
data class DownloadSnapshot(
    val download: Download,
    val downloaded: Long,
    val total: Long?,
    val speed: Long,
    val speedSamples: List<Long>,
    val lastFailure: Attempt?,
    val attempts: List<Attempt>,
    val mirrors: List<Mirror>,
    val files: List<FileSnapshot>,
)

/** A file of a job with its segments, as of the last checkpoint. */
data class FileSnapshot(
    val file: File,
    val segments: List<Segment>,
) {
    val written: Long get() = segments.sumOf { it.written }
}

/** A speed now and the last 60 one-second samples, oldest first. */
data class SpeedWindow(
    val current: Long,
    val samples: List<Long>,
) {
    companion object {
        val Empty = SpeedWindow(0, emptyList())

        /** How many one-second samples a window keeps. */
        const val SIZE = 60
    }
}
