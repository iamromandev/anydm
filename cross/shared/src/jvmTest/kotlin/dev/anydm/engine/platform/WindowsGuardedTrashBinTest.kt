package dev.anydm.engine.platform

import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import java.nio.file.Path as NioPath

class WindowsGuardedTrashBinTest {
    private val root: NioPath = Files.createTempDirectory("anydm-guard-test-")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private class Recording(
        var available: Boolean = true,
    ) : TrashBin {
        val trashed = mutableListOf<NioPath>()
        override val isAvailable get() = available

        override fun moveToTrash(item: NioPath): Boolean = trashed.add(item)
    }

    private class Drive(
        val fixed: Boolean = true,
        val total: Long = 1_000_000,
    ) : DriveInfo {
        override fun isFixedDrive(path: NioPath) = fixed

        override fun totalSpace(path: NioPath) = total
    }

    private fun item(size: Int = 100): NioPath = Files.createTempFile(root, "f", ".bin").also { Files.write(it, ByteArray(size)) }

    @Test
    fun aSmallFileOnAFixedDriveIsTrashed() {
        val inner = Recording()
        val file = item()

        WindowsGuardedTrashBin(inner, Drive()).moveToTrash(file) shouldBe true

        inner.trashed shouldBe listOf(file)
    }

    @Test
    fun aNetworkPathIsRefused() {
        val inner = Recording()
        val unc = NioPath.of("""\\server\share\a.bin""")

        WindowsGuardedTrashBin(inner, Drive()).moveToTrash(unc) shouldBe false

        inner.trashed shouldBe emptyList()
    }

    @Test
    fun aRemovableOrNetworkDriveIsRefused() {
        val inner = Recording()

        WindowsGuardedTrashBin(inner, Drive(fixed = false)).moveToTrash(item()) shouldBe false

        inner.trashed shouldBe emptyList()
    }

    @Test
    fun aFileLargerThanTenPercentOfTheDriveIsRefused() {
        val inner = Recording()

        WindowsGuardedTrashBin(inner, Drive(total = 500)).moveToTrash(item(size = 100)) shouldBe false

        inner.trashed shouldBe emptyList()
    }

    @Test
    fun aFolderIsMeasuredByItsContents() {
        val inner = Recording()
        val folder = Files.createDirectory(root.resolve("dir"))
        Files.write(folder.resolve("a"), ByteArray(60))
        Files.write(folder.resolve("b"), ByteArray(60))

        WindowsGuardedTrashBin(inner, Drive(total = 1000)).moveToTrash(folder) shouldBe false
        WindowsGuardedTrashBin(inner, Drive(total = 10_000)).moveToTrash(folder) shouldBe true
    }

    @Test
    fun itIsAvailableWhenTheBinIs() {
        WindowsGuardedTrashBin(Recording(available = false), Drive()).isAvailable shouldBe false
        WindowsGuardedTrashBin(Recording(available = true), Drive()).isAvailable shouldBe true
    }
}
