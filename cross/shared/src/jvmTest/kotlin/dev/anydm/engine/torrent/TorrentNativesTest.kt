package dev.anydm.engine.torrent

import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

class TorrentNativesTest {
    private val root = Files.createTempDirectory("anydm-natives-test-").toFile()
    private val property = "libtorrent4j.jni.path"
    private val before: String? = System.getProperty(property)

    @AfterTest
    fun restore() {
        if (before == null) System.clearProperty(property) else System.setProperty(property, before)
        root.deleteRecursively()
    }

    /** A class loader that has [resource] with [bytes] and nothing else. */
    private fun loaderWith(
        resource: String,
        bytes: ByteArray,
    ): ClassLoader {
        val jar = File(root, "natives").also { it.mkdirs() }
        File(jar, resource).also { it.parentFile.mkdirs() }.writeBytes(bytes)
        return URLClassLoader(arrayOf(URL("file:${jar.absolutePath}/")), null)
    }

    @Test
    fun nativeResourceForEachKnownPair() {
        nativeResource("Mac OS X", "aarch64") shouldBe "lib/arm64/libtorrent4j.dylib"
        nativeResource("Linux", "amd64") shouldBe "lib/x86_64/libtorrent4j.so"
        nativeResource("Windows 11", "amd64") shouldBe "lib/x86_64/libtorrent4j.dll"
        nativeResource("Windows 10", "x86_64") shouldBe "lib/x86_64/libtorrent4j.dll"
    }

    @Test
    fun anUnknownPairIsNull() {
        nativeResource("Mac OS X", "x86_64") shouldBe null
        nativeResource("Linux", "aarch64") shouldBe null
        nativeResource("FreeBSD", "amd64") shouldBe null
        nativeResource("Linux", "riscv64") shouldBe null
    }

    @Test
    fun loadExtractsOnceAndSetsTheProperty() =
        runBlocking<Unit> {
            val bytes = ByteArray(1000) { it.toByte() }
            val natives =
                TorrentNatives(Path(File(root, "out").toString()), loaderWith("lib/x86_64/libtorrent4j.so", bytes), "Linux", "amd64")

            val first = natives.extract().shouldBeInstanceOf<StepResult.Ok<File>>().value
            first.readBytes().toList() shouldBe bytes.toList()
            first.parentFile.name shouldBe "1000"
            System.getProperty(property) shouldBe first.absolutePath

            first.writeBytes(ByteArray(1000) { 9 })
            val second = natives.extract().shouldBeInstanceOf<StepResult.Ok<File>>().value
            second shouldBe first
            // the same length: it is the file already there, not copied again
            second.readBytes().all { it == 9.toByte() } shouldBe true
        }

    @Test
    fun aMissingResourceIsAClearFailure() =
        runBlocking<Unit> {
            val natives = TorrentNatives(Path(File(root, "out").toString()), loaderWith("lib/other/x", ByteArray(1)), "Linux", "amd64")

            natives.load().shouldBeInstanceOf<StepResult.Failed>().message shouldContain "torrents aren't supported here"
        }

    @Test
    fun anUnknownSystemIsAClearFailure() =
        runBlocking<Unit> {
            val natives = TorrentNatives(Path(File(root, "out").toString()), loaderWith("lib/x", ByteArray(1)), "Linux", "riscv64")

            natives.load().shouldBeInstanceOf<StepResult.Failed>().message shouldContain "torrents aren't supported here"
        }
}
