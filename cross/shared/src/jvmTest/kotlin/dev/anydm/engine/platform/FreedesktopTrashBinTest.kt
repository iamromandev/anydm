package dev.anydm.engine.platform

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test

class FreedesktopTrashBinTest {
    private val root: Path = Files.createTempDirectory("anydm-trash-test-")
    private val homeTrash = root.resolve("home/Trash")
    private val mount = root.resolve("mnt")
    private val deletedAt = LocalDateTime.of(2026, 10, 18, 12, 34, 56)

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    /** One volume (home) or two (the mount point is its own), without a second disk. */
    private class TwoVolumes(
        private val root: Path,
        private val mount: Path,
    ) : Volumes {
        override fun sameVolume(
            a: Path,
            b: Path,
        ) = a.startsWith(mount) == b.startsWith(mount)

        override fun mountRoot(path: Path): Path = if (path.startsWith(mount)) mount else root
    }

    private fun bin(uid: Int = 1000) = FreedesktopTrashBin(homeTrash, uid, { deletedAt }, TwoVolumes(root, mount))

    private fun file(
        path: Path,
        text: String = "x",
    ): Path {
        path.parent.createDirectories()
        path.createFile()
        path.writeText(text)
        return path
    }

    private fun infoOf(name: String) = homeTrash.resolve("info/$name.trashinfo").readLines()

    @Test
    fun aHomeVolumeFileGoesToTheHomeTrash() {
        val item = file(root.resolve("data/a.txt"), "hello")

        bin().moveToTrash(item) shouldBe true

        item.exists() shouldBe false
        homeTrash.resolve("files/a.txt").readLines() shouldBe listOf("hello")
        infoOf("a.txt") shouldBe listOf("[Trash Info]", "Path=${item.toString().encodedForInfo()}", "DeletionDate=2026-10-18T12:34:56")
    }

    @Test
    fun anotherVolumeUsesItsTopdirTrashWithARelativePath() {
        val item = file(mount.resolve("docs/b.txt"))

        bin(uid = 1000).moveToTrash(item) shouldBe true

        val trash = mount.resolve(".Trash-1000")
        trash.resolve("files/b.txt").exists() shouldBe true
        trash.resolve("info/b.txt.trashinfo").readLines()[1] shouldBe "Path=docs/b.txt"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(trash)) shouldBe "rwx------"
    }

    @Test
    fun aNameClashGetsANumberedSuffix() {
        val first = file(root.resolve("one/a.txt"), "1")
        val second = file(root.resolve("two/a.txt"), "2")

        bin().moveToTrash(first) shouldBe true
        bin().moveToTrash(second) shouldBe true

        homeTrash
            .resolve("files")
            .listDirectoryEntries()
            .map { it.name }
            .sorted() shouldBe listOf("a.2.txt", "a.txt")
        homeTrash.resolve("files/a.txt").readLines() shouldBe listOf("1")
        homeTrash.resolve("files/a.2.txt").readLines() shouldBe listOf("2")
        homeTrash
            .resolve("info")
            .listDirectoryEntries()
            .map { it.name }
            .sorted() shouldBe listOf("a.2.txt.trashinfo", "a.txt.trashinfo")
    }

    @Test
    fun unicodeSpacesAndPercentSignsRoundTrip() {
        val item = file(root.resolve("data/é ü 100%.txt"))

        bin().moveToTrash(item) shouldBe true

        val line = infoOf("é ü 100%.txt")[1].removePrefix("Path=")
        URLDecoder.decode(line.replace("+", "%2B"), UTF_8) shouldBe item.toString()
        line.contains(' ') shouldBe false
        line.endsWith("100%25.txt") shouldBe true
    }

    @Test
    fun aFolderIsMovedWhole() {
        val folder = root.resolve("data/dir")
        file(folder.resolve("in/x.txt"))

        bin().moveToTrash(folder) shouldBe true

        folder.exists() shouldBe false
        homeTrash.resolve("files/dir/in/x.txt").exists() shouldBe true
    }

    @Test
    fun aMissingItemThrowsNoSuchFile() {
        shouldThrow<NoSuchFileException> { bin().moveToTrash(root.resolve("data/gone.txt")) }
    }

    @Test
    fun theTrashIsAvailableWhenItCanBeMade() {
        bin().isAvailable shouldBe true
        homeTrash.resolve("files").exists() shouldBe true
        homeTrash.resolve("info").exists() shouldBe true
    }

    @Test
    fun theTrashIsNotAvailableWhenItsPlaceIsAFile() {
        val blocked = file(root.resolve("blocked"))
        FreedesktopTrashBin(blocked.resolve("Trash"), 1000, { deletedAt }, TwoVolumes(root, mount)).isAvailable shouldBe false
    }

    private fun String.encodedForInfo(): String =
        encodeToByteArray().joinToString("") { b ->
            val c = (b.toInt() and 0xff).toChar()
            if ((c.isLetterOrDigit() && b >= 0) || c in "/-_.~") c.toString() else "%" + "%02X".format(b.toInt() and 0xff)
        }
}
