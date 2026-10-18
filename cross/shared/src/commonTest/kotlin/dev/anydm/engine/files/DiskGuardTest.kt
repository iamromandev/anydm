package dev.anydm.engine.files

import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class DiskGuardTest {
    private val folder = Path("/dl/a")
    private val other = Path("/dl/b")
    private val reserve = 64L

    private fun guard(fs: FakeFileSystem) = DiskGuard(fs) { reserve }

    @Test
    fun belowTheReserveIsLow() =
        runTest {
            val fs = FakeFileSystem(free = 63)
            guard(fs).lowFolders(setOf(folder), emptySet()) shouldBe setOf(folder)
        }

    @Test
    fun atTheReserveIsNotLow() =
        runTest {
            val fs = FakeFileSystem(free = 64)
            guard(fs).lowFolders(setOf(folder), emptySet()) shouldBe emptySet()
        }

    @Test
    fun aLowFolderStaysLowUntilTwiceTheReserve() =
        runTest {
            val fs = FakeFileSystem(free = 70)
            guard(fs).lowFolders(setOf(folder), alreadyLow = setOf(folder)) shouldBe setOf(folder)
            fs.free = 128
            guard(fs).lowFolders(setOf(folder), alreadyLow = setOf(folder)) shouldBe emptySet()
        }

    @Test
    fun eachFolderIsJudgedOnItsOwn() =
        runTest {
            val fake = FakeFileSystem()
            val perFolder =
                object : FileSystem by fake {
                    override suspend fun freeSpace(path: Path): FileResult<Long> = FileResult.Ok(if (path == folder) 10L else 1000L)
                }
            DiskGuard(perFolder) { reserve }.lowFolders(setOf(folder, other), emptySet()) shouldBe setOf(folder)
        }

    @Test
    fun aFailedCheckKeepsThePreviousState() =
        runTest {
            val fs = FakeFileSystem(free = 1000)
            fs.faults[FakeFileSystem.Op.FREE_SPACE] = { FileResult.IoError(it, "boom") }
            guard(fs).lowFolders(setOf(folder, other), alreadyLow = setOf(folder)) shouldBe setOf(folder)
        }

    @Test
    fun aFolderNoLongerWatchedIsDropped() =
        runTest {
            val fs = FakeFileSystem(free = 1)
            guard(fs).lowFolders(setOf(other), alreadyLow = setOf(folder)) shouldBe setOf(other)
        }
}
