// Import the java.nio.file exceptions explicitly: kotlin.io has same-named classes in its default imports.
package dev.anydm.engine.platform

import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path as NioPath

/** The same location as a java.nio path. */
internal fun Path.toNioPath(): NioPath = NioPath.of(toString())

/**
 * Sorts an I/O error into the case a caller branches on. Disk-full messages vary by OS and
 * language, so a caller that knows how many bytes it needed passes [outOfRoom] from a space check.
 */
internal fun IOException.toFailure(
    path: Path,
    target: Path? = null,
    outOfRoom: Boolean = false,
): FileResult.Failed {
    val detail = message
    val text = detail.orEmpty()
    return when {
        this is AtomicMoveNotSupportedException && target != null -> FileResult.CrossVolume(path, target, detail)

        // Before AccessDenied: Windows reports a sharing violation as access denied.
        busy.containsMatchIn(text) -> FileResult.Busy(path, detail)

        this is NoSuchFileException -> FileResult.NotFound(path, detail)

        this is FileAlreadyExistsException -> FileResult.AlreadyExists(path, detail)

        // A file where a folder should be (ENOTDIR); Windows says the path is missing instead.
        this is NotDirectoryException || notDirectory.containsMatchIn(text) -> FileResult.AlreadyExists(path, detail)

        this is AccessDeniedException -> FileResult.AccessDenied(path, detail)

        outOfRoom || diskFull.containsMatchIn(text) -> FileResult.DiskFull(path, detail)

        readOnly.containsMatchIn(text) -> FileResult.AccessDenied(path, detail)

        else -> FileResult.IoError(path, detail ?: this::class.simpleName)
    }
}

// strerror(ENOSPC/EDQUOT) on Linux and macOS; Windows' English ERROR_DISK_FULL and ERROR_HANDLE_DISK_FULL.
private val diskFull =
    Regex("No space left on device|Dis[ck] quota exceeded|not enough space on the disk|disk is full", RegexOption.IGNORE_CASE)

// Windows' ERROR_SHARING_VIOLATION and ERROR_LOCK_VIOLATION texts; strerror(EBUSY/ETXTBSY) on Linux and macOS.
private val busy =
    Regex(
        "being used by another process|locked a portion of the file|sharing violation|lock violation|" +
            "text file busy|resource busy",
        RegexOption.IGNORE_CASE,
    )

// strerror(ENOTDIR).
private val notDirectory = Regex("Not a directory", RegexOption.IGNORE_CASE)

// strerror(EROFS); Windows' ERROR_WRITE_PROTECT.
private val readOnly = Regex("Read-only file system|write.protected", RegexOption.IGNORE_CASE)
