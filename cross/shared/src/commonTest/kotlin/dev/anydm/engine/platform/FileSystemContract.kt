package dev.anydm.engine.platform

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail

/** What every [FileSystem] must do, each test on a fresh temporary folder. Implementations' tests extend this. */
abstract class FileSystemContract {
    /** The implementation under test; its trash moves items into [trash], or it has none when null. */
    protected abstract fun fileSystem(trash: Path?): FileSystem

    protected val root: Path = newTempFolder("anydm-fs-test-")
    private val trash: Path = Path(root, "trash").also { SystemFileSystem.createDirectories(it) }

    @AfterTest
    fun deleteRoot() = deleteRecursively(root)

    @Test
    fun sizeOfAFileIsItsLength() =
        runTest {
            val file = Path(root, "a.bin").also { writeText(it, "12345") }
            fileSystem(trash).size(file) shouldBe FileResult.Ok(5L)
        }

    @Test
    fun sizeOfAMissingFileIsNotFound() =
        runTest {
            val missing = Path(root, "missing.bin")
            fileSystem(trash).size(missing) shouldBe FileResult.NotFound(missing)
        }

    @Test
    fun readTakesBytesFromAnOffset() =
        runTest {
            val file = Path(root, "a.bin").also { writeText(it, "0123456789") }
            fileSystem(trash).read(file, 3, 4).value() shouldBe "3456".encodeToByteArray()
        }

    @Test
    fun readPastTheEndIsShort() =
        runTest {
            val file = Path(root, "a.bin").also { writeText(it, "0123456789") }
            fileSystem(trash).read(file, 8, 10).value() shouldBe "89".encodeToByteArray()
            fileSystem(trash).read(file, 10, 5).value() shouldBe ByteArray(0)
        }

    @Test
    fun readOfAMissingFileIsNotFound() =
        runTest {
            val missing = Path(root, "missing.bin")
            fileSystem(trash).read(missing, 0, 1) shouldBe FileResult.NotFound(missing)
        }

    @Test
    fun readOfAFolderIsAFolderInTheWay() =
        runTest {
            fileSystem(trash).read(trash, 0, 1).shouldBeInstanceOf<FileResult.AlreadyExists>().path shouldBe trash
        }

    @Test
    fun sizeOfAFolderIsAFolderInTheWay() =
        runTest {
            fileSystem(trash).size(trash).shouldBeInstanceOf<FileResult.AlreadyExists>().path shouldBe trash
        }

    @Test
    fun prepareFolderCreatesMissingParents() =
        runTest {
            val folder = Path(root, "a", "b", "c")
            fileSystem(trash).prepareFolder(folder) shouldBe FileResult.Ok(Unit)
            isFolder(folder) shouldBe true
            namesIn(folder).shouldBeEmpty() // the write check leaves nothing behind
        }

    @Test
    fun prepareFolderLeavesAnExistingFolderAsItIs() =
        runTest {
            val folder = Path(root, "downloads").also { SystemFileSystem.createDirectories(it) }
            writeText(Path(folder, "keep.txt"), "keep")
            fileSystem(trash).prepareFolder(folder) shouldBe FileResult.Ok(Unit)
            namesIn(folder) shouldBe setOf("keep.txt")
        }

    @Test
    fun prepareFolderRefusesAFile() =
        runTest {
            val file = Path(root, "file.txt").also { writeText(it, "x") }
            fileSystem(trash).prepareFolder(file).shouldBeInstanceOf<FileResult.AlreadyExists>().path shouldBe file
        }

    @Test
    fun preallocateCreatesAFileOfTheExactSize() =
        runTest {
            val file = Path(root, "video.mp4.anydm")
            fileSystem(trash).preallocate(file, 1024L * 1024 + 3) shouldBe FileResult.Ok(Unit)
            sizeOf(file) shouldBe 1024L * 1024 + 3
        }

    @Test
    fun preallocateOfZeroBytesCreatesAnEmptyFile() =
        runTest {
            val file = Path(root, "empty.anydm")
            fileSystem(trash).preallocate(file, 0) shouldBe FileResult.Ok(Unit)
            sizeOf(file) shouldBe 0L
        }

    @Test
    fun preallocateNeverTouchesAnExistingFile() =
        runTest {
            val file = Path(root, "video.mp4.anydm").also { writeText(it, "partial bytes") }
            fileSystem(trash).preallocate(file, 1_000).shouldBeInstanceOf<FileResult.AlreadyExists>().path shouldBe file
            readText(file) shouldBe "partial bytes"
        }

    @Test
    fun preallocateNeedsTheFolderToExist() =
        runTest {
            fileSystem(trash).preallocate(Path(root, "missing", "v.anydm"), 1_000).shouldBeInstanceOf<FileResult.NotFound>()
            exists(Path(root, "missing")) shouldBe false
        }

    @Test
    fun preallocateRejectsANegativeSize() =
        runTest {
            shouldThrow<IllegalArgumentException> { fileSystem(trash).preallocate(Path(root, "x"), -1) }
        }

    @Test
    fun aFileWhereAFolderShouldBeIsInTheWay() =
        runTest {
            val file = Path(root, "file.txt").also { writeText(it, "x") }
            val before = namesIn(root)
            val fs = fileSystem(trash)
            // Windows reports the path as missing, POSIX as a file in the way.
            listOf(fs.prepareFolder(Path(file, "sub")), fs.preallocate(Path(file, "v"), 10)).forEach { result ->
                (result is FileResult.AlreadyExists || result is FileResult.NotFound) shouldBe true
            }
            readText(file) shouldBe "x"
            namesIn(root) shouldBe before
        }

    @Test
    fun aNameTheOsCantMakeIsReportedNotThrown() =
        runTest {
            val fs = fileSystem(trash)
            val bad = Path(root, "a\u0000b")
            val badInFolder = Path(bad, "sub")
            val good = Path(root, "good.txt").also { writeText(it, "x") }
            val before = namesIn(root)
            fs.prepareFolder(bad).shouldBeInstanceOf<FileResult.InvalidName>().path shouldBe bad
            fs.prepareFolder(badInFolder).shouldBeInstanceOf<FileResult.InvalidName>()
            fs.preallocate(bad, 10).shouldBeInstanceOf<FileResult.InvalidName>().path shouldBe bad
            fs.freeSpace(bad).shouldBeInstanceOf<FileResult.InvalidName>().path shouldBe bad
            fs.atomicMove(bad, Path(root, "x")).shouldBeInstanceOf<FileResult.InvalidName>().path shouldBe bad
            fs.atomicMove(good, bad).shouldBeInstanceOf<FileResult.InvalidName>().path shouldBe bad
            fs.moveToTrash(bad).shouldBeInstanceOf<FileResult.InvalidName>().path shouldBe bad
            fs.openWriter(bad).shouldBeInstanceOf<FileResult.InvalidName>().path shouldBe bad
            fs.delete(bad).shouldBeInstanceOf<FileResult.InvalidName>().path shouldBe bad
            readText(good) shouldBe "x"
            namesIn(root) shouldBe before
            namesIn(trash).shouldBeEmpty()
        }

    @Test
    fun freeSpaceMeasuresTheVolume() = runTest { fileSystem(trash).freeSpace(root).value() shouldBeGreaterThan 0L }

    @Test
    fun freeSpaceOfAMissingPathMeasuresItsNearestFolder() =
        runTest {
            val fs = fileSystem(trash)
            val here = fs.freeSpace(root).value()
            val there = fs.freeSpace(Path(root, "a", "b", "video.mp4")).value()
            abs(here - there) shouldBeLessThan 256L * 1024 * 1024 // other writers share the disk
            exists(Path(root, "a")) shouldBe false
        }

    @Test
    fun atomicMoveRenamesAFile() =
        runTest {
            val source = Path(root, "video.mp4.anydm").also { writeText(it, "new") }
            val target = Path(root, "video.mp4")
            fileSystem(trash).atomicMove(source, target) shouldBe FileResult.Ok(Unit)
            readText(target) shouldBe "new"
            exists(source) shouldBe false
        }

    @Test
    fun atomicMoveReplacesAnExistingTarget() =
        runTest {
            val source = Path(root, "video.mp4.anydm").also { writeText(it, "new") }
            val target = Path(root, "video.mp4").also { writeText(it, "old") }
            fileSystem(trash).atomicMove(source, target) shouldBe FileResult.Ok(Unit)
            readText(target) shouldBe "new"
            exists(source) shouldBe false
        }

    @Test
    fun atomicMoveOfAMissingSourceChangesNothing() =
        runTest {
            val source = Path(root, "gone.anydm")
            val target = Path(root, "video.mp4").also { writeText(it, "old") }
            fileSystem(trash).atomicMove(source, target).shouldBeInstanceOf<FileResult.NotFound>().path shouldBe source
            readText(target) shouldBe "old"
        }

    @Test
    fun atomicMoveNeedsTheTargetFolder() =
        runTest {
            val source = Path(root, "video.mp4.anydm").also { writeText(it, "new") }
            val folder = Path(root, "missing")
            fileSystem(trash)
                .atomicMove(source, Path(folder, "video.mp4"))
                .shouldBeInstanceOf<FileResult.NotFound>()
                .path shouldBe folder
            readText(source) shouldBe "new"
        }

    @Test
    fun atomicMoveWontReplaceAFolder() =
        runTest {
            val source = Path(root, "video.mp4.anydm").also { writeText(it, "new") }
            val target = Path(root, "video.mp4").also { SystemFileSystem.createDirectories(it) }
            fileSystem(trash).atomicMove(source, target).shouldBeInstanceOf<FileResult.AlreadyExists>().path shouldBe target
            readText(source) shouldBe "new"
            isFolder(target) shouldBe true
        }

    @Test
    fun moveToTrashTakesAFileOutOfItsFolder() =
        runTest {
            val file = Path(root, "video.mp4").also { writeText(it, "bytes") }
            fileSystem(trash).moveToTrash(file) shouldBe FileResult.Ok(Unit)
            exists(file) shouldBe false
            readText(Path(trash, "video.mp4")) shouldBe "bytes"
        }

    @Test
    fun moveToTrashTakesAFolderWithItsContents() =
        runTest {
            val folder = Path(root, "album").also { SystemFileSystem.createDirectories(it) }
            writeText(Path(folder, "1.mp3"), "a")
            fileSystem(trash).moveToTrash(folder) shouldBe FileResult.Ok(Unit)
            exists(folder) shouldBe false
            readText(Path(trash, "album", "1.mp3")) shouldBe "a"
        }

    @Test
    fun moveToTrashOfAMissingPathTouchesNothing() =
        runTest {
            fileSystem(trash).moveToTrash(Path(root, "gone.mp4")).shouldBeInstanceOf<FileResult.NotFound>()
            namesIn(trash).shouldBeEmpty()
        }

    @Test
    fun moveToTrashWithoutATrashKeepsTheFile() =
        runTest {
            val file = Path(root, "video.mp4").also { writeText(it, "bytes") }
            fileSystem(trash = null).moveToTrash(file).shouldBeInstanceOf<FileResult.TrashUnavailable>().path shouldBe file
            readText(file) shouldBe "bytes"
        }

    @Test
    fun aWriterWritesAtAnyOffset() =
        runTest {
            val fs = fileSystem(trash)
            val file = Path(root, "video.mp4.anydm")
            fs.preallocate(file, 10) shouldBe FileResult.Ok(Unit)
            fs.openWriter(file).value().use { writer ->
                writer.write(7, "abc".encodeToByteArray()) shouldBe FileResult.Ok(Unit)
                writer.write(0, "..xy..".encodeToByteArray(), startIndex = 2, endIndex = 4) shouldBe FileResult.Ok(Unit)
            }
            readBytes(file) shouldBe "xy".encodeToByteArray() + ByteArray(5) + "abc".encodeToByteArray()
        }

    @Test
    fun aWriterTakesParallelWritesToSeparateRanges() =
        runTest {
            val fs = fileSystem(trash)
            val file = Path(root, "video.mp4.anydm")
            val chunk = 4096
            fs.preallocate(file, 16L * chunk) shouldBe FileResult.Ok(Unit)
            fs.openWriter(file).value().use { writer ->
                val results =
                    withContext(Dispatchers.Default) {
                        (0 until 16)
                            .map { i -> async { writer.write(i.toLong() * chunk, ByteArray(chunk) { i.toByte() }) } }
                            .awaitAll()
                    }
                results.all { it == FileResult.Ok(Unit) } shouldBe true
            }
            readBytes(file) shouldBe ByteArray(16 * chunk) { (it / chunk).toByte() }
        }

    @Test
    fun aWriteBeyondTheEndGrowsTheFile() =
        runTest {
            val fs = fileSystem(trash)
            val file = Path(root, "grow.anydm")
            fs.preallocate(file, 0) shouldBe FileResult.Ok(Unit)
            fs.openWriter(file).value().use { it.write(4, "z".encodeToByteArray()) shouldBe FileResult.Ok(Unit) }
            sizeOf(file) shouldBe 5L
        }

    @Test
    fun openWriterNeverCreatesAFile() =
        runTest {
            val file = Path(root, "missing.anydm")
            fileSystem(trash).openWriter(file).shouldBeInstanceOf<FileResult.NotFound>().path shouldBe file
            exists(file) shouldBe false
        }

    @Test
    fun openWriterRefusesAFolder() =
        runTest {
            val folder = Path(root, "album").also { SystemFileSystem.createDirectories(it) }
            fileSystem(trash).openWriter(folder).shouldBeInstanceOf<FileResult.AlreadyExists>().path shouldBe folder
        }

    @Test
    fun aClosedWriterThrows() =
        runTest {
            val fs = fileSystem(trash)
            val file = Path(root, "video.mp4.anydm")
            fs.preallocate(file, 10) shouldBe FileResult.Ok(Unit)
            val writer = fs.openWriter(file).value()
            writer.close()
            shouldThrow<IllegalStateException> { writer.write(0, ByteArray(1)) }
            shouldThrow<IllegalStateException> { writer.size() }
            shouldThrow<IllegalStateException> { writer.sync() }
        }

    @Test
    fun aWriterReportsTheLengthOfItsFile() =
        runTest {
            val fs = fileSystem(trash)
            val file = Path(root, "video.mp4.anydm")
            fs.preallocate(file, 10) shouldBe FileResult.Ok(Unit)
            fs.openWriter(file).value().use { writer ->
                writer.size() shouldBe FileResult.Ok(10L)
                writer.write(20, ByteArray(1)) shouldBe FileResult.Ok(Unit)
                writer.size() shouldBe FileResult.Ok(21L)
            }
        }

    @Test
    fun aWriterSyncsWhatItWrote() =
        runTest {
            val fs = fileSystem(trash)
            val file = Path(root, "video.mp4.anydm")
            fs.preallocate(file, 4) shouldBe FileResult.Ok(Unit)
            fs.openWriter(file).value().use { writer ->
                writer.write(0, "abcd".encodeToByteArray()) shouldBe FileResult.Ok(Unit)
                writer.sync() shouldBe FileResult.Ok(Unit)
            }
            readText(file) shouldBe "abcd"
        }

    @Test
    fun atomicMoveWithoutReplaceLeavesAnExistingTargetAlone() =
        runTest {
            val source = Path(root, "video.mp4.anydm").also { writeText(it, "new") }
            val target = Path(root, "video.mp4").also { writeText(it, "old") }
            fileSystem(trash).atomicMove(source, target, replace = false).shouldBeInstanceOf<FileResult.AlreadyExists>().path shouldBe
                target
            readText(target) shouldBe "old"
            readText(source) shouldBe "new"
        }

    @Test
    fun atomicMoveWithoutReplaceStillMovesToAFreeName() =
        runTest {
            val source = Path(root, "video.mp4.anydm").also { writeText(it, "new") }
            val target = Path(root, "video.mp4")
            fileSystem(trash).atomicMove(source, target, replace = false) shouldBe FileResult.Ok(Unit)
            readText(target) shouldBe "new"
            exists(source) shouldBe false
        }

    @Test
    fun deleteRemovesAFile() =
        runTest {
            val file = Path(root, "video.mp4.anydm").also { writeText(it, "stale") }
            fileSystem(trash).delete(file) shouldBe FileResult.Ok(Unit)
            exists(file) shouldBe false
        }

    @Test
    fun deleteOfAMissingFileIsNotFound() =
        runTest {
            val file = Path(root, "gone.anydm")
            fileSystem(trash).delete(file).shouldBeInstanceOf<FileResult.NotFound>().path shouldBe file
        }

    @Test
    fun deleteWontRemoveAFolder() =
        runTest {
            val folder = Path(root, "album").also { SystemFileSystem.createDirectories(it) }
            fileSystem(trash).delete(folder).shouldBeInstanceOf<FileResult.AlreadyExists>().path shouldBe folder
            isFolder(folder) shouldBe true
        }

    @Test
    fun everyCallRejectsARelativePath() =
        runTest {
            val fs = fileSystem(trash)
            val relative = Path("downloads", "video.mp4")
            shouldThrow<IllegalArgumentException> { fs.prepareFolder(relative) }
            shouldThrow<IllegalArgumentException> { fs.preallocate(relative, 1) }
            shouldThrow<IllegalArgumentException> { fs.freeSpace(relative) }
            shouldThrow<IllegalArgumentException> { fs.atomicMove(relative, Path(root, "x")) }
            shouldThrow<IllegalArgumentException> { fs.atomicMove(Path(root, "x"), relative) }
            shouldThrow<IllegalArgumentException> { fs.moveToTrash(relative) }
            shouldThrow<IllegalArgumentException> { fs.openWriter(relative) }
            shouldThrow<IllegalArgumentException> { fs.delete(relative) }
        }
}

private fun <T> FileResult<T>.value(): T =
    when (this) {
        is FileResult.Ok -> value
        is FileResult.Failed -> fail("expected Ok, got $this")
    }
