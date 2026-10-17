package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.aDownload
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Four blocks of 1 MiB; block 0 is the one that gets slow. Requests are told apart by their order within block 0. */
class RaceTest {
    private val mib = 1L shl 20
    private val size = 4 * mib
    private val content = ByteArray(size.toInt()) { (it % 251).toByte() }
    private val segments = planBlocks(size, 4).mapIndexed { i, s -> Segment(SegmentId(i + 1L), FileId(1), s.start, s.end) }
    private val fast = 62.milliseconds // 64 KiB each: about 1 MiB/s

    private fun transferOf(segments: List<Segment> = this.segments): Transfer {
        val file = File(FileId(1), DownloadId(1), 0, "f.bin", size)
        val probe = HttpProbe("f.bin", size, null, true, "https://h/f.bin", null)
        val mirror = Mirror(MirrorId(1), DownloadId(1), "https://h/f.bin", 0)
        return Transfer(aDownload(), mirror, probe, listOf(TransferFile(file, segments, Path("/d/f.bin.1.anydm"), Path("/d/f.bin"))))
    }

    private fun TestScope.protocol(transport: FakeTransport) =
        HttpProtocol(transport, connections = { 4 }, timeSource = testScheduler.timeSource)

    /** Serves every block with [others], and the n-th request inside block 0 (from 1) with [block0]. */
    private class Script(
        private val content: ByteArray,
        val block0: (n: Int, bytes: ByteArray) -> HttpBody?,
        val others: (ByteArray) -> HttpBody,
    ) {
        val bodies = mutableListOf<HttpBody>()
        val block0Starts = mutableListOf<Long>()

        fun transport() =
            FakeTransport { request ->
                val start = request.rangeStart()!!
                val last =
                    request.headers
                        .first { it.first == "Range" }
                        .second
                        .substringAfter('-')
                        .toInt()
                val bytes = content.copyOfRange(start.toInt(), last + 1)
                val body =
                    if (start < (1 shl 20)) {
                        block0Starts += start
                        block0(block0Starts.size, bytes) ?: return@FakeTransport response(503)
                    } else {
                        others(bytes)
                    }
                bodies += body
                response(206, "Content-Range" to "bytes $start-$last/${content.size}", body = body)
            }
    }

    private fun paced(
        bytes: ByteArray,
        every: Duration,
    ) = PacedBody(bytes, every)

    @Test
    fun aStalledBlockIsRacedAndTheRacerWins() =
        runTest(timeout = 15.seconds) {
            var hanging: HangingBody? = null
            val script =
                Script(content, { n, bytes ->
                    if (n ==
                        1
                    ) {
                        HangingBody(bytes.copyOf(64 * 1024)).also { hanging = it }
                    } else {
                        paced(bytes, fast)
                    }
                }) {
                    paced(it, fast)
                }
            val io = RecordingIo(size, segments)
            protocol(script.transport()).transfer(transferOf(), io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            hanging!!.closed shouldBe true
            script.block0Starts shouldBe listOf(0L, 64L * 1024)
        }

    @Test
    fun whenTheOriginalWinsNoRacerByteLands() =
        runTest(timeout = 15.seconds) {
            var racer: PacedBody? = null
            val script =
                Script(content, { n, bytes ->
                    when (n) {
                        1 -> paced(bytes, 500.milliseconds)

                        // 128 KiB/s
                        else -> PacedBody(ByteArray(bytes.size) { 0x5A }, 1.seconds).also { racer = it }
                    }
                }) { paced(it, fast) }
            val io = RecordingIo(size, segments)
            protocol(script.transport()).transfer(transferOf(), io) shouldBe StepResult.Ok(Unit)
            script.block0Starts.size shouldBe 2
            io.bytes shouldBe content
            racer!!.closed shouldBe true
        }

    @Test
    fun smallBlocksAreNotRaced() =
        runTest(timeout = 15.seconds) {
            val left = 200L * 1024
            val resumed = segments.mapIndexed { i, s -> if (i == 0) s.copy(watermark = s.end - left) else s }
            val script = Script(content, { _, bytes -> paced(bytes, 500.milliseconds) }) { paced(it, fast) }
            val io = RecordingIo(size, resumed)
            protocol(script.transport()).transfer(transferOf(resumed), io) shouldBe StepResult.Ok(Unit)
            script.block0Starts.size shouldBe 1
        }

    @Test
    fun aBlockOnlyALittleSlowerIsNotRaced() =
        runTest(timeout = 15.seconds) {
            val script = Script(content, { _, bytes -> paced(bytes, 90.milliseconds) }) { paced(it, fast) }
            protocol(script.transport()).transfer(transferOf(), RecordingIo(size, segments)) shouldBe StepResult.Ok(Unit)
            script.block0Starts.size shouldBe 1
        }

    @Test
    fun aFailingRacerCostsNoAttempt() =
        runTest(timeout = 15.seconds) {
            val script =
                Script(content, { n, bytes ->
                    when (n) {
                        1 -> PacedBody(bytes, 500.milliseconds, failAfter = 512 * 1024)

                        2 -> null

                        // the racer: 503
                        3 -> PacedBody(bytes, fast, failAfter = 0)

                        // the original's retry fails at once
                        else -> paced(bytes, fast)
                    }
                }) { paced(it, fast) }
            val io = RecordingIo(size, segments)
            protocol(script.transport()).transfer(transferOf(), io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            script.block0Starts.size shouldBe 4
        }

    @Test
    fun oneRacerPerBlock() =
        runTest(timeout = 15.seconds) {
            val script =
                Script(content, { n, bytes -> if (n == 1) HangingBody(bytes.copyOf(64 * 1024)) else paced(bytes, 2.seconds) }) {
                    paced(it, fast)
                }
            val run = async { protocol(script.transport()).transfer(transferOf(), RecordingIo(size, segments)) }
            advanceTimeBy(8.seconds)
            runCurrent()
            script.block0Starts.size shouldBe 2
            run.cancelAndJoin()
        }

    // Review Focus

    @Test
    fun aTieWritesTheTailOnce() =
        runTest(timeout = 15.seconds) {
            val gate = CompletableDeferred<Unit>()
            val gated = mutableListOf<PacedBody>()
            val script =
                Script(content, { n, bytes ->
                    val every = if (n == 1) 500.milliseconds else fast
                    PacedBody(bytes, every, gate = gate).also { gated += it }
                }) { paced(it, fast) }
            val io = RecordingIo(size, segments)
            val run = async { protocol(script.transport()).transfer(transferOf(), io) }
            while (gated.size < 2 || !gated.all { it.atGate }) {
                advanceTimeBy(100.milliseconds)
                runCurrent()
            }
            gate.complete(Unit)
            run.await() shouldBe StepResult.Ok(Unit)
            val block = segments[0]
            val times = IntArray((block.end - block.start).toInt())
            for (r in io.written) for (at in r) if (at < block.end) times[at.toInt()]++
            times.all { it == 1 } shouldBe true
            io.lastWatermark(block.id) shouldBe block.end
            io.bytes shouldBe content
        }

    @Test
    fun aWinDuringTheOriginalsRetryCompletesTheBlock() =
        runTest(timeout = 15.seconds) {
            val script =
                Script(content, { n, bytes ->
                    when (n) {
                        1 -> PacedBody(bytes, 500.milliseconds, failAfter = 512 * 1024)

                        2 -> paced(bytes, 250.milliseconds)

                        // the racer: 256 KiB/s
                        else -> paced(bytes, 1.seconds) // the original's slow retry
                    }
                }) { paced(it, fast) }
            val io = RecordingIo(size, segments)
            protocol(script.transport()).transfer(transferOf(), io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            val block = segments[0]
            val times = IntArray((block.end - block.start).toInt())
            for (r in io.written) for (at in r) if (at < block.end) times[at.toInt()]++
            times.all { it == 1 } shouldBe true
        }

    @Test
    fun cancellingMidRaceClosesBoth() =
        runTest(timeout = 15.seconds) {
            val script = Script(content, { _, bytes -> HangingBody(bytes.copyOf(64 * 1024)) }) { paced(it, fast) }
            val protocol = protocol(script.transport())
            val run = async { protocol.transfer(transferOf(), RecordingIo(size, segments)) }
            while (script.block0Starts.size < 2) {
                advanceTimeBy(100.milliseconds)
                runCurrent()
            }
            run.cancelAndJoin()
            script.bodies.filterIsInstance<HangingBody>().all { it.closed } shouldBe true
            protocol.slots.held("https://h:443") shouldBe 0
        }

    // Task 4 found it end to end: the queue emptied while block 0 still looked fast, the idle worker left, and nobody
    // was there to race block 0 when it stalled.
    @Test
    fun aBlockThatStallsAfterTheQueueEmptiesIsRaced() =
        runTest(timeout = 15.seconds) {
            val script =
                Script(content, { n, bytes -> if (n == 1) PacedBody(bytes, fast, stallAfter = 768 * 1024) else paced(bytes, fast) }) {
                    paced(it, fast)
                }
            val io = RecordingIo(size, segments)
            protocol(script.transport()).transfer(transferOf(), io) shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
            script.block0Starts.size shouldBe 2
        }
}
