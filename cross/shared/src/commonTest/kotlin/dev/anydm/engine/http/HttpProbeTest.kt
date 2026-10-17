package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.aDownload
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class HttpProbeTest {
    private fun request(
        url: String = "https://h/f.zip",
        context: RequestContext? = null,
    ) = ProbeRequest(aDownload(), Mirror(MirrorId(1), DownloadId(1), url, 0), context)

    private suspend fun probe(
        response: HttpResponse,
        context: RequestContext? = null,
    ): StepResult<*> = HttpProtocol(FakeTransport { response }).probe(request(context = context))

    private suspend fun probed(
        response: HttpResponse,
        context: RequestContext? = null,
    ): HttpProbe = probe(response, context).shouldBeInstanceOf<StepResult.Ok<HttpProbe>>().value

    @Test
    fun aRangedReplyGivesSizeAndRanges() =
        runTest {
            val transport = FakeTransport { response(206, "Content-Range" to "bytes 0-1023/5000") }
            val probe = (HttpProtocol(transport).probe(request()) as StepResult.Ok).value as HttpProbe
            probe.size shouldBe 5000
            probe.rangesSupported shouldBe true
            val sent = transport.requests.single()
            sent.method shouldBe "GET"
            sent.url shouldBe "https://h/f.zip"
            sent.headers shouldContain ("Range" to "bytes=0-1023")
        }

    @Test
    fun anUnknownTotalKeepsRanges() =
        runTest {
            val probe = probed(response(206, "Content-Range" to "bytes 0-1023/*"))
            probe.size.shouldBeNull()
            probe.rangesSupported shouldBe true
        }

    @Test
    fun aPlainReplyMeansNoRanges() =
        runTest {
            val probe = probed(response(200, "Content-Length" to "5000"))
            probe.size shouldBe 5000
            probe.rangesSupported shouldBe false
            probed(response(200, "Content-Length" to "5000", "Content-Encoding" to "identity")).size shouldBe 5000
            probed(response(200)).size.shouldBeNull()
        }

    @Test
    fun aCompressedPlainReplyHasNoSize() =
        runTest {
            probed(response(200, "Content-Length" to "300", "Content-Encoding" to "gzip")).size.shouldBeNull()
        }

    @Test
    fun anEmptyFileIsSizeZero() =
        runTest {
            val probe = probed(response(416, "Content-Range" to "bytes */0", body = bytesBody(ByteArray(0))))
            probe.size shouldBe 0
            probe.rangesSupported shouldBe true
        }

    @Test
    fun theValidatorPrefersETag() =
        runTest {
            val lm = "Wed, 21 Oct 2015 07:28:00 GMT"
            probed(response(200, "ETag" to "\"v1\"", "Last-Modified" to lm)).validator shouldBe "etag:\"v1\""
            probed(response(200, "Last-Modified" to lm)).validator shouldBe "last-modified:$lm"
            probed(response(200, "ETag" to "W/\"w\"")).validator shouldBe "etag:W/\"w\""
            probed(response(200)).validator.shouldBeNull()
        }

    @Test
    fun theContextIsSent() =
        runTest {
            val transport = FakeTransport { response(200) }
            val context =
                RequestContext(
                    headers = listOf("X-A" to "1"),
                    referrer = "https://r/",
                    userAgent = "UA",
                    cookies = "a=1; b=2",
                )
            HttpProtocol(transport).probe(request(context = context))
            val sent = transport.requests.single().headers
            sent shouldContain ("X-A" to "1")
            sent shouldContain ("Referer" to "https://r/")
            sent shouldContain ("User-Agent" to "UA")
            sent shouldContain ("Cookie" to "a=1; b=2")

            HttpProtocol(transport).probe(request())
            transport.requests.last().headers shouldContain ("User-Agent" to "anydm")
            HttpProtocol(transport, userAgent = "anydm/1.2").probe(request())
            transport.requests.last().headers shouldContain ("User-Agent" to "anydm/1.2")
        }

    @Test
    fun statusesMapToErrorClasses() =
        runTest {
            val expected =
                mapOf(
                    401 to ErrorClass.AUTH,
                    403 to ErrorClass.AUTH,
                    404 to ErrorClass.GONE,
                    410 to ErrorClass.GONE,
                    429 to ErrorClass.RATE_LIMITED,
                    408 to ErrorClass.TRANSIENT,
                    500 to ErrorClass.TRANSIENT,
                    503 to ErrorClass.TRANSIENT,
                    418 to null,
                )
            for ((status, errorClass) in expected) {
                withClue(status) {
                    val failed = probe(response(status)).shouldBeInstanceOf<StepResult.Failed>()
                    failed.errorClass shouldBe errorClass
                    failed.message shouldBe "HTTP $status from h"
                }
            }
        }

    @Test
    fun networkFailuresAreTransient() =
        runTest {
            val reset = FakeTransport { throw HttpIoException(HttpIoKind.RESET, "connection reset") }
            HttpProtocol(reset).probe(request()) shouldBe StepResult.Failed(ErrorClass.TRANSIENT, "connection reset")
            val loop = FakeTransport { throw HttpIoException(HttpIoKind.TOO_MANY_REDIRECTS, "more than 5 redirects") }
            HttpProtocol(loop).probe(request()) shouldBe StepResult.Failed(null, "more than 5 redirects")
        }

    @Test
    fun theFinalUrlAndNameComeFromTheResponse() =
        runTest {
            val probe =
                probed(response(200, "Content-Type" to "application/x-iso9660-image", finalUrl = "https://cdn/real/file.iso"))
            probe.finalUrl shouldBe "https://cdn/real/file.iso"
            probe.name shouldBe "file.iso"
            probe.contentType shouldBe "application/x-iso9660-image"
            probed(response(200, "Content-Disposition" to "attachment; filename=\"named.iso\"")).name shouldBe "named.iso"
        }

    // Review Focus

    @Test
    fun aMalformedContentRangeMeansUnknownSize() =
        runTest {
            for (headers in listOf(arrayOf("Content-Range" to "bytes 0-1023/abc"), arrayOf("Content-Range" to "nonsense"), arrayOf())) {
                withClue(headers.toList()) {
                    val probe = probed(response(206, *headers))
                    probe.size.shouldBeNull()
                    probe.rangesSupported shouldBe true
                }
            }
        }

    @Test
    fun theProbeReadsAtMostOneKiB() =
        runTest {
            val body = bytesBody(ByteArray(1 shl 20))
            probed(response(200, "Content-Length" to "${1 shl 20}", body = body))
            (body.read <= 1024) shouldBe true
            body.closed shouldBe true
        }

    @Test
    fun theProbeAsksForIdentity() =
        runTest {
            val transport = FakeTransport { response(200) }
            HttpProtocol(transport).probe(request())
            transport.requests.single().headers shouldContain ("Accept-Encoding" to "identity")
        }

    @Test
    fun theProbeKeepsTheContext() =
        runTest {
            val context = RequestContext(cookies = "a=1")
            probed(response(200), context).context shouldBe context
        }

    // Review Focus 5 (CR/LF in context values) is RequestContextTest: such a context can't be made.
}
