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
}
