package dev.anydm.engine.files

import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class ClashPolicyTest {
    private val folder = Path("/dl")

    private fun fsWith(vararg names: String) = FakeFileSystem().also { fs -> names.forEach { fs.put(Path(folder, it), byteArrayOf(1)) } }

    private suspend fun free(
        fs: FakeFileSystem,
        name: String,
    ) = (nextFreeName(fs, folder, name) as FileResult.Ok).value

    @Test
    fun aFreeNameIsKept() =
        runTest {
            free(fsWith(), "a.txt") shouldBe Path(folder, "a.txt")
        }

    @Test
    fun nextFreeNameSkipsTakenOnes() =
        runTest {
            free(fsWith("a.txt", "a (1).txt"), "a.txt") shouldBe Path(folder, "a (2).txt")
        }

    @Test
    fun aTakenNameGetsNumberOneFirst() =
        runTest {
            free(fsWith("a.txt"), "a.txt") shouldBe Path(folder, "a (1).txt")
        }

    @Test
    fun aTarExtensionStaysWhole() =
        runTest {
            free(fsWith("a.tar.gz"), "a.tar.gz") shouldBe Path(folder, "a (1).tar.gz")
        }

    @Test
    fun anOrdinaryDottedNameKeepsOnlyTheLastExtension() =
        runTest {
            free(fsWith("my.file.v2.txt"), "my.file.v2.txt") shouldBe Path(folder, "my.file.v2 (1).txt")
        }

    @Test
    fun aNameWithoutExtension() =
        runTest {
            free(fsWith("README"), "README") shouldBe Path(folder, "README (1)")
        }

    @Test
    fun aDotfileHasNoExtension() =
        runTest {
            free(fsWith(".profile"), ".profile") shouldBe Path(folder, ".profile (1)")
        }

    @Test
    fun aFolderInTheWayCountsAsTaken() =
        runTest {
            val fs = FakeFileSystem()
            fs.prepareFolder(Path(folder, "a.txt"))
            free(fs, "a.txt") shouldBe Path(folder, "a (1).txt")
        }

    @Test
    fun aFailingCheckIsReturned() =
        runTest {
            val fs = FakeFileSystem()
            fs.faults[FakeFileSystem.Op.SIZE_OF] = { FileResult.AccessDenied(it) }
            nextFreeName(fs, folder, "a.txt").shouldBeInstanceOf<FileResult.AccessDenied>()
        }
}
