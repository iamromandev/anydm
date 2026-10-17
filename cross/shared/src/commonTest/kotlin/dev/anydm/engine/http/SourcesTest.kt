package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferFile
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** RangeTransfer with more than one source: blocks shared between mirrors (HTTP-12). */
class SourcesTest {
    private val mib = 1L shl 20
    private val size = 10 * mib
    private val content = ByteArray(size.toInt()) { (it % 251).toByte() }
    private val segments = planBlocks(size, 4).mapIndexed { i, s -> Segment(SegmentId(i + 1L), FileId(1), s.start, s.end) }
    private val file = TransferFile(File(FileId(1), DownloadId(1), 0, "f.bin", size), segments, Path("/d/f.bin.1.anydm"), Path("/d/f.bin"))
    private val primary = Source("https://h/f.bin", emptyList(), "https://h:443")
    private val m2 = Source("https://m2/f.bin", emptyList(), "https://m2:443")

    private fun TestScope.transferOf(
        transport: FakeTransport,
        io: RecordingIo,
        slots: HostSlots = HostSlots { 4 },
    ) = RangeTransfer(transport, primary, io, slots, testScheduler.timeSource)

    private fun paced(every: Duration = 100.milliseconds): suspend (HttpRequest) -> HttpResponse =
        { request -> pacedRanges(content, finalUrl = request.url) { PacedBody(it, every = every) }(request) }

    private fun blockRequests(
        transport: FakeTransport,
        host: String,
    ) = transport.requests.count { it.url.startsWith("https://$host/") && it.rangeStart() != null }

    @Test
    fun twoSourcesShareTheBlocks() =
        runTest {
            val transport = FakeTransport(paced())
            val io = RecordingIo(size, segments)
            val transfer = transferOf(transport, io)
            transfer.addSource(m2)

            transfer.run(file) shouldBe StepResult.Ok(Unit)

            io.bytes shouldBe content
            blockRequests(transport, "h") shouldBeGreaterThan 0
            blockRequests(transport, "m2") shouldBeGreaterThan 0
        }

    @Test
    fun aSourceAddedLateJoinsWhileBlocksWait() =
        runTest {
            val transport = FakeTransport(paced())
            val io = RecordingIo(size, segments)
            val transfer = transferOf(transport, io)
            val run = async { transfer.run(file) }
            advanceTimeBy(1500.milliseconds)
            blockRequests(transport, "m2") shouldBe 0

            transfer.addSource(m2)

            run.await() shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            blockRequests(transport, "m2") shouldBeGreaterThan 0
        }

    @Test
    fun aSourceAddedAfterTheRunIsIgnored() =
        runTest {
            val transport = FakeTransport(paced())
            val transfer = transferOf(transport, RecordingIo(size, segments))
            transfer.run(file) shouldBe StepResult.Ok(Unit)

            transfer.addSource(m2)
            runCurrent()

            blockRequests(transport, "m2") shouldBe 0
        }

    @Test
    fun aSecondarysFailuresCountTowardItsBlock() =
        runTest {
            val slow = paced(every = 1.seconds)
            val transport =
                FakeTransport { request ->
                    if (request.url.startsWith("https://m2/")) response(503, finalUrl = request.url) else slow(request)
                }
            val transfer = transferOf(transport, RecordingIo(size, segments))
            transfer.addSource(m2)

            transfer.run(file) shouldBe StepResult.Failed(ErrorClass.TRANSIENT, "HTTP 503 from m2")

            val failed = transport.requests.filter { it.url.startsWith("https://m2/") }
            failed.size shouldBe ATTEMPTS
            failed.map { it.rangeStart() }.toSet().size shouldBe 1
        }

    @Test
    fun eachSourceHoldsSlotsOfItsOwnOrigin() =
        runTest {
            val bodies = mutableMapOf<String, MutableList<PacedBody>>()
            val transport =
                FakeTransport { request ->
                    val host = hostOf(request.url)
                    pacedRanges(content, finalUrl = request.url) {
                        PacedBody(it, every = 100.milliseconds).also { body -> bodies.getOrPut(host) { mutableListOf() } += body }
                    }(request)
                }
            val transfer = transferOf(transport, RecordingIo(size, segments), HostSlots { 1 })
            transfer.addSource(m2)
            val run = async { transfer.run(file) }
            var both = false
            repeat(40) {
                advanceTimeBy(250.milliseconds)
                runCurrent()
                val open = bodies.mapValues { (_, list) -> list.count { !it.closed } }
                open.values.forEach { it shouldBeLessThanOrEqual 1 }
                if (open["h"] == 1 && open["m2"] == 1) both = true
            }
            run.await() shouldBe StepResult.Ok(Unit)
            both shouldBe true
        }
}
