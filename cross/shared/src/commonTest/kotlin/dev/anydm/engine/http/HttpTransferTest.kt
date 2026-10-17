package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class HttpTransferTest {
    private val mib = 1L shl 20
    private val size = 10 * mib
    private val content = ByteArray(size.toInt()) { (it % 251).toByte() }
    private val context = RequestContext(headers = listOf("X-A" to "1"))
    private val segments = segmentsOf(size)
    private val block = segments.first().end

    private fun segmentsOf(
        size: Long,
        connections: Int = 4,
    ) = planBlocks(size, connections).mapIndexed { i, s -> Segment(SegmentId(i + 1L), FileId(1), s.start, s.end) }

    private fun transferOf(
        segments: List<Segment>,
        size: Long? = this.size,
        mirrorUrl: String = "https://h/f.bin",
        context: RequestContext = this.context,
    ): Transfer {
        val file = File(FileId(1), DownloadId(1), 0, "f.bin", size)
        val probe = HttpProbe("f.bin", size, null, true, "https://h/f.bin", null, context)
        val mirror = Mirror(MirrorId(1), DownloadId(1), mirrorUrl, 0)
        return Transfer(aDownload(), mirror, probe, listOf(TransferFile(file, segments, Path("/d/f.bin.1.anydm"), Path("/d/f.bin"))))
    }

    private suspend fun transfer(
        transport: FakeTransport,
        io: RecordingIo,
        segments: List<Segment> = this.segments,
        connections: Int = 4,
    ) = HttpProtocol(transport, connections = { connections }).transfer(transferOf(segments), io)

    private fun requestsFor(
        transport: FakeTransport,
        start: Long,
    ) = transport.requests.count { it.rangeStart() == start }

    @Test
    fun everyBlockIsFetchedOnce() =
        runTest {
            val transport = FakeTransport(servingRanges(content))
            val io = RecordingIo(size, segments)
            transfer(transport, io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            transport.requests.size shouldBe 10
            transport.requests.map { r -> r.headers.first { it.first == "Range" }.second }.toSet() shouldBe
                segments.map { "bytes=${it.start}-${it.end - 1}" }.toSet()
            for (request in transport.requests) {
                request.url shouldBe "https://h/f.bin"
                request.headers shouldContain ("Accept-Encoding" to "identity")
                request.headers shouldContain ("X-A" to "1")
            }
        }

    @Test
    fun aResumeFetchesOnlyWhatIsMissing() =
        runTest {
            val resumed =
                segments.mapIndexed { i, s ->
                    when (i) {
                        0 -> s.copy(watermark = s.start + 1000)
                        1 -> s.copy(watermark = s.end)
                        else -> s
                    }
                }
            val transport = FakeTransport(servingRanges(content))
            val io = RecordingIo(size, resumed)
            transfer(transport, io, resumed) shouldBe StepResult.Ok(Unit)
            transport.requests.map { it.rangeStart() } shouldContain 1000L
            requestsFor(transport, 0) shouldBe 0
            requestsFor(transport, resumed[1].start) shouldBe 0
            transport.requests.size shouldBe 9
            io.bytes.copyOfRange(1000, block.toInt()) shouldBe content.copyOfRange(1000, block.toInt())
        }

    @Test
    fun aCompleteFileMakesNoRequest() =
        runTest {
            val done = segments.map { it.copy(watermark = it.end) }
            val transport = FakeTransport(servingRanges(content))
            transfer(transport, RecordingIo(size, done), done) shouldBe StepResult.Ok(Unit)
            transport.requests.size shouldBe 0
        }

    @Test
    fun aWrongContentRangeIsRetriedThenFails() =
        runTest {
            val serve = servingRanges(content)
            val transport =
                FakeTransport { request ->
                    if (request.rangeStart() == 0L) {
                        response(
                            206,
                            "Content-Range" to "bytes 1-$block/$size",
                            body = bytesBody(content.copyOfRange(1, block.toInt() + 1)),
                        )
                    } else {
                        serve(request)
                    }
                }
            val io = RecordingIo(size, segments)
            val failed = transfer(transport, io).shouldBeInstanceOf<StepResult.Failed>()
            failed.errorClass shouldBe ErrorClass.TRANSIENT
            requestsFor(transport, 0) shouldBe 3
            io.written.none { it.first < block } shouldBe true
        }

    @Test
    fun aShortBodyIsRetried() =
        runTest {
            val serve = servingRanges(content)
            var first = true
            val transport =
                FakeTransport { request ->
                    if (request.rangeStart() == 0L && first) {
                        first = false
                        response(
                            206,
                            "Content-Range" to "bytes 0-${block - 1}/$size",
                            body = bytesBody(content.copyOfRange(0, (block / 2).toInt())),
                        )
                    } else {
                        serve(request)
                    }
                }
            val io = RecordingIo(size, segments)
            transfer(transport, io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            transport.requests.map { it.rangeStart() } shouldContain block / 2
        }

    @Test
    fun aServerErrorIsRetried() =
        runTest {
            val serve = servingRanges(content)
            var failedOnce = false
            val transport =
                FakeTransport { request ->
                    if (request.rangeStart() == 0L && !failedOnce) {
                        failedOnce = true
                        response(503)
                    } else {
                        serve(request)
                    }
                }
            val io = RecordingIo(size, segments)
            transfer(transport, io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
        }

    @Test
    fun aMissingFileFailsAtOnce() =
        runTest {
            val serve = servingRanges(content)
            val transport = FakeTransport { request -> if (request.rangeStart() == 0L) response(404) else serve(request) }
            transfer(transport, RecordingIo(size, segments)) shouldBe StepResult.Failed(ErrorClass.GONE, "HTTP 404 from h")
            requestsFor(transport, 0) shouldBe 1
        }

    @Test
    fun statusesEndTheRun() =
        runTest {
            val expected = mapOf(401 to ErrorClass.AUTH, 429 to ErrorClass.RATE_LIMITED, 416 to ErrorClass.TRANSIENT, 418 to null)
            for ((status, errorClass) in expected) {
                withClue(status) {
                    val transport = FakeTransport { response(status) }
                    val failed = transfer(transport, RecordingIo(size, segments)).shouldBeInstanceOf<StepResult.Failed>()
                    failed.errorClass shouldBe errorClass
                    failed.message shouldBe "HTTP $status from h"
                    transport.requests
                        .groupBy { it.rangeStart() }
                        .values
                        .all { it.size == 1 } shouldBe true
                }
            }
        }

    @Test
    fun a503WithRetryAfterEndsTheRun() =
        runTest {
            val serve = servingRanges(content)
            val transport = FakeTransport { r -> if (r.rangeStart() == 0L) response(503, "Retry-After" to "2") else serve(r) }
            transfer(transport, RecordingIo(size, segments)) shouldBe StepResult.Failed(ErrorClass.TRANSIENT, "HTTP 503 from h", 2.seconds)
            requestsFor(transport, 0) shouldBe 1
        }

    @Test
    fun a503WithRetryAfterZeroIsRetriedInPlace() =
        runTest {
            val serve = servingRanges(content)
            var failedOnce = false
            val transport =
                FakeTransport { r ->
                    if (r.rangeStart() == 0L && !failedOnce) {
                        failedOnce = true
                        response(503, "Retry-After" to "0")
                    } else {
                        serve(r)
                    }
                }
            val io = RecordingIo(size, segments)
            transfer(transport, io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
        }

    @Test
    fun a503WithRetryAfterKeepsWatermarks() =
        runTest {
            val serve = servingRanges(content)
            val transport =
                FakeTransport { r -> if (r.rangeStart() == segments[1].start) response(503, "Retry-After" to "2") else serve(r) }
            val io = RecordingIo(size, segments)
            transfer(transport, io, connections = 1) shouldBe StepResult.Failed(ErrorClass.TRANSIENT, "HTTP 503 from h", 2.seconds)
            io.lastWatermark(segments[0].id) shouldBe segments[0].end
            requestsFor(transport, segments[1].start) shouldBe 1
        }

    @Test
    fun a429CarriesRetryAfter() =
        runTest {
            transfer(FakeTransport { response(429, "Retry-After" to "30") }, RecordingIo(size, segments)) shouldBe
                StepResult.Failed(ErrorClass.RATE_LIMITED, "HTTP 429 from h", 30.seconds)
        }

    @Test
    fun permanentNetworkFailuresEndTheRun() =
        runTest {
            for (kind in listOf(HttpIoKind.TLS, HttpIoKind.TOO_MANY_REDIRECTS, HttpIoKind.BAD_URL)) {
                withClue(kind) {
                    val transport = FakeTransport { throw HttpIoException(kind, "h") }
                    val failed = transfer(transport, RecordingIo(size, segments)).shouldBeInstanceOf<StepResult.Failed>()
                    failed.errorClass shouldBe null
                    transport.requests
                        .groupBy { it.rangeStart() }
                        .values
                        .all { it.size == 1 } shouldBe true
                }
            }
        }

    @Test
    fun aFullDiskEndsTheTransfer() =
        runTest {
            val io = RecordingIo(size, segments).apply { failWriteAt = 3 * mib }
            transfer(FakeTransport(servingRanges(content)), io) shouldBeSameInstanceAs io.diskFull
        }

    @Test
    fun watermarksFollowWrites() =
        runTest {
            val io = RecordingIo(size, segments)
            transfer(FakeTransport(servingRanges(content)), io) shouldBe StepResult.Ok(Unit)
            for (segment in segments) io.lastWatermark(segment.id) shouldBe segment.end
        }

    @Test
    fun theLimiterAndMeterSeeEveryByte() =
        runTest {
            val io = RecordingIo(size, segments)
            transfer(FakeTransport(servingRanges(content)), io) shouldBe StepResult.Ok(Unit)
            io.limiter.bytes shouldBe size
            io.meter.bytes shouldBe size
            io.written.all { it.last - it.first + 1 <= 64 * 1024 } shouldBe true
        }

    // Review Focus

    @Test
    fun parallelWorkersReportExactProgress() =
        runTest {
            val serve = servingRanges(content)
            val transport =
                FakeTransport { request ->
                    delay(10L * ((request.rangeStart() ?: 0) / block % 3))
                    serve(request)
                }
            val io = RecordingIo(size, segments)
            transfer(transport, io) shouldBe StepResult.Ok(Unit)
            io.progress.zipWithNext().all { (a, b) -> a <= b } shouldBe true
            io.progress.last() shouldBe size
        }

    @Test
    fun bytesPastTheRangeAreNotWritten() =
        runTest {
            val serve = servingRanges(content)
            val transport =
                FakeTransport { request ->
                    if (request.rangeStart() == 0L) {
                        response(
                            206,
                            "Content-Range" to "bytes 0-${block - 1}/$size",
                            body =
                                bytesBody(
                                    content.copyOfRange(
                                        0,
                                        block.toInt() + 100,
                                    ),
                                ),
                        )
                    } else {
                        serve(request)
                    }
                }
            val io = RecordingIo(size, segments)
            transfer(transport, io) shouldBe StepResult.Ok(Unit)
            io.written.all { r -> segments.any { r.first >= it.start && r.last < it.end } } shouldBe true
            io.bytes shouldBe content
        }

    @Test
    fun aContentRangeForAnotherSizeIsNotWritten() =
        runTest {
            val serve = servingRanges(content)
            val transport =
                FakeTransport { request ->
                    if (request.rangeStart() == 0L) {
                        response(
                            206,
                            "Content-Range" to "bytes 0-${block - 1}/${size + 1}",
                            body = bytesBody(content.copyOfRange(0, block.toInt())),
                        )
                    } else {
                        serve(request)
                    }
                }
            val io = RecordingIo(size, segments)
            transfer(transport, io).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.TRANSIENT
            io.written.none { it.first < block } shouldBe true
        }

    @Test
    fun failuresOnDifferentBlocksDontAddUp() =
        runTest {
            val serve = servingRanges(content)
            val failures = mutableMapOf<Long, Int>()
            val transport =
                FakeTransport { request ->
                    val start = request.rangeStart()!!
                    if (start < 4 * block && failures.getOrElse(start) { 0 } < 2) {
                        failures[start] = failures.getOrElse(start) { 0 } + 1
                        response(503)
                    } else {
                        serve(request)
                    }
                }
            val io = RecordingIo(size, segments)
            transfer(transport, io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
        }

    @Test
    fun cancellingClosesEveryBody() =
        runTest {
            val bodies = mutableListOf<HangingBody>()
            val transport =
                FakeTransport { request ->
                    val start = request.rangeStart()!!
                    val end = segments.first { it.start == start }.end
                    val body = HangingBody(content.copyOfRange(start.toInt(), start.toInt() + 1024))
                    bodies += body
                    response(206, "Content-Range" to "bytes $start-${end - 1}/$size", body = body)
                }
            val io = RecordingIo(size, segments)
            val job = launch { transfer(transport, io) }
            runCurrent()
            bodies.isNotEmpty() shouldBe true
            val writes = io.written.size
            job.cancelAndJoin()
            bodies.all { it.closed } shouldBe true
            io.written.size shouldBe writes
        }

    // One stream: files without segments, and a server that ignores ranges

    private suspend fun streamed(
        transport: FakeTransport,
        io: RecordingIo,
        size: Long?,
    ) = HttpProtocol(transport).transfer(transferOf(emptyList(), size), io)

    @Test
    fun theStreamHonoursRetryAfter() =
        runTest {
            val transport = FakeTransport { response(503, "Retry-After" to "5") }
            streamed(transport, RecordingIo(size, emptyList()), size) shouldBe
                StepResult.Failed(ErrorClass.TRANSIENT, "HTTP 503 from h", 5.seconds)
            transport.requests.size shouldBe 1
        }

    @Test
    fun theStreamStopsOnAPermanentNetworkFailure() =
        runTest {
            val transport = FakeTransport { throw HttpIoException(HttpIoKind.TLS, "h") }
            streamed(transport, RecordingIo(size, emptyList()), size) shouldBe StepResult.Failed(null, "TLS handshake failed (h)")
            transport.requests.size shouldBe 1
        }

    @Test
    fun aFileWithoutSegmentsIsOneStream() =
        runTest {
            val transport = FakeTransport(servingRanges(content))
            val io = RecordingIo(size, emptyList())
            streamed(transport, io, size) shouldBe StepResult.Ok(Unit)
            transport.requests
                .single()
                .headers
                .none { it.first == "Range" } shouldBe true
            io.bytes shouldBe content
            io.checkpoints.size shouldBe 0
            io.progress.last() shouldBe size
        }

    @Test
    fun anUnknownSizeEndsWithTheStream() =
        runTest {
            val bytes = content.copyOf(3000)
            val io = RecordingIo(3000, emptyList())
            streamed(FakeTransport { response(200, body = bytesBody(bytes)) }, io, null) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe bytes
            io.progress.last() shouldBe 3000
        }

    @Test
    fun aStreamShorterThanTheSizeIsRetried() =
        runTest {
            var calls = 0
            val retried =
                FakeTransport {
                    val body = if (calls++ == 0) content.copyOf(1000) else content
                    response(200, body = bytesBody(body))
                }
            val io = RecordingIo(size, emptyList())
            streamed(retried, io, size) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            retried.requests.size shouldBe 2

            val short = FakeTransport { response(200, body = bytesBody(content.copyOf(1000))) }
            streamed(short, RecordingIo(size, emptyList()), size).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe
                ErrorClass.TRANSIENT
            short.requests.size shouldBe 3
        }

    @Test
    fun anEmptyFileMakesNoRequest() =
        runTest {
            val transport = FakeTransport { error("no requests") }
            streamed(transport, RecordingIo(0, emptyList()), 0) shouldBe StepResult.Ok(Unit)
            transport.requests.size shouldBe 0
        }

    private val small = 4 * mib
    private val smallContent = content.copyOf(small.toInt())

    @Test
    fun aServerThatIgnoresRangesSwitchesToOneStream() =
        runTest {
            val blocks = segmentsOf(small)
            blocks.size shouldBe 4
            val half = blocks[1].start + (blocks[1].end - blocks[1].start) / 2
            val resumed = listOf(blocks[0].copy(watermark = blocks[0].end), blocks[1].copy(watermark = half)) + blocks.drop(2)
            val bodies = mutableListOf<BytesBody>()
            val transport =
                FakeTransport {
                    bytesBody(smallContent).also { bodies += it }.let { response(200, body = it) }
                }
            val io = RecordingIo(small, resumed)
            HttpProtocol(transport).transfer(transferOf(resumed, small), io) shouldBe StepResult.Ok(Unit)
            bodies.count { it.read == smallContent.size } shouldBe 1
            bodies.all { it.closed } shouldBe true
            io.bytes.copyOfRange(half.toInt(), small.toInt()) shouldBe smallContent.copyOfRange(half.toInt(), small.toInt())
            io.written.none { it.first < half } shouldBe true
            for (block in resumed) io.lastWatermark(block.id)?.let { it shouldBe block.end }
            for (block in resumed.drop(1)) io.lastWatermark(block.id) shouldBe block.end
        }

    @Test
    fun theOneStreamIsRetriedAsAStream() =
        runTest {
            val blocks = segmentsOf(small)
            var plain = 0
            val transport =
                FakeTransport { request ->
                    if (request.rangeStart() != null) {
                        response(200, body = bytesBody(smallContent.copyOf(1000)))
                    } else {
                        plain++
                        response(200, body = bytesBody(smallContent))
                    }
                }
            val io = RecordingIo(small, blocks)
            HttpProtocol(transport).transfer(transferOf(blocks, small), io) shouldBe StepResult.Ok(Unit)
            plain shouldBe 1
            io.bytes shouldBe smallContent
        }

    @Test
    fun finalizeReturnsThePath() =
        runTest {
            HttpProtocol(FakeTransport { error("none") }).finalize(transferOf(segments)) shouldBe
                StepResult.Ok(
                    dev.anydm.engine.protocol
                        .FinalizeResult(listOf("f.bin")),
                )
        }

    // Final review

    @Test
    fun credentialsStayOnTheMirrorsOrigin() =
        runTest {
            val secret = RequestContext(headers = listOf("Authorization" to "Bearer t", "X-A" to "1"), cookies = "session=s")
            val moved = FakeTransport(servingRanges(content))
            val io = RecordingIo(size, segments)
            HttpProtocol(moved).transfer(transferOf(segments, mirrorUrl = "https://site.example/dl?id=1", context = secret), io) shouldBe
                StepResult.Ok(Unit)
            for (request in moved.requests) {
                request.headers.none { it.first == "Cookie" || it.first == "Authorization" } shouldBe true
                request.headers shouldContain ("X-A" to "1")
            }
            val same = FakeTransport(servingRanges(content))
            HttpProtocol(same).transfer(transferOf(segments, context = secret), RecordingIo(size, segments)) shouldBe StepResult.Ok(Unit)
            same.requests.all { ("Cookie" to "session=s") in it.headers && ("Authorization" to "Bearer t") in it.headers } shouldBe true
        }

    @Test
    fun aLongerStreamFails() =
        runTest {
            val io = RecordingIo(5000, emptyList())
            val transport = FakeTransport { response(200, body = bytesBody(content.copyOf(10_000))) }
            streamed(transport, io, 5000).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.TRANSIENT
            transport.requests.size shouldBe 1
        }

    @Test
    fun aWholeBodyOfAnotherSizeIsNotWritten() =
        runTest {
            val blocks = segmentsOf(small)
            val other = content.copyOf(8 * mib.toInt())
            val transport = FakeTransport { response(200, "Content-Length" to "${other.size}", body = bytesBody(other)) }
            val io = RecordingIo(small, blocks)
            HttpProtocol(transport).transfer(transferOf(blocks, small), io).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe
                ErrorClass.TRANSIENT
            io.written.size shouldBe 0
        }

    @Test
    fun aDeadServerIsNotHammered() =
        runTest {
            val big = 40 * mib
            val blocks = segmentsOf(big)
            blocks.size shouldBe 16
            val transport = FakeTransport { response(503) }
            HttpProtocol(transport, connections = { 4 })
                .transfer(transferOf(blocks, big), RecordingIo(big, blocks))
                .shouldBeInstanceOf<StepResult.Failed>()
                .errorClass shouldBe ErrorClass.TRANSIENT
            (transport.requests.size <= ATTEMPTS * 4) shouldBe true
        }
}
