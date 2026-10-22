package dev.anydm.engine.os

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One app per user, and what a second launch hands over (OSI-08), with real files and sockets. */
class FileSingleInstanceTest {
    private val folder = Files.createTempDirectory("anydm-instance-test-").toFile()
    private val opened = mutableListOf<AutoCloseable>()
    private val heard = LinkedBlockingQueue<List<String>>()

    @AfterTest
    fun close() {
        opened.forEach { runCatching { it.close() } }
        folder.deleteRecursively()
    }

    private fun instance(answerWithin: kotlin.time.Duration = 3.seconds) = FileSingleInstance(folder, answerWithin).also { opened += it }

    private suspend fun primary(): FileSingleInstance {
        val first = instance()
        first.start(listOf("first")) { heard.put(it) } shouldBe SingleInstance.Role.PRIMARY
        return first
    }

    private fun next(): List<String> = heard.poll(5, TimeUnit.SECONDS) ?: error("nothing was handed over")

    private fun connect(): Pair<Socket, Int> {
        val info = File(folder, "instance.json").readText()
        val port = Regex("\"port\":(\\d+)").find(info)!!.groupValues[1].toInt()
        return Socket(InetAddress.getByName("127.0.0.1"), port) to port
    }

    @Test
    fun theFirstLaunchIsTheRunningInstance() =
        runBlocking<Unit> {
            primary()
            heard.size shouldBe 0
        }

    @Test
    fun aSecondLaunchHandsItsArgumentsOverInOrderAndIsToldToExit() =
        runBlocking<Unit> {
            primary()

            val role = instance().start(listOf("magnet:?xt=urn:btih:abc", "/home/me/my file.torrent", "ünïcode ✓")) { }

            role shouldBe SingleInstance.Role.HANDED_OVER
            next() shouldContainExactly listOf("magnet:?xt=urn:btih:abc", "/home/me/my file.torrent", "ünïcode ✓")
        }

    @Test
    fun severalLaunchesAreAllHeardInTheOrderTheyCame() =
        runBlocking<Unit> {
            primary()
            for (n in 1..5) instance().start(listOf("link-$n")) { } shouldBe SingleInstance.Role.HANDED_OVER

            (1..5).map { next() } shouldContainExactly (1..5).map { listOf("link-$it") }
        }

    @Test
    fun aSecondLaunchWithNoArgumentsStillExits() =
        runBlocking<Unit> {
            primary()

            instance().start(emptyList()) { } shouldBe SingleInstance.Role.HANDED_OVER
            next() shouldBe emptyList()
        }

    @Test
    fun aRunningInstanceThatDoesNotAnswerIsGivenUpOnAfterTheWait() =
        runBlocking<Unit> {
            // Something holds the lock and listens to nobody.
            val holder = RandomAccessFile(File(folder, "instance.lock"), "rw")
            opened += holder
            val lock = holder.channel.lock()
            opened += lock

            val waited = System.nanoTime()
            val role = instance(answerWithin = 400.milliseconds).start(listOf("x")) { }

            role shouldBe SingleInstance.Role.ALONE
            ((System.nanoTime() - waited) / 1_000_000 >= 350) shouldBe true
        }

    @Test
    fun afterTheRunningInstanceClosesTheNextLaunchIsTheRunningOne() =
        runBlocking<Unit> {
            primary().close()

            instance().start(emptyList()) { } shouldBe SingleInstance.Role.PRIMARY
        }

    @Test
    fun aRelativeTorrentPathIsMadeAbsoluteFromTheSecondLaunchsOwnFolder() =
        runBlocking<Unit> {
            primary()

            instance().start(listOf("my.torrent", "https://example.com/a.zip", "--flag")) { } shouldBe SingleInstance.Role.HANDED_OVER

            next() shouldContainExactly listOf(File("my.torrent").absolutePath, "https://example.com/a.zip", "--flag")
        }

    @Test
    fun aLeftoverFileOfADeadInstanceIsReplacedByTheNextOne() =
        runBlocking<Unit> {
            File(folder, "instance.json").writeText("""{"port":1,"secret":"stale"}""")

            val first = instance()
            first.start(emptyList()) { heard.put(it) } shouldBe SingleInstance.Role.PRIMARY

            instance().start(listOf("after")) { } shouldBe SingleInstance.Role.HANDED_OVER
            next() shouldBe listOf("after")
        }

    @Test
    fun aFolderThatCannotBeUsedMeansGoingOnAloneNotFailing() =
        runBlocking<Unit> {
            val notAFolder = File(folder, "file").also { it.writeText("x") }

            FileSingleInstance(notAFolder, 300.milliseconds).also { opened += it }.start(emptyList()) { } shouldBe SingleInstance.Role.ALONE
        }

    @Test
    fun aLaunchThatWaitsForALockTakesItWhenItIsFreed() =
        runBlocking<Unit> {
            val holder = RandomAccessFile(File(folder, "instance.lock"), "rw")
            opened += holder
            val lock = holder.channel.lock()
            val role = java.util.concurrent.CompletableFuture<SingleInstance.Role>()
            Thread { role.complete(runBlocking { instance(answerWithin = 5.seconds).start(emptyList()) { } }) }.start()
            Thread.sleep(300)

            lock.release()
            holder.close()

            role.get(10, TimeUnit.SECONDS) shouldBe SingleInstance.Role.PRIMARY
        }

    @Test
    fun aClientThatDripsIsCutOff() =
        runBlocking<Unit> {
            FileSingleInstance(folder, 3.seconds, readWithin = 600.milliseconds).also { opened += it }.start(emptyList()) { heard.put(it) }
            val (socket, _) = connect()

            socket.use {
                it.soTimeout = 5000
                val out = it.getOutputStream()
                val started = System.nanoTime()
                var cut = false
                while (System.nanoTime() - started < 4_000_000_000L && !cut) {
                    try {
                        out.write('a'.code)
                        out.flush()
                        Thread.sleep(150)
                    } catch (_: java.io.IOException) {
                        cut = true
                    }
                }
                cut shouldBe true
            }
            instance().start(listOf("still-works")) { } shouldBe SingleInstance.Role.HANDED_OVER
            next() shouldBe listOf("still-works")
        }

    @Test
    fun aMessageOverTheLimitIsCutOffByTheServerToo() =
        runBlocking<Unit> {
            primary()
            val (socket, _) = connect()

            socket.use {
                it.soTimeout = 5000
                try {
                    it.getOutputStream().write(ByteArray(70_000) { 'a'.code.toByte() })
                    it.getOutputStream().flush()
                } catch (_: java.io.IOException) {
                    // Cut off before the end: the point.
                }
                // Closed with bytes still unread, so the end comes as a clean close or as a reset: either way, cut off.
                val ended =
                    try {
                        it.getInputStream().read() == -1
                    } catch (_: java.io.IOException) {
                        true
                    }
                ended shouldBe true
            }
            heard.poll(300, TimeUnit.MILLISECONDS) shouldBe null
        }

    @Test
    fun aMessageWithTheWrongSecretIsNotHeard() =
        runBlocking<Unit> {
            primary()
            val (socket, _) = connect()

            socket.use {
                it.getOutputStream().write(
                    """{"secret":"wrong","arguments":["https://evil.example/x"]}""".encodeToByteArray() + '\n'.code.toByte(),
                )
                it.getOutputStream().flush()
                it.soTimeout = 3000
                it.getInputStream().read() shouldBe -1
            }
            heard.poll(300, TimeUnit.MILLISECONDS) shouldBe null
        }

    @Test
    fun nonsenseIsNotHeardEither() =
        runBlocking<Unit> {
            primary()
            for (junk in listOf("not json", "[]", """{"arguments":["x"]}""", """{"secret":5,"arguments":["x"]}""", "")) {
                val (socket, _) = connect()
                socket.use {
                    it.getOutputStream().write(junk.encodeToByteArray() + '\n'.code.toByte())
                    it.getOutputStream().flush()
                    it.soTimeout = 3000
                    it.getInputStream().read() shouldBe -1
                }
            }
            heard.poll(300, TimeUnit.MILLISECONDS) shouldBe null
        }

    @Test
    fun aMessageTooBigToSendIsGivenUpOnAtOnce() =
        runBlocking<Unit> {
            primary()
            val huge = "a".repeat(200_000)

            instance(answerWithin = 600.milliseconds).start(listOf(huge)) { } shouldBe SingleInstance.Role.ALONE
            heard.poll(300, TimeUnit.MILLISECONDS) shouldBe null
        }

    @Test
    fun theFileThatNamesTheSecretIsPrivate() =
        runBlocking<Unit> {
            primary()
            val file = File(folder, "instance.json").toPath()

            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.getPosixFilePermissions(file) shouldBe setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
        }

    @Test
    fun aRealSecondProcessHandsItsArgumentsOver() =
        runBlocking<Unit> {
            primary()
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process =
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.anydm.engine.os.SecondInstanceMainKt",
                    folder.path,
                    "magnet:?xt=urn:btih:def",
                    "/tmp/x.torrent",
                ).redirectErrorStream(true)
                    .start()

            val exited = process.waitFor(30, TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()

            (exited && process.exitValue() == 0) shouldBe true
            next() shouldContainExactly listOf("magnet:?xt=urn:btih:def", "/tmp/x.torrent")
        }
}
