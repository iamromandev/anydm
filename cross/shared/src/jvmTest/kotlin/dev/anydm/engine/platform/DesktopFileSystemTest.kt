package dev.anydm.engine.platform

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import java.nio.file.Path as NioPath

class DesktopFileSystemTest : FileSystemContract() {
    override fun fileSystem(trash: Path?): FileSystem =
        DesktopFileSystem(
            Dispatchers.IO,
            trash?.let { FolderTrashBin(it.toNioPath()) } ?: NoTrashBin,
        )

    @Test
    fun aLargePreallocationIsSparse() {
        val store = Files.getFileStore(root.toNioPath())
        val size = 4L * 1024 * 1024 * 1024 + 1 // past 32-bit offsets
        assumeTrue("${store.type()} may not make sparse files", store.type().lowercase() in sparseFileSystems)
        assumeTrue("needs room even if the file isn't sparse", store.usableSpace > 2 * size)
        val before = store.usableSpace
        val file = Path(root, "big.anydm")
        runTest { fileSystem(trash = null).preallocate(file, size) shouldBe FileResult.Ok(Unit) }
        Files.size(file.toNioPath()) shouldBe size
        // Allocating it would take all 4 GiB; half leaves room for other writers on this disk.
        before - store.usableSpace shouldBeLessThan size / 2
    }

    // The caller being cancelled must not abort a rename or trash move that hasn't started: the
    // dispatcher here holds the work until after the cancel, which is the worst case.
    @Test
    fun aCancelledAtomicMoveStillHappens() =
        runTest {
            val fs = DesktopFileSystem(StandardTestDispatcher(testScheduler), NoTrashBin)
            val source = Path(root, "video.mp4.anydm").also { writeText(it, "new") }
            val target = Path(root, "video.mp4")
            val call = launch(start = CoroutineStart.UNDISPATCHED) { fs.atomicMove(source, target) }
            call.cancel()
            testScheduler.advanceUntilIdle()
            call.join()
            exists(source) shouldBe false
            readText(target) shouldBe "new"
        }

    @Test
    fun aCancelledDeleteStillHappens() =
        runTest {
            val fs = DesktopFileSystem(StandardTestDispatcher(testScheduler), NoTrashBin)
            val file = Path(root, "video.mp4.anydm").also { writeText(it, "stale") }
            val call = launch(start = CoroutineStart.UNDISPATCHED) { fs.delete(file) }
            call.cancel()
            testScheduler.advanceUntilIdle()
            call.join()
            exists(file) shouldBe false
        }

    // withContext drops the result of a call whose caller was cancelled meanwhile, so the channel the call opened would
    // never be closed. The dispatchers here hold the work: the block runs, and the cancel lands before the caller resumes.
    @Test
    fun aCancelledOpenWriterClosesTheChannelItOpened() {
        val io = QueueDispatcher()
        val caller = QueueDispatcher()
        val opened = mutableListOf<DesktopFileWriter>()
        val fs = DesktopFileSystem(io, NoTrashBin) { opened += it }
        val file = Path(root, "video.mp4.anydm").also { writeText(it, "x") }
        val scope = CoroutineScope(caller + Job())
        try {
            val call = scope.launch(start = CoroutineStart.UNDISPATCHED) { fs.openWriter(file) }
            io.runAll()
            opened.size shouldBe 1 // the channel is open and the result is on its way to the caller
            opened.single().isOpen shouldBe true
            call.cancel()
            caller.runAll()
            call.isCancelled shouldBe true
            opened.single().isOpen shouldBe false
        } finally {
            scope.cancel()
            opened.forEach { it.close() }
        }
    }

    @Test
    fun anUncancelledOpenWriterKeepsItsChannelOpen() {
        val io = QueueDispatcher()
        val caller = QueueDispatcher()
        val opened = mutableListOf<DesktopFileWriter>()
        val fs = DesktopFileSystem(io, NoTrashBin) { opened += it }
        val file = Path(root, "video.mp4.anydm").also { writeText(it, "x") }
        val scope = CoroutineScope(caller + Job())
        try {
            var result: FileResult<FileWriter>? = null
            val call = scope.launch(start = CoroutineStart.UNDISPATCHED) { result = fs.openWriter(file) }
            io.runAll()
            caller.runAll()
            call.isCompleted shouldBe true
            result.shouldBeInstanceOf<FileResult.Ok<FileWriter>>()
            opened.single().isOpen shouldBe true
        } finally {
            scope.cancel()
            opened.forEach { it.close() }
        }
    }

    @Test
    fun deleteOfASymlinkRemovesTheLinkNotItsTarget() {
        val target = Path(root, "real.bin").also { writeText(it, "keep") }
        val link = Path(root, "link.bin")
        try {
            Files.createSymbolicLink(link.toNioPath(), target.toNioPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue("symlinks aren't supported here", false)
        } catch (e: java.io.IOException) {
            assumeTrue("symlinks aren't allowed here: ${e.message}", false)
        }
        runTest { fileSystem(trash = null).delete(link) shouldBe FileResult.Ok(Unit) }
        Files.exists(link.toNioPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
        readText(target) shouldBe "keep"
    }

    @Test
    fun openWriterOnAReadOnlyFileIsAccessDenied() {
        val file = Path(root, "locked.anydm").also { writeText(it, "x") }
        val nio = file.toNioPath()
        assumeTrue("needs POSIX permissions", Files.getFileStore(nio).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("r--r--r--"))
        try {
            assumeFalse("permissions don't bind this user", Files.isWritable(nio))
            runTest { fileSystem(trash = null).openWriter(file).shouldBeInstanceOf<FileResult.AccessDenied>().path shouldBe file }
        } finally {
            Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun aCancelledMoveToTrashStillHappens() =
        runTest {
            val trash = Path(root, "trash")
            val fs = DesktopFileSystem(StandardTestDispatcher(testScheduler), FolderTrashBin(trash.toNioPath()))
            val file = Path(root, "video.mp4").also { writeText(it, "bytes") }
            val call = launch(start = CoroutineStart.UNDISPATCHED) { fs.moveToTrash(file) }
            call.cancel()
            testScheduler.advanceUntilIdle()
            call.join()
            exists(file) shouldBe false
            readText(Path(trash, "video.mp4")) shouldBe "bytes"
        }

    @Test
    fun prepareFolderReportsAFolderItCantWriteTo() {
        val folder = Path(root, "locked").also { SystemFileSystem.createDirectories(it) }
        val nio = folder.toNioPath()
        assumeTrue("needs POSIX permissions", Files.getFileStore(nio).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeFalse("permissions don't bind this user", Files.isWritable(nio))
            runTest { fileSystem(trash = null).prepareFolder(folder).shouldBeInstanceOf<FileResult.AccessDenied>() }
        } finally {
            Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun deleteTreeDoesNotFollowASymlinkOutOfTheTree() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val outside =
            Files
                .createDirectories(
                    root.toNioPath().resolve("outside"),
                ).also { Files.writeString(it.resolve("keep.txt"), "keep") }
        val tree = Files.createDirectories(root.toNioPath().resolve("tree"))
        Files.createSymbolicLink(tree.resolve("link"), outside)

        runTest { fileSystem(trash = null).deleteTree(Path(tree.toString())) shouldBe FileResult.Ok(Unit) }

        Files.exists(tree) shouldBe false
        Files.readString(outside.resolve("keep.txt")) shouldBe "keep"
    }
}

/** Holds every task until the test runs it, so a test decides what happens between a call's steps. */
private class QueueDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.addLast(block)
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}

private val sparseFileSystems = setOf("apfs", "btrfs", "ext4", "f2fs", "ntfs", "refs", "tmpfs", "xfs", "zfs")

/** A stand-in trash: a plain folder, so tests never touch the real one. */
private class FolderTrashBin(
    private val folder: NioPath,
) : TrashBin {
    override val isAvailable = true

    override fun moveToTrash(item: NioPath): Boolean {
        Files.move(item, folder.resolve(item.fileName))
        return true
    }
}

private object NoTrashBin : TrashBin {
    override val isAvailable = false

    override fun moveToTrash(item: NioPath): Boolean = error("no trash here")
}
