package dev.anydm.engine.http

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.PrepareOutcome
import dev.anydm.engine.core.RunListener
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.namesIn
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.platform.readBytes
import dev.anydm.engine.platform.sizeOf
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** HttpProtocol on OkHttp, the real runner, store and file system, against the fault server (#569). */
class HttpDownloadTest {
    private val root = newTempFolder("anydm-http-")
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val bytes = Random(1).nextBytes(20 shl 20)
    private val runner =
        StepRunner(
            DesktopFileSystem(),
            store.downloads,
            mapOf(DownloadKind.DIRECT to HttpProtocol(OkHttpTransport(), connections = { 4 })),
            root,
            freeSpaceReserve = 0,
            // Watermarks reach the store often enough for the resume test to see blocks finish.
            checkpointEvery = 100.milliseconds,
        )

    init {
        server.serve("/f.bin", bytes, etag = "\"v1\"")
    }

    @AfterTest
    fun close() {
        server.close()
        store.close()
        deleteRecursively(root)
    }

    private suspend fun added(): Pair<Download, Mirror> {
        val download =
            store.downloads.add(
                NewDownload(DownloadKind.DIRECT, mirrors = listOf(server.url("/f.bin")), saveFolder = root.toString()),
            )
        return download to store.downloads.mirrors(download.id).single()
    }

    private suspend fun prepared(
        download: Download,
        mirror: Mirror,
    ) = runner.prepare(download, mirror, RunListener.None).shouldBeInstanceOf<PrepareOutcome.Ready>()

    private fun ranged() = server.requests.filter { it.headers["Range"] != null && it.headers["Range"] != "bytes=0-1023" }

    private fun partial(download: Download) = "f.bin.${download.id.value}.anydm"

    @Test
    fun aFileDownloadsOverFourConnections() =
        runBlocking<Unit> {
            val (download, mirror) = added()
            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()
            readBytes(Path(root, "f.bin")) shouldBe bytes
            namesIn(root) shouldBe setOf("f.bin")
            ranged().size shouldBe 16
            server.requests.all { it.headers["Accept-Encoding"] == "identity" } shouldBe true
        }

    @Test
    fun aServerThatIgnoresRangesStillDownloads() =
        runBlocking<Unit> {
            server.fault("/f.bin", Fault.IgnoreRanges)
            val (download, mirror) = added()
            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()
            readBytes(Path(root, "f.bin")) shouldBe bytes
            server.requests
                .drop(1)
                .single()
                .headers["Range"] shouldBe null
        }

    @Test
    fun aWrongContentRangeFailsTheRun() =
        runBlocking<Unit> {
            server.fault("/f.bin", Fault.WrongContentRange)
            val (download, mirror) = added()
            val failed = runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Failed>()
            failed.failure.errorClass shouldBe ErrorClass.TRANSIENT
            namesIn(root) shouldContain partial(download)
        }

    @Test
    fun aDroppedConnectionRecoversWithinTheRun() =
        runBlocking<Unit> {
            val (download, mirror) = added()
            val ready = prepared(download, mirror)
            server.fault("/f.bin", Fault.Once(Fault.DropAfter(300_000)))
            runner.run(ready).shouldBeInstanceOf<RunOutcome.Finished>()
            readBytes(Path(root, "f.bin")) shouldBe bytes
            ranged().size shouldBe 17
        }

    @Test
    fun aStoppedDownloadResumesWithIdenticalBytes() =
        runBlocking<Unit> {
            server.fault("/f.bin", Fault.Throttle(1L shl 20))
            val (download, mirror) = added()
            val first = prepared(download, mirror)
            val job = launch(Dispatchers.Default) { runner.run(first) }
            while (true) {
                val segments = store.downloads.files(download.id).flatMap { store.downloads.segments(it.id) }
                if (segments.count { it.isComplete } >= 5) break
                delay(20)
            }
            job.isActive shouldBe true
            job.cancelAndJoin()
            namesIn(root) shouldBe setOf(partial(download))
            sizeOf(Path(root, partial(download))) shouldBe bytes.size.toLong()
            val done =
                store.downloads
                    .files(download.id)
                    .flatMap { store.downloads.segments(it.id) }
                    .filter { it.isComplete }
            val before = server.requests.size

            server.clear("/f.bin")
            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()
            readBytes(Path(root, "f.bin")) shouldBe bytes
            val starts =
                server.requests
                    .drop(before)
                    .mapNotNull { it.headers["Range"] }
                    .filter { it != "bytes=0-1023" } // the probe
                    .map { it.removePrefix("bytes=").substringBefore('-').toLong() }
            starts.isEmpty() shouldBe false
            done.none { it.start in starts } shouldBe true
        }

    // #570: a slow block is raced and the first to finish wins

    @Test
    fun aSlowBlockIsRacedEndToEnd() =
        runBlocking<Unit> {
            val (download, mirror) = added()
            val ready = prepared(download, mirror)
            // The first ranged request (block 0, 1.25 MiB) at 64 KiB/s would take about 20 s alone.
            server.fault("/f.bin", Fault.Once(Fault.Throttle(64L * 1024)))
            val took = measureTime { runner.run(ready).shouldBeInstanceOf<RunOutcome.Finished>() }
            (took < 10.seconds) shouldBe true
            readBytes(Path(root, "f.bin")) shouldBe bytes
            val firstBlock = 0L until (bytes.size / 16).toLong()
            ranged().count {
                it.headers["Range"]!!
                    .removePrefix("bytes=")
                    .substringBefore('-')
                    .toLong() in firstBlock
            } shouldBe 2
        }
}
