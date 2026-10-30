package dev.anydm.engine.ftp

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.aDownload
import dev.anydm.engine.core.aPendingDownload
import dev.anydm.engine.http.RecordingIo
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferPlan
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class FtpProtocolTransferTest {
    private val server = FakeRemoteServer()
    private val keychain = FakeKeychain()
    private val logins = KeychainCredentials(keychain)
    private val kib = 1024
    private val content = ByteArray(1024 * kib) { (it % 251).toByte() }
    private val url = "ftp://host/f.bin"

    // 64 KiB blocks keep the tests small: a 1 MiB file is split four ways.
    private fun protocol(connections: Int = 4) =
        FtpProtocol(
            server.factory,
            logins,
            logins,
            connections = { connections },
            minBlock =
                64L * kib,
        )

    private class Run(
        val transfer: Transfer,
        val io: RecordingIo,
        val segments: List<Segment>,
    )

    private suspend fun prepare(
        protocol: FtpProtocol,
        watermarks: Map<Int, Long> = emptyMap(),
        bytes: ByteArray = content,
    ): Run {
        server.files["/f.bin"] = bytes
        val mirror = Mirror(MirrorId(1), DownloadId(1), url, 0)
        val probe =
            protocol
                .probe(
                    ProbeRequest(aPendingDownload(DownloadKind.FTP), mirror),
                ).shouldBeInstanceOf<StepResult.Ok<*>>()
                .value as FtpProbe
        val plan =
            (
                protocol
                    .plan(
                        probe,
                        PlanContext(aPendingDownload(DownloadKind.FTP, fileName = "f.bin")),
                    ).shouldBeInstanceOf<StepResult.Ok<*>>()
                    .value as TransferPlan
            ).files
                .single()
        val segments =
            plan.segments.mapIndexed { i, s -> Segment(SegmentId(i + 1L), FileId(1), s.start, s.end, watermarks[i] ?: s.start) }
        val file = File(FileId(1), DownloadId(1), 0, "f.bin", plan.size)
        val transfer =
            Transfer(aDownload(), mirror, probe, listOf(TransferFile(file, segments, Path("/d/f.bin.1.anydm"), Path("/d/f.bin"))))
        return Run(transfer, RecordingIo(bytes.size.toLong(), segments), segments)
    }

    private suspend fun FtpProtocol.runOf(run: Run) = transfer(run.transfer, run.io)

    @Test
    fun blocksFetchTheirRangesInParallel() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)

            protocol.runOf(run) shouldBe StepResult.Ok(Unit)

            run.segments.size shouldBe 4
            server.peakReads shouldBe 4
        }

    @Test
    fun theFileIsByteIdenticalAndEveryBlockIsCheckpointedToItsEnd() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)

            protocol.runOf(run) shouldBe StepResult.Ok(Unit)

            run.io.bytes.contentEquals(content) shouldBe true
            for (s in run.segments) run.io.lastWatermark(s.id) shouldBe s.end
            run.io.progress.last() shouldBe content.size.toLong()
        }

    @Test
    fun everyClientIsClosed() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)

            protocol.runOf(run)

            server.closed shouldBe server.opened
        }

    @Test
    fun eachChunkGoesThroughTheLimiterAndMeter() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)

            protocol.runOf(run)

            run.io.limiter.bytes shouldBe content.size.toLong()
            run.io.meter.bytes shouldBe content.size.toLong()
        }

    @Test
    fun aFullDiskEndsTheTransferAndCheckpointsOnlyWhatWasWritten() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)
            run.io.failWriteAt = 300L * kib

            val result = protocol.runOf(run)

            result.shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.DISK_FULL
            for (s in run.segments) (run.io.lastWatermark(s.id) ?: s.start) shouldBeLessThanOrEqualTo maxOf(s.start, 300L * kib)
        }

    @Test
    fun aDroppedConnectionRetriesFromTheWatermark() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)
            server.faults += ReadFault(afterBytes = 96 * kib, error = RemoteException(RemoteFailure.TRANSIENT, "reset"))

            protocol.runOf(run) shouldBe StepResult.Ok(Unit)

            run.io.bytes.contentEquals(content) shouldBe true
            // The block that broke after 96 KiB came back at that offset, not at its start.
            val starts = run.segments.map { it.start }
            server.readLog.any { (offset, _) -> starts.any { offset == it + 96L * kib } } shouldBe true
        }

    @Test
    fun aShortReadIsTransientAndResumes() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)
            server.faults += ReadFault(afterBytes = 32 * kib)

            protocol.runOf(run) shouldBe StepResult.Ok(Unit)

            run.io.bytes.contentEquals(content) shouldBe true
        }

    @Test
    fun aServerThatKeepsDroppingFailsAsTransientAfterAFewTries() =
        runTest {
            val protocol = protocol(connections = 1)
            val run = prepare(protocol)
            repeat(20) { server.faults += ReadFault(afterBytes = 0, error = RemoteException(RemoteFailure.TRANSIENT, "reset")) }

            val result = protocol.runOf(run)

            result.shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.TRANSIENT
            server.opened shouldBeLessThanOrEqualTo 6
        }

    @Test
    fun tooManyConnectionsLowersTheBlockCountAndStillFinishes() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)
            server.maxConnections = 2

            protocol.runOf(run) shouldBe StepResult.Ok(Unit)

            run.io.bytes.contentEquals(content) shouldBe true
            server.peakActive shouldBeLessThanOrEqualTo 2
        }

    @Test
    fun aServerThatAllowsOneConnectionStillFinishes() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)
            server.maxConnections = 1

            protocol.runOf(run) shouldBe StepResult.Ok(Unit)

            run.io.bytes.contentEquals(content) shouldBe true
        }

    @Test
    fun restUnsupportedFallsBackToOneBlockFromZero() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol, watermarks = mapOf(1 to 288L * kib))
            server.restUnsupported = true

            protocol.runOf(run) shouldBe StepResult.Ok(Unit)

            run.io.bytes.contentEquals(content) shouldBe true
            for (s in run.segments) run.io.lastWatermark(s.id) shouldBe s.end
        }

    @Test
    fun authAndGoneFailTheTransferWithTheirClasses() =
        runTest {
            val protocol = protocol()
            val run = prepare(protocol)
            server.connectFailure = RemoteException(RemoteFailure.AUTH, "refused")
            protocol.runOf(run).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.AUTH

            server.connectFailure = null
            server.files.clear()
            protocol.runOf(prepareFrom(run)).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.GONE
        }

    private fun prepareFrom(run: Run) = Run(run.transfer, RecordingIo(content.size.toLong(), run.segments), run.segments)

    @Test
    fun cancellingClosesEveryClientAndKeepsWatermarks() =
        runTest {
            val protocol = protocol()
            val first = prepare(protocol)
            server.chunkDelay = 10.milliseconds

            val job = backgroundScope.async { protocol.runOf(first) }
            advanceTimeBy(80.milliseconds)
            runCurrent()
            job.cancelAndJoin()

            server.closed shouldBe server.opened
            val marks = first.segments.associate { it.id to (first.io.lastWatermark(it.id) ?: it.start) }
            val done = first.segments.sumOf { marks.getValue(it.id) - it.start }
            (done in 1 until content.size.toLong()) shouldBe true

            // A second run from those watermarks finishes the file.
            server.chunkDelay = 0.milliseconds
            val second = prepare(protocol, watermarks = first.segments.mapIndexed { i, s -> i to marks.getValue(s.id) }.toMap())
            first.io.bytes.copyInto(second.io.bytes)
            protocol.runOf(second) shouldBe StepResult.Ok(Unit)
            second.io.bytes.contentEquals(content) shouldBe true
            for (s in second.segments) second.io.lastWatermark(s.id) shouldBe s.end
        }

    @Test
    fun anUnknownSizeFileIsReadToTheEnd() =
        runTest {
            val protocol = protocol()
            server.reportsSize = false
            val run = prepare(protocol)

            protocol.runOf(run) shouldBe StepResult.Ok(Unit)

            run.segments shouldBe emptyList()
            run.io.bytes.contentEquals(content) shouldBe true
            run.io.progress.last() shouldBe content.size.toLong()
            server.readLog shouldContain (0L to null)
        }
}
