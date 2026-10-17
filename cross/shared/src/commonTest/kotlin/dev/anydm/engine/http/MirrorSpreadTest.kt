package dev.anydm.engine.http

import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.aDownload
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** HttpProtocol.transfer spreads blocks across mirrors whose size and strong ETag match the probe (HTTP-12). */
class MirrorSpreadTest {
    private val mib = 1L shl 20
    private val size = 10 * mib
    private val content = ByteArray(size.toInt()) { (it % 251).toByte() }
    private val segments = planBlocks(size, 4).mapIndexed { i, s -> Segment(SegmentId(i + 1L), FileId(1), s.start, s.end) }
    private val context = RequestContext(cookies = "s=1")
    private val primaryUrl = "https://h/f.bin"

    private fun transferOf(
        mirrors: List<String>,
        validator: String? = "etag:\"v1\"",
    ): Transfer {
        val download = aDownload()
        val file = File(FileId(1), download.id, 0, "f.bin", size)
        val probe = HttpProbe("f.bin", size, validator, true, primaryUrl, null, context)
        val own = Mirror(MirrorId(1), download.id, primaryUrl, 0)
        val others = mirrors.mapIndexed { i, url -> Mirror(MirrorId(i + 2L), download.id, url, i + 1) }
        return Transfer(download, own, probe, listOf(TransferFile(file, segments, Path("/d/f.bin.1.anydm"), Path("/d/f.bin"))), others)
    }

    private fun TestScope.protocol(
        transport: FakeTransport,
        connections: Int = 4,
    ) = HttpProtocol(transport, connections = { connections }, timeSource = testScheduler.timeSource)

    /** Paced 206s of [bytes] with [etag], answered from [finalUrl] (the request's own url by default). */
    private fun serving(
        etag: String? = "\"v1\"",
        bytes: ByteArray = content,
        finalUrl: String? = null,
        opened: MutableList<PacedBody>? = null,
    ): suspend (HttpRequest) -> HttpResponse =
        { request ->
            pacedRanges(bytes, finalUrl = finalUrl ?: request.url, etag = etag) {
                PacedBody(it, every = 100.milliseconds).also { body -> opened?.add(body) }
            }(request)
        }

    private fun routed(vararg routes: Pair<String, suspend (HttpRequest) -> HttpResponse>) =
        FakeTransport { request -> routes.first { request.url == it.first }.second(request) }

    private fun FakeTransport.ranges(url: String) =
        requests.filter { it.url == url }.map { r -> r.headers.first { it.first == "Range" }.second }

    private fun FakeTransport.blocks(url: String) = ranges(url).count { it != "bytes=0-1023" }

    @Test
    fun matchingMirrorsShareBlocks() =
        runTest {
            val transport = routed(primaryUrl to serving(), "https://m2/f.bin" to serving())
            val io = RecordingIo(size, segments)

            protocol(transport).transfer(transferOf(listOf("https://m2/f.bin")), io) shouldBe StepResult.Ok(Unit)

            io.bytes shouldBe content
            transport.blocks(primaryUrl) shouldBeGreaterThan 0
            transport.blocks("https://m2/f.bin") shouldBeGreaterThan 0
        }

    @Test
    fun aMismatchedMirrorGetsOnlyItsProbe() =
        runTest {
            val variants =
                mapOf<String, suspend (HttpRequest) -> HttpResponse>(
                    "another ETag" to serving(etag = "\"v2\""),
                    "another size" to serving(bytes = content.copyOf(size.toInt() - 1)),
                    "a weak ETag" to serving(etag = "W/\"v1\""),
                    "no ranges" to { r -> response(200, "Content-Length" to "$size", "ETag" to "\"v1\"", finalUrl = r.url) },
                    "a 404" to { r -> response(404, finalUrl = r.url) },
                )
            for ((variant, m2) in variants) {
                withClue(variant) {
                    val transport = routed(primaryUrl to serving(), "https://m2/f.bin" to m2)
                    val io = RecordingIo(size, segments)
                    protocol(transport).transfer(transferOf(listOf("https://m2/f.bin")), io) shouldBe StepResult.Ok(Unit)
                    io.bytes shouldBe content
                    transport.ranges("https://m2/f.bin") shouldBe listOf("bytes=0-1023")
                }
            }
        }

    @Test
    fun withoutAStrongEtagNoMirrorIsProbed() =
        runTest {
            for (validator in listOf("etag:W/\"v1\"", "last-modified:Sun, 06 Nov 1994 08:49:37 GMT", null)) {
                withClue(validator) {
                    val transport = routed(primaryUrl to serving(), "https://m2/f.bin" to serving())
                    protocol(transport).transfer(transferOf(listOf("https://m2/f.bin"), validator), RecordingIo(size, segments)) shouldBe
                        StepResult.Ok(Unit)
                    transport.ranges("https://m2/f.bin") shouldBe emptyList()
                }
            }
        }

    @Test
    fun aSlowMirrorProbeDoesntHoldBackThePrimary() =
        runTest {
            val slow = serving()
            val transport =
                routed(
                    primaryUrl to serving(),
                    "https://m2/f.bin" to { r ->
                        delay(1.hours)
                        slow(r)
                    },
                )
            val start = currentTime

            protocol(transport).transfer(transferOf(listOf("https://m2/f.bin")), RecordingIo(size, segments)) shouldBe StepResult.Ok(Unit)

            currentTime - start shouldBeLessThan 60_000L
            transport.ranges("https://m2/f.bin") shouldBe listOf("bytes=0-1023")
        }

    @Test
    fun credentialsGoOnlyToTheChosenMirrorsOrigin() =
        runTest {
            val transport = routed(primaryUrl to serving(), "https://h/g.bin" to serving(), "https://m3/f.bin" to serving())

            protocol(transport).transfer(transferOf(listOf("https://h/g.bin", "https://m3/f.bin")), RecordingIo(size, segments)) shouldBe
                StepResult.Ok(Unit)

            transport.blocks("https://h/g.bin") shouldBeGreaterThan 0
            transport.blocks("https://m3/f.bin") shouldBeGreaterThan 0
            transport.requests.filter { it.url == "https://h/g.bin" }.forEach { r -> r.headers.any { it.first == "Cookie" } shouldBe true }
            transport.requests.filter { it.url == "https://m3/f.bin" }.forEach { r ->
                r.headers.any { it.first == "Cookie" } shouldBe false
            }
        }

    @Test
    fun aSecondaryRedirectedElsewhereGetsNoCredentials() =
        runTest {
            // The mirror is on the chosen mirror's origin, but answers from m3: its blocks go to m3 without the cookie.
            val transport =
                routed(
                    primaryUrl to serving(),
                    "https://h/r" to serving(finalUrl = "https://m3/f.bin"),
                    "https://m3/f.bin" to serving(),
                )

            protocol(transport).transfer(transferOf(listOf("https://h/r")), RecordingIo(size, segments)) shouldBe StepResult.Ok(Unit)

            transport.blocks("https://m3/f.bin") shouldBeGreaterThan 0
            transport.requests.filter { it.url == "https://m3/f.bin" }.forEach { r ->
                r.headers.any { it.first == "Cookie" } shouldBe false
            }
        }

    @Test
    fun aSecondaryOnTheSameHostSharesTheCap() =
        runTest {
            val opened = mutableListOf<PacedBody>()
            val transport = routed(primaryUrl to serving(opened = opened))
            val run = async { protocol(transport, connections = 2).transfer(transferOf(listOf(primaryUrl)), RecordingIo(size, segments)) }
            repeat(40) {
                advanceTimeBy(250.milliseconds)
                runCurrent()
                opened.count { !it.closed } shouldBeLessThanOrEqual 2
            }
            run.await() shouldBe StepResult.Ok(Unit)
        }
}
