package dev.anydm.engine.tools

import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class DesktopPathFinderTest {
    private val dir = Files.createTempDirectory("anydm-path-test-").toRealPath()
    private val other = Files.createTempDirectory("anydm-path-test-").toRealPath()

    @BeforeTest
    fun notOnWindows() = assumeFalse(System.getProperty("os.name").startsWith("Windows"))

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
        other.toFile().deleteRecursively()
    }

    private fun executable(
        folder: java.nio.file.Path,
        name: String,
        run: Boolean = true,
    ) = Files.writeString(folder.resolve(name), "#!/bin/sh\n").also { it.toFile().setExecutable(run) }

    private fun finder(vararg folders: Any) = DesktopPathFinder(extraFolders = emptyList()) { folders.joinToString(File.pathSeparator) }

    @Test
    fun findsAnExecutableInAFolderOfThePath() {
        executable(dir, "mytool")

        finder("/no/such/folder", dir).find("mytool") shouldBe Path(dir.resolve("mytool").toString())
    }

    @Test
    fun theFirstFolderWins() {
        executable(dir, "mytool")
        executable(other, "mytool")

        finder(other, dir).find("mytool") shouldBe Path(other.resolve("mytool").toString())
    }

    @Test
    fun aFileThatCannotRunIsNotFound() {
        executable(dir, "plain", run = false)

        finder(dir).find("plain") shouldBe null
    }

    @Test
    fun aFolderOfThatNameIsNotFound() {
        Files.createDirectory(dir.resolve("thing"))

        finder(dir).find("thing") shouldBe null
    }

    @Test
    fun missingOrEmptyPathFindsNothing() {
        DesktopPathFinder(extraFolders = emptyList()) { null }.find("mytool") shouldBe null
        finder("", "  ").find("mytool") shouldBe null
        executable(dir, "mytool")
        finder("\u0000bad", dir).find("mytool") shouldBe Path(dir.resolve("mytool").toString())
    }

    @Test
    fun extraFoldersAreSearchedAfterThePath() {
        executable(other, "mytool")

        DesktopPathFinder(extraFolders = listOf(other.toString())) { dir.toString() }.find("mytool") shouldBe
            Path(other.resolve("mytool").toString())
        executable(dir, "mytool")
        DesktopPathFinder(extraFolders = listOf(other.toString())) { dir.toString() }.find("mytool") shouldBe
            Path(dir.resolve("mytool").toString())
    }

    @Test
    fun theDefaultExtraFoldersOnAMacAreTheOnesHomebrewUses() {
        DesktopPathFinder.extraFoldersFor("Mac OS X") shouldBe listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin")
        DesktopPathFinder.extraFoldersFor("Linux") shouldBe emptyList()
    }
}
