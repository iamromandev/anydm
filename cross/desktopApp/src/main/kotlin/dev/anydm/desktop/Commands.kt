package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.ErrorClass
import java.nio.file.Path

/** What the user can do to the selected jobs; [verb] finishes "could not be …" in a refusal. */
enum class JobCommand(
    val label: String,
    val verb: String,
    val tag: String,
) {
    PAUSE("Pause", "paused", "pause"),
    RESUME("Resume", "resumed", "resume"),
    CANCEL("Cancel", "cancelled", "cancel"),
    RETRY("Retry", "retried", "retry"),
    DOWNLOAD_ANYWAY("Download anyway", "retried", "anyway"),
    RELINK("Relink…", "relinked", "relink"),
    RAISE("Raise priority", "reprioritized", "raise"),
    LOWER("Lower priority", "reprioritized", "lower"),
    STOP_SEEDING("Stop seeding", "stopped seeding", "stop-seeding"),
    OPEN("Open", "opened", "open"),
    REVEAL("Show in folder", "shown", "reveal"),
    COPY_PATH("Copy path", "copied", "copy-path"),
    REMOVE("Remove…", "removed", "remove"),
}

private val Finished = setOf(COMPLETED, SEEDING, CANCELLED, FAILED)

/** Whether [command] can be given to the job [s] is now. */
internal fun JobCommand.appliesTo(s: DownloadSnapshot): Boolean {
    val status: DownloadStatus = s.download.status
    return when (this) {
        JobCommand.PAUSE -> status == PENDING || status == QUEUED || status == DOWNLOADING
        JobCommand.RESUME -> status == PAUSED
        JobCommand.CANCEL -> status !in Finished
        JobCommand.RETRY -> status == FAILED
        JobCommand.DOWNLOAD_ANYWAY -> status == FAILED && s.lastFailure?.errorClass == ErrorClass.DUPLICATE
        JobCommand.RELINK -> status == FAILED && s.lastFailure?.errorClass in RelinkClasses && s.mirrors.isNotEmpty()
        JobCommand.RAISE, JobCommand.LOWER -> status !in Finished
        JobCommand.STOP_SEEDING -> status == SEEDING
        JobCommand.OPEN, JobCommand.REVEAL, JobCommand.COPY_PATH -> (status == COMPLETED || status == SEEDING) && placedFile(s) != null
        JobCommand.REMOVE -> true
    }
}

private val RelinkClasses = setOf(ErrorClass.GONE, ErrorClass.AUTH)

/** The commands that take one job at a time with a dialog or a file; the rest run for every selected job. */
internal val SingleJob = setOf(JobCommand.RELINK, JobCommand.OPEN, JobCommand.REVEAL, JobCommand.COPY_PATH)

/** Where the job's first finished file is, or null while none is placed. */
internal fun placedFile(s: DownloadSnapshot): Path? {
    val folder = s.download.saveFolder ?: return null
    val placed = s.files.firstNotNullOfOrNull { it.file.placedPath } ?: return null
    // A name this OS can't take as a path has no file to act on.
    return runCatching { Path.of(folder).resolve(placed) }.getOrNull()
}
