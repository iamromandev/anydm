package dev.anydm.engine.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class FakeFileSystemTest {
    private val folder = Path("/fake")
    private val file = Path(folder, "a.bin.anydm")

    private suspend fun FakeFileSystem.writer(): FileWriter {
        prepareFolder(folder)
        preallocate(file, 8)
        return openWriter(file).shouldBeInstanceOf<FileResult.Ok<FileWriter>>().value
    }

    @Test
    fun aPowerCutDropsBytesWrittenSinceTheLastSync() =
        runTest {
            val fs = FakeFileSystem()
            val writer = fs.writer()
            writer.write(0, byteArrayOf(1, 2, 3, 4))
            writer.sync()
            writer.write(4, byteArrayOf(9, 9))

            fs.powerCut()

            fs.read(file)!!.toList() shouldBe listOf<Byte>(1, 2, 3, 4, 0, 0, 0, 0)
        }

    @Test
    fun aWriterOpenedBeforeAPowerCutIsDead() =
        runTest {
            val fs = FakeFileSystem()
            val writer = fs.writer()
            writer.write(0, byteArrayOf(1))

            fs.powerCut()

            writer.sync().shouldBeInstanceOf<FileResult.IoError>()
            writer.write(0, byteArrayOf(2)).shouldBeInstanceOf<FileResult.IoError>()
            fs.read(file)!!.first() shouldBe 0.toByte()
        }

    @Test
    fun aPowerCutKeepsMetadataOperations() =
        runTest {
            val fs = FakeFileSystem()
            val writer = fs.writer()
            writer.write(0, byteArrayOf(7))
            writer.sync()
            writer.close()
            val target = Path(folder, "a.bin")
            fs.atomicMove(file, target, replace = false)

            fs.powerCut()

            fs.exists(file) shouldBe false
            fs.read(target)!!.first() shouldBe 7.toByte()
        }

    @Test
    fun sizeReportsTheFileLength() =
        runTest {
            val fs = FakeFileSystem()
            fs.writer()

            fs.size(file) shouldBe FileResult.Ok(8L)
            fs.size(Path(folder, "missing")) shouldBe FileResult.NotFound(Path(folder, "missing"))
        }

    @Test
    fun readServesTheFakesBytes() =
        runTest {
            val fs = FakeFileSystem()
            fs.put(file, "0123456789".encodeToByteArray())

            fs.read(file, 3, 4).shouldBeInstanceOf<FileResult.Ok<ByteArray>>().value shouldBe "3456".encodeToByteArray()
            fs.read(file, 8, 10).shouldBeInstanceOf<FileResult.Ok<ByteArray>>().value shouldBe "89".encodeToByteArray()
            fs.read(Path(folder, "missing"), 0, 1) shouldBe FileResult.NotFound(Path(folder, "missing"))
        }

    @Test
    fun readHonoursAFault() =
        runTest {
            val fs = FakeFileSystem()
            fs.put(file, "0123456789".encodeToByteArray())
            fs.faults[FakeFileSystem.Op.READ] = { FileResult.IoError(it, "boom") }

            fs.read(file, 0, 1) shouldBe FileResult.IoError(file, "boom")
        }

    @Test
    fun listGivesTheDirectChildrenByName() =
        runTest {
            val fs = FakeFileSystem()
            fs.put(Path(folder, "b.txt"), byteArrayOf(1))
            fs.put(Path(folder, "a.txt"), byteArrayOf(1))
            fs.put(Path(folder, "sub", "deep.txt"), byteArrayOf(1))

            fs.list(folder) shouldBe FileResult.Ok(listOf(Path(folder, "a.txt"), Path(folder, "b.txt"), Path(folder, "sub")))
        }

    @Test
    fun listOfAFileOrAMissingFolderFails() =
        runTest {
            val fs = FakeFileSystem()
            fs.put(file, byteArrayOf(1))

            fs.list(file) shouldBe FileResult.AlreadyExists(file, "a file is in the way")
            fs.list(Path("/nope")) shouldBe FileResult.NotFound(Path("/nope"))
        }
}
