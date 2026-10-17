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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RampTest {
    private val mib = 1L shl 20
    private val size = 40 * mib
    private val content = ByteArray(size.toInt()) { (it % 251).toByte() }
    private val segments = planBlocks(size, 4).mapIndexed { i, s -> Segment(SegmentId(i + 1L), FileId(1), s.start, s.end) }
    private val origin = "https://h:443"

    private fun transferOf(id: Long = 1): Transfer {
        val file = File(FileId(1), DownloadId(id), 0, "f.bin", size)
        val probe = HttpProbe("f.bin", size, null, true, "https://h/f.bin", null)
        val mirror = Mirror(MirrorId(id), DownloadId(id), "https://h/f.bin", 0)
        return Transfer(aDownload(), mirror, probe, listOf(TransferFile(file, segments, Path("/d/f.bin.$id.anydm"), Path("/d/f.bin"))))
    }

    private class Opened {
        val bodies = mutableListOf<HttpBody>()

        fun open(): Int =
            bodies.count {
                when (it) {
                    is PacedBody -> !it.closed
                    is PipedBody -> !it.closed
                    else -> false
                }
            }
    }

    private fun TestScope.protocol(transport: FakeTransport) =
        HttpProtocol(transport, connections = { 4 }, timeSource = testScheduler.timeSource)

    private fun paced(opened: Opened) =
        FakeTransport(pacedRanges(content) { PacedBody(it, every = 100.milliseconds).also { b -> opened.bodies += b } })

    @Test
    fun connectionsRiseToTheCap() =
        runTest {
            val opened = Opened()
            val io = RecordingIo(size, segments)
            val run = async { protocol(paced(opened)).transfer(transferOf(), io) }
            advanceTimeBy(500.milliseconds)
            val counts = mutableListOf<Int>()
            repeat(6) {
                runCurrent()
                counts += opened.open()
                advanceTimeBy(1.seconds)
            }
            counts shouldBe listOf(1, 2, 3, 4, 4, 4)
            run.await() shouldBe StepResult.Ok(Unit)
            io.bytes shouldBe content
        }

    @Test
    fun noGainStopsTheRamp() =
        runTest {
            val opened = Opened()
            val pipe = SharedPipe(mib)
            val transport = FakeTransport(pacedRanges(content) { PipedBody(it, pipe).also { b -> opened.bodies += b } })
            val run = async { protocol(transport).transfer(transferOf(), RecordingIo(size, segments)) }
            advanceTimeBy(500.milliseconds)
            val counts = mutableListOf<Int>()
            repeat(30) {
                runCurrent()
                counts += opened.open()
                advanceTimeBy(1.seconds)
            }
            counts.max() shouldBe 2
            counts.drop(2).all { it == 2 } shouldBe true
            run.await() shouldBe StepResult.Ok(Unit)
        }

    @Test
    fun twoDownloadsShareTheHost() =
        runTest {
            val opened = Opened()
            val protocol = protocol(paced(opened))
            val io1 = RecordingIo(size, segments)
            val io2 = RecordingIo(size, segments)
            val first = async { protocol.transfer(transferOf(1), io1) }
            val second = async { protocol.transfer(transferOf(2), io2) }
            var max = 0
            while (first.isActive && second.isActive) {
                advanceTimeBy(500.milliseconds)
                runCurrent()
                max = maxOf(max, opened.open())
            }
            (max <= 4) shouldBe true
            io1.written.isNotEmpty() shouldBe true
            io2.written.isNotEmpty() shouldBe true
            first.await() shouldBe StepResult.Ok(Unit)
            second.await() shouldBe StepResult.Ok(Unit)
        }

    @Test
    fun aYieldingWorkerLetsTheOtherIn() =
        runTest {
            val opened = Opened()
            val protocol = protocol(paced(opened))
            val io1 = RecordingIo(size, segments)
            val io2 = RecordingIo(size, segments)
            val first = async { protocol.transfer(transferOf(1), io1) }
            advanceTimeBy(4500.milliseconds)
            runCurrent()
            opened.open() shouldBe 4
            val second = async { protocol.transfer(transferOf(2), io2) }
            advanceTimeBy(5.seconds)
            runCurrent()
            io2.written.isNotEmpty() shouldBe true
            first.await() shouldBe StepResult.Ok(Unit)
            second.await() shouldBe StepResult.Ok(Unit)
        }

    @Test
    fun everySlotComesBack() =
        runTest {
            val opened = Opened()
            val ok = protocol(paced(opened))
            ok.transfer(transferOf(), RecordingIo(size, segments)) shouldBe StepResult.Ok(Unit)
            ok.slots.held(origin) shouldBe 0

            val missing = protocol(FakeTransport { response(404) })
            (missing.transfer(transferOf(), RecordingIo(size, segments)) is StepResult.Failed) shouldBe true
            missing.slots.held(origin) shouldBe 0

            val cancelled = protocol(paced(Opened()))
            val job = launch { cancelled.transfer(transferOf(), RecordingIo(size, segments)) }
            advanceTimeBy(3.seconds)
            job.cancelAndJoin()
            cancelled.slots.held(origin) shouldBe 0
        }
}
