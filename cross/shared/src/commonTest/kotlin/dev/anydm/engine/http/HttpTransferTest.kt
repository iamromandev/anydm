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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

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
    ): Transfer {
        val file = File(FileId(1), DownloadId(1), 0, "f.bin", size)
        val probe = HttpProbe("f.bin", size, null, true, "https://h/f.bin", null, context)
        val mirror = Mirror(MirrorId(1), DownloadId(1), "https://h/f.bin", 0)
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

    private class HangingBody(
        private val bytes: ByteArray,
    ) : HttpBody {
        var closed = false
        private var sent = false

        override suspend fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (sent) awaitCancellation()
            sent = true
            val n = minOf(length, bytes.size)
            bytes.copyInto(into, offset, 0, n)
            return n
        }

        override fun close() {
            closed = true
        }
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
            bodies.size shouldBe 4
            val writes = io.written.size
            job.cancelAndJoin()
            bodies.all { it.closed } shouldBe true
            io.written.size shouldBe writes
        }
}
