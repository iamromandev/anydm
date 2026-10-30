// Import the java.nio.file exceptions explicitly: kotlin.io has same-named classes in its default imports.
package dev.anydm.engine.platform

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import kotlin.test.Test

class FileFailuresTest {
    private val path = Path(SystemTemporaryDirectory, "anydm-a") // never touched
    private val target = Path(SystemTemporaryDirectory, "anydm-b")

    @Test
    fun ioErrorsBecomeTheFailuresCallersBranchOn() {
        listOf(
            NoSuchFileException("a") to FileResult.NotFound::class,
            FileAlreadyExistsException("a") to FileResult.AlreadyExists::class,
            AccessDeniedException("a") to FileResult.AccessDenied::class,
            FileSystemException("a", null, "No space left on device") to FileResult.DiskFull::class,
            FileSystemException("a", null, "Disc quota exceeded") to FileResult.DiskFull::class,
            IOException("There is not enough space on the disk") to FileResult.DiskFull::class,
            FileSystemException("a", null, "Read-only file system") to FileResult.AccessDenied::class,
            NotDirectoryException("a") to FileResult.AlreadyExists::class,
            FileSystemException("a", null, "Not a directory") to FileResult.AlreadyExists::class,
            FileSystemException("a", null, "NOT A DIRECTORY") to FileResult.AlreadyExists::class,
            AccessDeniedException("a", null, "Permission denied") to FileResult.AccessDenied::class,
            // A file in use by another process: Windows says so as access denied or a plain I/O error.
            AccessDeniedException("a", null, "The process cannot access the file because it is being used by another process") to
                FileResult.Busy::class,
            FileSystemException("a", null, "The process cannot access the file because it is being used by another process") to
                FileResult.Busy::class,
            FileSystemException("a", null, "The process cannot access the file because another process has locked a portion of the file") to
                FileResult.Busy::class,
            AccessDeniedException("a", null, "Sharing violation") to FileResult.Busy::class,
            FileSystemException("a", null, "Lock violation") to FileResult.Busy::class,
            FileSystemException("a", null, "Text file busy") to FileResult.Busy::class,
            FileSystemException("a", null, "Device or resource busy") to FileResult.Busy::class,
            FileSystemException("a", null, "Resource busy") to FileResult.Busy::class,
            IOException("IT IS BEING USED BY ANOTHER PROCESS") to FileResult.Busy::class,
            IOException("something else") to FileResult.IoError::class,
        ).forEach { (error, expected) -> withClue(error) { error.toFailure(path)::class shouldBe expected } }
    }

    @Test
    fun aFailedSpaceCheckMakesAnyErrorDiskFull() {
        IOException("Le disque est plein").toFailure(path, outOfRoom = true).shouldBeInstanceOf<FileResult.DiskFull>()
    }

    @Test
    fun aMoveThatCantBeAtomicIsCrossVolume() {
        AtomicMoveNotSupportedException("a", "b", "Invalid cross-device link")
            .toFailure(path, target)
            .shouldBeInstanceOf<FileResult.CrossVolume>()
            .target shouldBe target
    }
}
