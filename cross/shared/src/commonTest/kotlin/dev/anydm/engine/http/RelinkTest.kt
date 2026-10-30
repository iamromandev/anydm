package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.aDownload
import dev.anydm.engine.protocol.RelinkRequest
import dev.anydm.engine.protocol.Relinked
import dev.anydm.engine.protocol.StepResult
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** HttpProtocol.relink: a new link is accepted only if sample ranges equal the partial's bytes (HTTP-13). */
class RelinkTest {
    private val mib = 1L shl 20
    private val size = 10 * mib
    private val old = ByteArray(size.toInt()) { (it % 251).toByte() }
    private val download = aDownload().copy(size = size)
    private val mirror = Mirror(MirrorId(1), download.id, "https://h1/f.bin", 0)
    private val newUrl = "https://h2/f.bin"
    private val written = listOf(0L until 3 * mib, 5 * mib until 9 * mib)

    private fun requestFor(
        stored: FakeStoredBytes = FakeStoredBytes(old, written),
        url: String = newUrl,
        job: dev.anydm.engine.core.Download = download,
    ) = RelinkRequest(job, mirror, url, stored = stored)

    private fun FakeTransport.ranges() = requests.map { r -> r.headers.firstOrNull { it.first == "Range" }?.second }

    private fun alwaysWhole(bytes: ByteArray): suspend (HttpRequest) -> HttpResponse =
        { _ -> response(200, "Content-Length" to "${bytes.size}", "ETag" to "\"v2\"", finalUrl = newUrl, body = bytesBody(bytes)) }

    @Test
    fun sameBytesAreAccepted() =
        runTest {
            val transport = FakeTransport(servingRanges(old.copyOf(), newUrl, etag = "\"v2\""))

            HttpProtocol(transport).relink(requestFor()) shouldBe StepResult.Ok(Relinked("etag:\"v2\""))

            transport.ranges() shouldBe listOf("bytes=0-1023", "bytes=0-65535", "bytes=7307264-7372799")
        }

    @Test
    fun differentBytesInTheFirstSampleAreRefused() =
        runTest {
            val new = old.copyOf().also { it[100]++ }
            HttpProtocol(FakeTransport(servingRanges(new, newUrl, etag = "\"v2\""))).relink(requestFor()) shouldBe
                StepResult.Failed(null, "new link's bytes differ near offset 100")
        }

    @Test
    fun differentBytesInTheSecondSampleAreRefused() =
        runTest {
            val new = old.copyOf().also { it[7340032]++ }
            HttpProtocol(FakeTransport(servingRanges(new, newUrl, etag = "\"v2\""))).relink(requestFor()) shouldBe
                StepResult.Failed(null, "new link's bytes differ near offset 7340032")
        }

    @Test
    fun anotherSizeIsRefused() =
        runTest {
            val new = old.copyOf(9 * mib.toInt())
            HttpProtocol(FakeTransport(servingRanges(new, newUrl))).relink(requestFor()) shouldBe
                StepResult.Failed(null, "new link is 9437184 bytes, not 10485760")
        }

    @Test
    fun nothingWrittenIsAcceptedAfterTheProbe() =
        runTest {
            val transport = FakeTransport(servingRanges(old.copyOf(), newUrl, etag = "\"v2\""))

            HttpProtocol(transport).relink(requestFor(FakeStoredBytes(old, emptyList()))) shouldBe StepResult.Ok(Relinked("etag:\"v2\""))

            transport.requests.size shouldBe 1
        }

    @Test
    fun aServerIgnoringRangesIsComparedOnTheFirstSample() =
        runTest {
            val transport = FakeTransport(alwaysWhole(old.copyOf()))

            HttpProtocol(transport).relink(requestFor()) shouldBe StepResult.Ok(Relinked("etag:\"v2\""))

            transport.ranges() shouldBe listOf("bytes=0-1023", null)
        }

    @Test
    fun aServerIgnoringRangesWithNothingAtTheStartIsRefused() =
        runTest {
            val stored = FakeStoredBytes(old, listOf(5 * mib until 6 * mib))
            HttpProtocol(FakeTransport(alwaysWhole(old.copyOf()))).relink(requestFor(stored)) shouldBe
                StepResult.Failed(null, "can't compare: h2 ignores ranges and nothing is written at the start")
        }

    @Test
    fun aGoneLinkIsGone() =
        runTest {
            HttpProtocol(FakeTransport { response(404, finalUrl = newUrl) }).relink(requestFor()) shouldBe
                StepResult.Failed(ErrorClass.GONE, "HTTP 404 from h2")
        }

    @Test
    fun aWrongSampleContentRangeIsRefused() =
        runTest {
            val serve = servingRanges(old.copyOf(), newUrl)
            val transport =
                FakeTransport { request ->
                    if (request.rangeStart() == 0L && request.headers.any { it.second == "bytes=0-1023" }) {
                        serve(request)
                    } else {
                        response(
                            206,
                            "Content-Range" to "bytes 1-65536/$size",
                            finalUrl = newUrl,
                            body = bytesBody(old.copyOfRange(1, 65537)),
                        )
                    }
                }
            HttpProtocol(transport).relink(requestFor()) shouldBe StepResult.Failed(null, "new link answered the sample range wrongly")
        }

    @Test
    fun aStoredReadFailureIsReturned() =
        runTest {
            val stored =
                FakeStoredBytes(old, written).also {
                    it.failRead =
                        StepResult.Failed(null, "partial is shorter than its watermarks")
                }
            HttpProtocol(FakeTransport(servingRanges(old.copyOf(), newUrl))).relink(requestFor(stored)) shouldBe
                StepResult.Failed(null, "partial is shorter than its watermarks")
        }

    @Test
    fun aSmallFileComparesOneSample() =
        runTest {
            val small = old.copyOf(40 * 1024)
            val transport = FakeTransport(servingRanges(small.copyOf(), newUrl))
            val stored = FakeStoredBytes(small, listOf(0L until 40 * 1024L))

            HttpProtocol(transport).relink(requestFor(stored, job = download.copy(size = 40 * 1024L))) shouldBe
                StepResult.Ok(Relinked(null))

            transport.ranges() shouldBe listOf("bytes=0-1023", "bytes=0-40959")
        }

    @Test
    fun samplesComeFromTheRedirectTarget() =
        runTest {
            val transport = FakeTransport(servingRanges(old.copyOf(), newUrl))

            HttpProtocol(transport).relink(requestFor(url = "https://h1/x")) shouldBe StepResult.Ok(Relinked(null))

            transport.requests.map { it.url } shouldBe listOf("https://h1/x", newUrl, newUrl)
        }

    @Test
    fun everyBodyIsClosed() =
        runTest {
            val cases =
                mapOf(
                    "accepted" to old.copyOf(),
                    "refused" to old.copyOf().also { it[100]++ },
                )
            for ((case, new) in cases) {
                for (ranged in listOf(true, false)) {
                    withClue("$case, ranges: $ranged") {
                        val bodies = mutableListOf<BytesBody>()
                        val serve = if (ranged) servingRanges(new, newUrl) else alwaysWhole(new)
                        val transport = FakeTransport { request -> serve(request).also { bodies += it.body as BytesBody } }
                        HttpProtocol(transport).relink(requestFor())
                        bodies.all { it.closed } shouldBe true
                    }
                }
            }
        }
}
