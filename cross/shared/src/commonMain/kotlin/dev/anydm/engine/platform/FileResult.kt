package dev.anydm.engine.platform

import kotlinx.io.files.Path

/**
 * The outcome of a [FileSystem] call: [Ok], or a [Failed] case the caller branches on. A failure
 * names the [Failed.path] it is about and keeps the OS's words in [Failed.detail] for an Attempt
 * message or a log line. Bugs, such as a relative path, throw instead.
 *
 * Callers map these: [DiskFull] is ErrorClass.DISK_FULL (#551, #553); [TrashUnavailable] keeps
 * the files and tells the user (#552); [CrossVolume] is copied and verified instead (#539);
 * [Busy] is retried briefly (#554); [InvalidName] means the name must be sanitised (FIL-03, #539; until then the step fails with the reason).
 */
sealed interface FileResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : FileResult<T>

    sealed interface Failed : FileResult<Nothing> {
        val path: Path
        val detail: String?
    }

    /** [path] doesn't exist, or the folder it should be in doesn't. */
    data class NotFound(
        override val path: Path,
        override val detail: String? = null,
    ) : Failed

    /** Something is in the way at [path]: an existing file, a file where a folder should be, or the reverse. */
    data class AlreadyExists(
        override val path: Path,
        override val detail: String? = null,
    ) : Failed

    /** The OS refused: no permission, or a read-only volume. */
    data class AccessDenied(
        override val path: Path,
        override val detail: String? = null,
    ) : Failed

    /** No room left on the volume holding [path]. */
    data class DiskFull(
        override val path: Path,
        override val detail: String? = null,
    ) : Failed

    /** [path] can't become [target] in one step: another volume, or no atomic rename there. */
    data class CrossVolume(
        override val path: Path,
        val target: Path,
        override val detail: String? = null,
    ) : Failed

    /** This OS or session has no trash; [path] is untouched. */
    data class TrashUnavailable(
        override val path: Path,
        override val detail: String? = null,
    ) : Failed

    /**
     * The OS can't make a file or folder with the name in [path] (a NUL character; on Windows also
     * `?`, `:` or a trailing space or dot). Nothing was touched. The caller must sanitise the name (FIL-03, #539).
     */
    data class InvalidName(
        override val path: Path,
        override val detail: String? = null,
    ) : Failed

    /**
     * Another process holds [path] open (Windows sharing or lock violations, a busy file on Unix).
     * It usually passes: retry briefly (#554).
     */
    data class Busy(
        override val path: Path,
        override val detail: String? = null,
    ) : Failed

    /** Any other I/O failure. */
    data class IoError(
        override val path: Path,
        override val detail: String? = null,
    ) : Failed
}
