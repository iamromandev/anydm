package dev.anydm.engine.tools

import dev.anydm.engine.platform.DesktopProcessRunner
import dev.anydm.engine.platform.FileResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test

class DesktopUnpackerTest {
    private val root = Files.createTempDirectory("anydm-unpack-test-").toRealPath()
    private val unpacker = DesktopUnpacker(DesktopProcessRunner())
    private val into = Path(root.resolve("into").toString())

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun zip(
        name: String,
        vararg entries: Pair<String, String>,
    ): Path {
        val file = root.resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            for ((entry, text) in entries) {
                out.putNextEntry(ZipEntry(entry))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return Path(file.toString())
    }

    private fun text(relative: String) = Files.readString(root.resolve("into").resolve(relative))

    @Test
    fun unpacksAZipWithItsFolders() {
        runBlocking {
            val archive = zip("a.zip", "bin/tool" to "binary", "README" to "read me")

            unpacker.unpack(archive, ArchiveKind.ZIP, into) shouldBe FileResult.Ok(Unit)

            text("bin/tool") shouldBe "binary"
            text("README") shouldBe "read me"
        }
    }

    @Test
    fun unpackingTwiceOverwrites() {
        runBlocking {
            unpacker.unpack(zip("a.zip", "f" to "one"), ArchiveKind.ZIP, into)
            unpacker.unpack(zip("b.zip", "f" to "two"), ArchiveKind.ZIP, into) shouldBe FileResult.Ok(Unit)

            text("f") shouldBe "two"
        }
    }

    @Test
    fun aZipEntryOutsideTheFolderIsRefused() {
        runBlocking {
            for (name in listOf("../evil", "sub/../../evil", "/abs/evil")) {
                val result = unpacker.unpack(zip("bad.zip", name to "x"), ArchiveKind.ZIP, into)

                result.shouldBeInstanceOf<FileResult.InvalidName>()
            }
            Files.exists(root.resolve("evil")) shouldBe false
            Files.exists(
                Path("/abs/evil").toString().let {
                    java.nio.file.Path
                        .of(it)
                },
            ) shouldBe false
        }
    }

    @Test
    fun aCorruptZipFails() {
        runBlocking {
            val bad = root.resolve("bad.zip").also { Files.write(it, ByteArray(100) { 7 }) }

            unpacker.unpack(Path(bad.toString()), ArchiveKind.ZIP, into).shouldBeInstanceOf<FileResult.IoError>()
        }
    }

    @Test
    fun aMissingArchiveIsNotFound() {
        runBlocking {
            val missing = Path(root.resolve("nope.zip").toString())

            unpacker.unpack(missing, ArchiveKind.ZIP, into).shouldBeInstanceOf<FileResult.NotFound>()
        }
    }

    @Test
    fun aBareFileIsLeftAlone() {
        runBlocking {
            unpacker.unpack(Path(root.resolve("whatever").toString()), ArchiveKind.NONE, into) shouldBe FileResult.Ok(Unit)

            Files.exists(root.resolve("into")) shouldBe false
        }
    }

    @Test
    fun unpacksATarXz() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val source = Files.createDirectories(root.resolve("src/bin"))
        Files.writeString(source.resolve("tool"), "from tar")
        val archive = root.resolve("a.tar.xz")
        val made = ProcessBuilder("tar", "-cJf", archive.toString(), "-C", root.resolve("src").toString(), ".").start().waitFor()
        assumeTrue("tar can't write xz here", made == 0)
        runBlocking {
            unpacker.unpack(Path(archive.toString()), ArchiveKind.TAR_XZ, into) shouldBe FileResult.Ok(Unit)

            text("bin/tool") shouldBe "from tar"
        }
    }

    @Test
    fun aBrokenTarXzReportsWhatTarSaid() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val bad = root.resolve("bad.tar.xz").also { Files.write(it, ByteArray(100) { 7 }) }
        runBlocking {
            unpacker.unpack(Path(bad.toString()), ArchiveKind.TAR_XZ, into).shouldBeInstanceOf<FileResult.IoError>()
        }
    }

    @Test
    fun markExecutableSetsTheBit() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val file = root.resolve("tool").also { Files.writeString(it, "x") }
        runBlocking {
            unpacker.markExecutable(Path(file.toString())) shouldBe FileResult.Ok(Unit)

            Files.isExecutable(file) shouldBe true
        }
    }

    @Test
    fun markExecutableOfAMissingFileIsNotFound() {
        runBlocking {
            unpacker.markExecutable(Path(root.resolve("nope").toString())).shouldBeInstanceOf<FileResult.NotFound>()
        }
    }
}
