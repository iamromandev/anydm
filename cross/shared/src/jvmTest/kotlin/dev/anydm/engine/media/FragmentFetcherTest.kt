package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.http.Fault
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

class FragmentFetcherTest {
    private val server = FaultServer()
    private val http = HttpProtocol(OkHttpTransport())
    private val fetcher = FragmentFetcher(http, DesktopFileSystem())
    private val root = Files.createTempDirectory("anydm-frag-test-").toRealPath().toFile()
    private val dir = File(root, "frag")
    private val folder = Path(dir.toString())

    @AfterTest
    fun close() {
        server.close()
        root.deleteRecursively()
    }

    private fun bytesOf(i: Int) = ByteArray(1000 + i * 7) { (it + i).toByte() }

    private fun serve(n: Int) {
        for (i in 0 until n) server.serve("/f/$i", bytesOf(i))
    }

    private fun refs(n: Int) = (0 until n).map { FragmentRef(server.url("/f/$it")) }

    private val trusted get() = server.url("/")

    private fun onDisk(i: Int) = File(dir, fragmentName(i))

    private fun requestsTo(path: String) = server.requests.count { it.url.encodedPath == path }

    private fun total(n: Int) = (0 until n).sumOf { bytesOf(it).size.toLong() }

    private fun failed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    @Test
    fun fetchesEveryFragmentIntoNumberedFiles() =
        runBlocking<Unit> {
            serve(5)
            val io = CollectingIo()

            fetcher.fetch(folder, refs(5), null, io, trusted) shouldBe StepResult.Ok(total(5))

            for (i in 0 until 5) onDisk(i).readBytes().contentEquals(bytesOf(i)) shouldBe true
            dir.list().orEmpty().none { it.endsWith(".tmp") } shouldBe true
            io.limiter.bytes shouldBe total(5)
            io.meter.bytes shouldBe total(5)
        }

    @Test
    fun skipsFragmentsAlreadyOnDisk() =
        runBlocking<Unit> {
            serve(4)
            dir.mkdirs()
            onDisk(0).writeBytes(bytesOf(0))
            onDisk(1).writeBytes(bytesOf(1))
            val io = CollectingIo()

            fetcher.fetch(folder, refs(4), null, io, trusted) shouldBe StepResult.Ok(total(4))

            requestsTo("/f/0") shouldBe 0
            requestsTo("/f/1") shouldBe 0
            io.limiter.bytes shouldBe total(4) - bytesOf(0).size - bytesOf(1).size
        }

    @Test
    fun aStaleTempFileIsRemoved() =
        runBlocking<Unit> {
            serve(3)
            dir.mkdirs()
            File(dir, "000002.tmp").writeBytes(byteArrayOf(9, 9, 9))

            fetcher.fetch(folder, refs(3), null, CollectingIo(), trusted) shouldBe StepResult.Ok(total(3))

            dir.list().orEmpty().none { it.endsWith(".tmp") } shouldBe true
            onDisk(2).readBytes().contentEquals(bytesOf(2)) shouldBe true
        }

    @Test
    fun aTruncatedBodyIsRetried() =
        runBlocking<Unit> {
            serve(3)
            server.fault("/f/2", Fault.Once(Fault.DropAfter(10)))

            fetcher.fetch(folder, refs(3), null, CollectingIo(), trusted) shouldBe StepResult.Ok(total(3))

            requestsTo("/f/2") shouldBe 2
            onDisk(2).readBytes().contentEquals(bytesOf(2)) shouldBe true
        }

    @Test
    fun aFragmentThatKeepsFailingFailsTheFetch() =
        runBlocking<Unit> {
            serve(3)
            server.fault("/f/1", Fault.Status(503))

            failed(fetcher.fetch(folder, refs(3), null, CollectingIo(), trusted)).errorClass shouldBe ErrorClass.TRANSIENT

            requestsTo("/f/1") shouldBe 3
        }

    @Test
    fun aForbiddenFragmentFailsAtOnce() =
        runBlocking<Unit> {
            serve(3)
            server.fault("/f/1", Fault.Status(403))

            failed(fetcher.fetch(folder, refs(3), null, CollectingIo(), trusted)).errorClass shouldBe ErrorClass.AUTH

            requestsTo("/f/1") shouldBe 1
        }

    @Test
    fun aByteRangeFragmentAsksForItsRange() =
        runBlocking<Unit> {
            val big = ByteArray(5000) { (it % 200).toByte() }
            server.serve("/big", big)

            fetcher.fetch(folder, listOf(FragmentRef(server.url("/big"), null, 100, 200)), null, CollectingIo(), trusted) shouldBe
                StepResult.Ok(100L)

            onDisk(0).readBytes().contentEquals(big.copyOfRange(100, 200)) shouldBe true
            server.requests.last().headers["Range"] shouldBe "bytes=100-199"
        }

    @Test
    fun progressStartsFromWhatIsAlreadyThere() =
        runBlocking<Unit> {
            serve(3)
            dir.mkdirs()
            onDisk(0).writeBytes(bytesOf(0))
            val io = CollectingIo()

            fetcher.fetch(folder, refs(3), null, io, trusted)

            io.progress.first() shouldBe bytesOf(0).size.toLong()
            io.progress.last() shouldBe total(3)
            io.progress shouldBe io.progress.sorted()
        }

    @Test
    fun anEmptyBodyIsRetriedThenFails() =
        runBlocking<Unit> {
            serve(2)
            server.serve("/f/0", ByteArray(0))

            failed(fetcher.fetch(folder, refs(2), null, CollectingIo(), trusted)).errorClass shouldBe ErrorClass.TRANSIENT

            requestsTo("/f/0") shouldBe 3
            onDisk(0).exists() shouldBe false
        }

    @Test
    fun aZeroByteFragmentFileIsFetchedAgain() =
        runBlocking<Unit> {
            serve(2)
            dir.mkdirs()
            onDisk(0).writeBytes(ByteArray(0))

            fetcher.fetch(folder, refs(2), null, CollectingIo(), trusted) shouldBe StepResult.Ok(total(2))

            onDisk(0).readBytes().contentEquals(bytesOf(0)) shouldBe true
        }

    @Test
    fun credentialsStayAwayFromAnotherHost() =
        runBlocking<Unit> {
            FaultServer().use { other ->
                serve(1)
                other.serve("/g/1", bytesOf(1))
                val context = RequestContext(listOf("Authorization" to "Bearer secret"), cookies = "session=1")
                val fragments = listOf(FragmentRef(server.url("/f/0")), FragmentRef(other.url("/g/1")))

                fetcher.fetch(folder, fragments, context, CollectingIo(), trusted) shouldBe StepResult.Ok(total(2))

                other.requests.none { it.headers["Authorization"] != null || it.headers["Cookie"] != null } shouldBe true
                server.requests.all { it.headers["Authorization"] == "Bearer secret" && it.headers["Cookie"] == "session=1" } shouldBe true
            }
        }

    @Test
    fun aChangedFragmentListStartsTheFolderOver() =
        runBlocking<Unit> {
            serve(3)
            val first = (0 until 3).map { FragmentRef(server.url("/f/$it"), duration = 4.0) }
            fetcher.fetch(folder, first, null, CollectingIo(), trusted)
            for (i in 0 until 3) server.serve("/g/$i", bytesOf(i + 10))
            val second = (0 until 3).map { FragmentRef(server.url("/g/$it"), duration = 6.0) }

            fetcher.fetch(folder, second, null, CollectingIo(), trusted) shouldBe
                StepResult.Ok((0 until 3).sumOf { bytesOf(it + 10).size.toLong() })

            for (i in 0 until 3) onDisk(i).readBytes().contentEquals(bytesOf(i + 10)) shouldBe true
        }

    @Test
    fun theSameListLeavesTheFolderAlone() =
        runBlocking<Unit> {
            serve(3)
            val list = (0 until 3).map { FragmentRef(server.url("/f/$it"), duration = 4.0) }
            fetcher.fetch(folder, list, null, CollectingIo(), trusted)
            val before = server.requests.size

            fetcher.fetch(folder, list, null, CollectingIo(), trusted) shouldBe StepResult.Ok(total(3))

            server.requests.size shouldBe before
        }

    @Test
    fun aBadAddressFailsCleanlyAmongMany() =
        runBlocking<Unit> {
            serve(6)
            val list = refs(6).toMutableList().also { it[0] = FragmentRef("file:///etc/passwd") }

            failed(fetcher.fetch(folder, list, null, CollectingIo(), trusted))
        }

    @Test
    fun aHtmlErrorPageIsNotSavedAsAFragment() =
        runBlocking<Unit> {
            serve(3)
            server.serve("/f/1", "<html>blocked</html>".toByteArray(), contentType = "text/html")

            failed(fetcher.fetch(folder, refs(3), null, CollectingIo(), trusted)).errorClass shouldBe ErrorClass.TRANSIENT

            onDisk(1).exists() shouldBe false
        }

    @Test
    fun joinWritesFragmentsInOrderAtGrowingOffsets() =
        runBlocking<Unit> {
            serve(4)
            fetcher.fetch(folder, refs(4), null, CollectingIo(), trusted)
            val io = CollectingIo()

            fetcher.join(folder, 4, FileId(1), io) shouldBe StepResult.Ok(total(4))

            io.bytes(FileId(1)).contentEquals((0 until 4).map { bytesOf(it) }.reduce { a, b -> a + b }) shouldBe true
        }

    @Test
    fun joinFailsWhenAFragmentIsMissing() =
        runBlocking<Unit> {
            serve(3)
            fetcher.fetch(folder, refs(3), null, CollectingIo(), trusted)
            onDisk(1).delete()

            failed(fetcher.join(folder, 3, FileId(1), CollectingIo())).message shouldContain "fragment 1"
        }
}
