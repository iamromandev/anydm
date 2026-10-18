package dev.anydm.engine.files

import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class CrossVolumeMoveTest {
    private val source = Path("/a/file.zip.1.anydm")
    private val target = Path("/b/file.zip")
    private val copy = Path("/b/file.zip.copy")
    private val content = ByteArray(2_500_000) { (it * 31).toByte() }

    private fun fs() =
        FakeFileSystem().also {
            it.put(source, content)
            it.prepareFolderNow(Path("/b"))
            it.volumeOf = { path -> if (path.toString().startsWith("/a")) "A" else "B" }
        }

    private fun FakeFileSystem.prepareFolderNow(folder: Path) =
        put(Path(folder, ".keep"), byteArrayOf()).also { remove(Path(folder, ".keep")) }

    /** [fake] with a hook on reading the copy, to play a disk that returns the wrong bytes or the wrong size. */
    private fun lying(
        fake: FakeFileSystem,
        size: Long? = null,
        flip: Boolean = false,
    ): FileSystem =
        object : FileSystem by fake {
            override suspend fun size(path: Path): FileResult<Long> =
                if (path == copy && size != null) FileResult.Ok(size) else fake.size(path)

            override suspend fun read(
                path: Path,
                offset: Long,
                length: Int,
            ): FileResult<ByteArray> {
                val read = fake.read(path, offset, length)
                if (path == copy && flip && offset == 0L && read is FileResult.Ok && read.value.isNotEmpty()) {
                    read.value[0] = (read.value[0] + 1).toByte()
                }
                return read
            }
        }

    @Test
    fun copiesVerifiesAndDeletesTheSource() =
        runTest {
            val fake = fs()
            moveAcrossVolumes(fake, source, target, replace = false) shouldBe FileResult.Ok(Unit)
            fake.read(target)?.contentEquals(content) shouldBe true
            fake.exists(source) shouldBe false
            fake.exists(copy) shouldBe false
        }

    @Test
    fun anEmptyFileMoves() =
        runTest {
            val fake = fs()
            fake.put(source, byteArrayOf())
            moveAcrossVolumes(fake, source, target, replace = false) shouldBe FileResult.Ok(Unit)
            fake.read(target)?.size shouldBe 0
        }

    @Test
    fun theSourceIsKeptWhenTheCopyIsTheWrongSize() =
        runTest {
            val fake = fs()
            moveAcrossVolumes(lying(fake, size = content.size + 1L), source, target, replace = false)
                .shouldBeInstanceOf<FileResult.IoError>()
            fake.exists(source) shouldBe true
            fake.exists(target) shouldBe false
            fake.exists(copy) shouldBe false
        }

    @Test
    fun theSourceIsKeptWhenTheCopyHasOtherBytes() =
        runTest {
            val fake = fs()
            moveAcrossVolumes(lying(fake, flip = true), source, target, replace = false).shouldBeInstanceOf<FileResult.IoError>()
            fake.exists(source) shouldBe true
            fake.exists(target) shouldBe false
            fake.exists(copy) shouldBe false
        }

    @Test
    fun aFailedWriteLeavesTheSourceAndNoCopy() =
        runTest {
            val fake = fs()
            fake.faults[FakeFileSystem.Op.WRITE] = { if (it == copy) FileResult.DiskFull(it) else null }
            moveAcrossVolumes(fake, source, target, replace = false).shouldBeInstanceOf<FileResult.DiskFull>()
            fake.exists(source) shouldBe true
            fake.exists(copy) shouldBe false
        }

    @Test
    fun aTakenTargetIsRefusedWithoutReplace() =
        runTest {
            val fake = fs()
            fake.put(target, byteArrayOf(9))
            moveAcrossVolumes(fake, source, target, replace = false).shouldBeInstanceOf<FileResult.AlreadyExists>()
            fake.read(target)?.toList() shouldBe listOf<Byte>(9)
            fake.exists(source) shouldBe true
        }

    @Test
    fun replaceOverwritesTheTarget() =
        runTest {
            val fake = fs()
            fake.put(target, byteArrayOf(9))
            moveAcrossVolumes(fake, source, target, replace = true) shouldBe FileResult.Ok(Unit)
            fake.read(target)?.contentEquals(content) shouldBe true
        }

    @Test
    fun aPowerCutBeforeTheFinalMoveLeavesTheSourceAndNoTarget() =
        runTest {
            val fake = fs()
            val cutting =
                object : FileSystem by fake {
                    override suspend fun atomicMove(
                        source: Path,
                        target: Path,
                        replace: Boolean,
                    ): FileResult<Unit> {
                        fake.powerCut()
                        return FileResult.IoError(source, "power cut")
                    }
                }
            moveAcrossVolumes(cutting, source, target, replace = false).shouldBeInstanceOf<FileResult.IoError>()
            fake.exists(source) shouldBe true
            fake.exists(target) shouldBe false
        }

    @Test
    fun aStaleCopyFromAnEarlierTryIsReplaced() =
        runTest {
            val fake = fs()
            fake.put(copy, byteArrayOf(1, 2, 3))
            moveAcrossVolumes(fake, source, target, replace = false) shouldBe FileResult.Ok(Unit)
            fake.read(target)?.contentEquals(content) shouldBe true
            fake.exists(copy) shouldBe false
        }
}
