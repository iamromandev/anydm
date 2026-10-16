package dev.anydm.engine.core

import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FakeFileSystem.Op
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.fakeContent
import dev.anydm.engine.store.InMemoryPlanStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StepRunnerTest {
    private val partial = Path(stepRoot, "file.zip.1.anydm")
    private val target = Path(stepRoot, "file.zip")
    private val whole = fakeContent(0, 0, 64 * 1024)

    @Test
    fun aDownloadGoesThroughAllFiveSteps() =
        runTest {
            val h = StepHarness()
            val result = h.runAll()
            result shouldBe JobResult(result.download, null)
            result.download.status shouldBe COMPLETED
            h.listener.moves.map { it.status } shouldBe listOf(QUEUED, DOWNLOADING, MUXING, COMPLETED)
            h.listener.moves.fold(aPendingDownload()) { d, m -> d.movedTo(m.status) } // every move legal (FND-02)
            h.journal shouldBe
                listOf(
                    "duplicateCheck",
                    "prepareFolder $stepRoot", // preflight (size unknown, so no space check yet)
                    "probe",
                    "freeSpace $stepRoot",
                    "moved QUEUED", // probe, and the space its size needs
                    "moved DOWNLOADING",
                    "plan",
                    "freeSpace $stepRoot",
                    "prepareFolder $stepRoot",
                    "delete $partial",
                    "preallocate $partial",
                    "openWriter $partial",
                    "transfer", // transfer
                    "moved MUXING",
                    "finalize",
                    "atomicMove $partial", // post-process
                    "moved COMPLETED",
                    "finish", // finish
                )
            h.fs.read(target) shouldBe whole
            h.fs.exists(partial) shouldBe false
            h.fs.paths().none { it.name.endsWith(".anydm") } shouldBe true
            h.fs.openWriters shouldBe 0
            h.finish.calls shouldBe listOf(result.download to listOf(target))
        }

    @Test
    fun aFailureAtAnyStepLeavesTheJobWhereItFailed() =
        runTest {
            val mismatches =
                buildList {
                    for (case in failureCases) {
                        val h = StepHarness().apply(case.setUp)
                        val result = h.runAll(case.download)
                        val f = result.failure
                        val actual =
                            listOf(
                                f?.step,
                                f?.errorClass,
                                result.download.status,
                                h.listener.moves.map { it.status },
                                h.protocol.calls,
                                h.finish.calls.size,
                            )
                        val expected = listOf(case.step, case.errorClass, case.moves.lastOrNull() ?: PENDING, case.moves, case.calls, 0)
                        if (actual != expected ||
                            f?.message.isNullOrBlank()
                        ) {
                            add("${case.name}: expected $expected, got $actual (${f?.message})")
                        }
                        // No leftover state: nothing open, nothing placed, the partial as the case says (kept for a
                        // resume once it was made), and no watermark vouching for bytes that aren't in it.
                        if (h.fs.openWriters != 0) add("${case.name}: ${h.fs.openWriters} writers left open")
                        if (h.fs.exists(partial) != case.partialKept) add("${case.name}: partial kept should be ${case.partialKept}")
                        if (h.fs.exists(target)) add("${case.name}: a file was placed")
                        addAll(h.watermarksNotBackedByBytes().map { "${case.name}: $it" })
                    }
                }
            mismatches.shouldBeEmpty()
        }

    @Test
    fun cancellingKeepsThePartialAndTheNextRunResumesIt() =
        runTest {
            val h = StepHarness()
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            val job = launch { h.runner.run(ready, h.listener) }
            testScheduler.advanceTimeBy(1.seconds) // 4 KiB chunks land at 250, 500, 750 ms
            job.cancel()
            job.join()
            job.isCancelled shouldBe true
            h.fs.openWriters shouldBe 0
            h.listener.moves.map { it.status } shouldBe listOf(QUEUED, DOWNLOADING)
            h.store.written() shouldBe 3 * 4096L
            h.fs
                .read(partial)
                .shouldNotBeNull()
                .copyOf(3 * 4096) shouldBe whole.copyOf(3 * 4096)
            h.journal.clear()
            h.protocol.fetched.clear()
            h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
            h.protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L - 3 * 4096
            h.journal shouldNotContain "preallocate $partial"
            h.fs.read(target) shouldBe whole
        }

    @Test
    fun stoppingAtAnyByteAndRunningAgainGivesTheSameFile() =
        runTest {
            checkAll(40, Arb.long(1L until 64L * 1024)) { cut ->
                val h = StepHarness()
                h.protocol.failure = FakeFailure.AtTransfer(cut, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
                val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
                val failed = h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Failed>()
                h.store.written() shouldBe cut
                h.fs.exists(partial) shouldBe true // kept for resume
                h.protocol.failure = null
                h.protocol.fetched.clear()
                h.runner.run(ready.copy(download = failed.download.movedTo(QUEUED)), h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
                h.protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L - cut
                h.fs.read(target) shouldBe whole
            }
        }

    @Test
    fun preparedFieldsKeepTheUsersChoice() =
        runTest {
            val h = StepHarness()
            val ready = h.prepare(aPendingDownload(saveFolder = null, fileName = "mine.zip")).shouldBeInstanceOf<PrepareOutcome.Ready>()
            ready.download.saveFolder shouldBe stepRoot.toString()
            ready.download.fileName shouldBe "mine.zip"
            ready.download.size shouldBe 65536L
            ready.download.status shouldBe QUEUED
            h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
            h.fs.read(Path(stepRoot, "mine.zip")) shouldBe whole
        }

    @Test
    fun freeSpaceIsCheckedWithTheReserve() =
        runTest {
            val short = StepHarness(freeSpaceReserve = 100)
            short.protocol.files = listOf(FakeFile("f.bin", 1_000))
            short.fs.free = 1_099
            val failed = short.runAll(aPendingDownload(size = 1_000))
            failed.failure.shouldNotBeNull().let {
                it.step shouldBe Step.PREFLIGHT
                it.errorClass shouldBe ErrorClass.DISK_FULL
            }

            val enough = StepHarness(freeSpaceReserve = 100)
            enough.protocol.files = listOf(FakeFile("f.bin", 1_000))
            enough.fs.free = 1_100
            enough.runAll(aPendingDownload(size = 1_000)).download.status shouldBe COMPLETED
        }

    @Test
    fun unknownSizeSkipsTheSpaceCheck() =
        runTest {
            val h = StepHarness()
            h.protocol.files = listOf(FakeFile("stream.bin", 10_000, sizeKnown = false))
            h.fs.free = 0
            h.runAll().download.status shouldBe COMPLETED
            h.journal.none { it.startsWith("freeSpace") } shouldBe true
            h.fs.read(Path(stepRoot, "stream.bin")) shouldBe fakeContent(0, 0, 10_000)
            h.listener.reported.last() shouldBe (10_000L to null)
        }

    @Test
    fun aChangedPlanStartsTheFileOver() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            val failed = h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Failed>()
            val fileId =
                h.store
                    .files(ready.download.id)
                    .single()
                    .id
            val firstSegments = h.store.segments(fileId).map { it.id }

            h.protocol.segments = 3
            h.protocol.failure = null
            h.protocol.fetched.clear()
            h.journal.clear()
            h.runner.run(ready.copy(download = failed.download.movedTo(QUEUED)), h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
            h.journal shouldContain "delete $partial"
            h.journal shouldContain "preallocate $partial"
            h.protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L
            val secondSegments =
                h.store
                    .segments(
                        h.store
                            .files(ready.download.id)
                            .single()
                            .id,
                    ).map { it.id }
            secondSegments.size shouldBe 3
            secondSegments.intersect(firstSegments.toSet()).shouldBeEmpty()
            h.fs.read(target) shouldBe whole
        }

    @Test
    fun aMissingPartialStartsTheFileOver() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            val failed = h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Failed>()
            h.fs.remove(partial)

            h.protocol.failure = null
            h.protocol.fetched.clear()
            h.runner.run(ready.copy(download = failed.download.movedTo(QUEUED)), h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
            h.protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L
            h.fs.read(target) shouldBe whole
        }

    @Test
    fun speedsRunOnVirtualTime() =
        runTest {
            val clock = virtualClock()
            val start = clock.now()
            StepHarness().runAll().download.status shouldBe COMPLETED
            clock.now() - start shouldBe 4.seconds
        }

    // FakeProtocol is what calls limiter.acquire and meter.record; this proves only that the runner hands the caller's
    // hooks to the protocol unchanged. A real protocol must call them itself on the path its bytes take (#538).
    @Test
    fun theRunnerPassesTheCallersLimiterAndMeterToTheProtocol() =
        runTest {
            val limiter = RecordingLimiter()
            val meter = RecordingMeter()
            StepHarness().runAll(limiter = limiter, meter = meter).download.status shouldBe COMPLETED
            limiter.bytes shouldBe 65536L
            meter.bytes shouldBe 65536L
        }

    @Test
    fun progressIsReported() =
        runTest {
            val h = StepHarness()
            h.runAll().download.status shouldBe COMPLETED
            h.listener.reported.size shouldBe 16
            h.listener.reported
                .map { it.first }
                .zipWithNext()
                .all { (a, b) -> a <= b } shouldBe true
            h.listener.reported.last() shouldBe (65536L to 65536L)
        }

    @Test
    fun aTorrentLikeJobSeeds() =
        runTest {
            val h = StepHarness()
            val lengths = listOf(3_000L, 5_000L, 700L)
            val names = listOf("Album/1.flac", "Album/2.flac", "Album/cover.jpg")
            h.protocol.files = names.zip(lengths) { name, length -> FakeFile(name, length) }
            h.protocol.name = "Album"
            h.protocol.seeding = true
            val ready = h.prepare(aPendingDownload(kind = DownloadKind.TORRENT)).shouldBeInstanceOf<PrepareOutcome.Ready>()
            ready.download.size shouldBe 8_700L
            h.runner
                .run(ready, h.listener)
                .shouldBeInstanceOf<RunOutcome.Finished>()
                .download.status shouldBe SEEDING
            h.listener.moves.map { it.status } shouldBe listOf(QUEUED, DOWNLOADING, MUXING, SEEDING)
            h.journal shouldContain "prepareFolder ${Path(stepRoot, "Album")}"
            names.forEachIndexed { i, name ->
                h.fs.read(Path(stepRoot, *name.split('/').toTypedArray())) shouldBe fakeContent(i, 0, lengths[i].toInt())
            }
            h.fs.paths().none { it.name.endsWith(".anydm") } shouldBe true
            h.fs.openWriters shouldBe 0
        }

    @Test
    fun aMediaLikeJobWithTwoStreams() =
        runTest {
            val h = StepHarness()
            h.protocol.files = listOf(FakeFile("Title.f137.mp4", 6_000), FakeFile("Title.f140.m4a", 2_500, sizeKnown = false))
            val ready = h.prepare(aPendingDownload(kind = DownloadKind.SITE)).shouldBeInstanceOf<PrepareOutcome.Ready>()
            ready.download.size shouldBe null
            h.runner
                .run(ready, h.listener)
                .shouldBeInstanceOf<RunOutcome.Finished>()
                .download.status shouldBe COMPLETED
            h.fs.read(Path(stepRoot, "Title.f137.mp4")) shouldBe fakeContent(0, 0, 6_000)
            h.fs.read(Path(stepRoot, "Title.f140.m4a")) shouldBe fakeContent(1, 0, 2_500)
            h.fs.paths().none { it.name.endsWith(".anydm") } shouldBe true
            h.listener.reported
                .map { it.second }
                .all { it == null } shouldBe true
        }

    @Test
    fun theRunnerCopiesRetryAfterIntoTheFailure() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.RATE_LIMITED, "slow down", 30.seconds))
            h
                .runAll()
                .failure
                .shouldNotBeNull()
                .retryAfter shouldBe 30.seconds
        }

    @Test
    fun aProtocolBugPropagates() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.ThrowAtProbe(IllegalStateException("boom"))
            shouldThrow<IllegalStateException> { h.runAll() }.message shouldBe "boom"
            h.listener.moves.shouldBeEmpty()
        }

    @Test
    fun stepsNeedTheRightStatus() =
        runTest {
            val h = StepHarness()
            shouldThrow<IllegalArgumentException> { h.prepare(aPendingDownload().movedTo(QUEUED)) }
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            shouldThrow<IllegalArgumentException> { h.runner.run(ready.copy(download = ready.download.movedTo(DOWNLOADING)), h.listener) }
        }

    @Test
    fun theDuplicateCheckRunsBeforeAnyDiskWrite() =
        runTest {
            val h = StepHarness()
            h.duplicate = DownloadId(7)
            h
                .runAll()
                .failure
                .shouldNotBeNull()
                .step shouldBe Step.PREFLIGHT
            h.journal.first() shouldBe "duplicateCheck"
            h.journal.none { it.startsWith("prepareFolder") } shouldBe true
        }

    @Test
    fun aSizeTheProbeLearnsMustFitBeforeTheJobIsQueued() =
        runTest {
            val h = StepHarness()
            h.fs.free = 1_000 // the size was unknown when the job was added
            val failed = h.prepare().shouldBeInstanceOf<PrepareOutcome.Failed>()
            failed.failure.step shouldBe Step.PROBE
            failed.failure.errorClass shouldBe ErrorClass.DISK_FULL
            failed.download.status shouldBe PENDING
            h.listener.moves.shouldBeEmpty() // never QUEUED, and the queue decides where it goes
        }

    @Test
    fun theSpaceCheckAfterTheProbeCountsTheReserveAndBytesAlreadyOnDisk() =
        runTest {
            val reserve = StepHarness(freeSpaceReserve = 100)
            reserve.protocol.files = listOf(FakeFile("f.bin", 1_000))
            reserve.fs.free = 1_099
            reserve
                .prepare()
                .shouldBeInstanceOf<PrepareOutcome.Failed>()
                .failure.errorClass shouldBe ErrorClass.DISK_FULL
            reserve.fs.free = 1_100
            reserve.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()

            val resumed = StepHarness()
            resumed.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            val ready = resumed.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            val failed = resumed.runner.run(ready, resumed.listener).shouldBeInstanceOf<RunOutcome.Failed>()
            val again = failed.download.movedTo(PENDING)
            resumed.fs.free = 64 * 1024L - 10_000 - 1
            resumed.runner.prepare(again, aMirror(), resumed.listener).shouldBeInstanceOf<PrepareOutcome.Failed>()
            resumed.fs.free = 64 * 1024L - 10_000
            resumed.runner.prepare(again, aMirror(), resumed.listener).shouldBeInstanceOf<PrepareOutcome.Ready>()
        }

    @Test
    fun hugeFilesDontOverflowTheSpaceCheck() =
        runTest {
            val h = StepHarness()
            h.protocol.files = listOf(FakeFile("a.bin", 1L shl 62), FakeFile("b.bin", 1L shl 62))
            h.fs.free = 1L shl 62
            val result = h.runAll()
            result.download.status shouldBe PENDING // the probe's size doesn't fit
            result.failure.shouldNotBeNull().errorClass shouldBe ErrorClass.DISK_FULL
        }

    @Test
    fun theWritersAreSyncedOnceTheTransferIsDone() =
        runTest {
            val h = StepHarness()
            h.runAll().download.status shouldBe COMPLETED
            h.fs.syncs shouldBe 1
        }

    @Test
    fun aTransferThatThrowsLeavesNoWriterOpen() =
        runTest {
            val fs = FakeFileSystem()
            val runner = runnerWith(throwingTransfer(IllegalStateException("transfer bug")), fs)
            val ready = runner.prepare(aPendingDownload(), aMirror()).shouldBeInstanceOf<PrepareOutcome.Ready>()
            shouldThrow<IllegalStateException> { runner.run(ready) }.message shouldBe "transfer bug"
            fs.openWriters shouldBe 0
        }

    @Test
    fun aWriterThatWontCloseDoesntKeepTheOthersOpenOrHideTheRealFailure() =
        runTest {
            val fs = FakeFileSystem()
            fs.closeFault = { if (it.name.startsWith("2.")) IllegalStateException("close boom") else null }
            val bug = IllegalStateException("transfer bug")
            val runner = runnerWith(throwingTransfer(bug), fs)
            val ready = runner.prepare(aPendingDownload(), aMirror()).shouldBeInstanceOf<PrepareOutcome.Ready>()
            val thrown = shouldThrow<IllegalStateException> { runner.run(ready) }
            (thrown === bug) shouldBe true
            thrown.suppressedExceptions.map { it.message } shouldBe listOf("close boom")
            fs.openWriters shouldBe 0
        }

    @Test
    fun aCloseFailureAfterASuccessfulTransferIsNotSwallowed() =
        runTest {
            val fs = FakeFileSystem()
            fs.closeFault = { if (it.name.startsWith("2.")) IllegalStateException("close boom") else null }
            val runner = runnerWith(FakeProtocol(files = threeFiles), fs)
            val ready = runner.prepare(aPendingDownload(), aMirror()).shouldBeInstanceOf<PrepareOutcome.Ready>()
            shouldThrow<IllegalStateException> { runner.run(ready) }.message shouldBe "close boom"
            fs.openWriters shouldBe 0
        }

    @Test
    fun aPartialOfTheWrongLengthStartsTheFileOver() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            val failed = h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Failed>()
            h.fs.remove(partial)
            h.fs.put(partial, ByteArray(100)) // some other program's file of the same name, or a half-copied one

            h.protocol.failure = null
            h.protocol.fetched.clear()
            h.journal.clear()
            h.runner.run(ready.copy(download = failed.download.movedTo(QUEUED)), h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
            h.journal shouldContain "delete $partial"
            h.journal shouldContain "preallocate $partial"
            h.protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L
            h.fs.read(target) shouldBe whole
        }

    @Test
    fun aFreshPlanNeverAppendsToTheOldPartial() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            val failed = h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Failed>()
            val fileId =
                h.store
                    .files(ready.download.id)
                    .single()
                    .id
            val firstSegments = h.store.segments(fileId).map { it.id }

            h.protocol.failure = null
            h.protocol.fresh = true // the source changed since: same ranges, but nothing already written is any good
            h.protocol.fetched.clear()
            h.journal.clear()
            h.runner.run(ready.copy(download = failed.download.movedTo(QUEUED)), h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
            h.journal shouldContain "delete $partial"
            h.journal shouldContain "preallocate $partial"
            h.protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L
            h.store
                .segments(fileId)
                .map { it.id }
                .intersect(firstSegments.toSet())
                .shouldBeEmpty()
            h.fs.read(target) shouldBe whole
        }

    @Test
    fun twoDownloadsOfTheSameNameKeepSeparatePartials() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            val one = h.prepare(aPendingDownload(id = 1)).shouldBeInstanceOf<PrepareOutcome.Ready>()
            h.runner.run(one, h.listener).shouldBeInstanceOf<RunOutcome.Failed>()
            val partialOne = Path(stepRoot, "file.zip.1.anydm")
            val bytesOne = h.fs.read(partialOne).shouldNotBeNull()

            h.protocol.failure = null
            val two = h.prepare(aPendingDownload(id = 2)).shouldBeInstanceOf<PrepareOutcome.Ready>()
            h.runner.run(two, h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
            h.fs.read(partialOne) shouldBe bytesOne // untouched
            h.fs.read(target) shouldBe whole
            h.fs.exists(Path(stepRoot, "file.zip.2.anydm")) shouldBe false // renamed into place
            h.fs.openWriters shouldBe 0
        }

    @Test
    fun anExistingFileIsNotOverwritten() =
        runTest {
            val h = StepHarness()
            h.protocol.files = listOf(FakeFile("report.pdf", 1_000))
            val existing = Path(stepRoot, "report.pdf")
            val mine = byteArrayOf(1, 2, 3)
            h.fs.put(existing, mine)
            val result = h.runAll()
            result.download.status shouldBe MUXING
            val failure = result.failure.shouldNotBeNull()
            failure.step shouldBe Step.POST_PROCESS
            failure.errorClass shouldBe null
            failure.message shouldContain "report.pdf"
            h.fs.read(existing) shouldBe mine
            h.fs.exists(Path(stepRoot, "report.pdf.1.anydm")) shouldBe true // kept; the user decides
        }

    @Test
    fun thePartialsOfFilesTheNewPlanDropsAreDeleted() =
        runTest {
            val h = StepHarness()
            h.protocol.files = listOf(FakeFile("a.zip", 10), FakeFile("b.zip", 10))
            h.protocol.failure = FakeFailure.AtTransfer(5, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            val failed = h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Failed>()
            val partialA = Path(stepRoot, "a.zip.1.anydm")
            val partialB = Path(stepRoot, "b.zip.1.anydm")
            h.fs.exists(partialA) shouldBe true
            h.fs.exists(partialB) shouldBe true

            h.protocol.files = listOf(FakeFile("a.zip", 10)) // the source lists one file now
            h.protocol.failure = null
            h.runner.run(ready.copy(download = failed.download.movedTo(QUEUED)), h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
            h.fs.exists(partialB) shouldBe false
            h.fs.read(Path(stepRoot, "a.zip")) shouldBe fakeContent(0, 0, 10)
            h.fs.paths().none { it.name.endsWith(".anydm") } shouldBe true
        }

    @Test
    fun cancellingWhileTheFinishHookRunsStillFinishes() =
        runTest {
            val h = StepHarness()
            val hook = mutableListOf<String>()
            val slow =
                object : FinishHook {
                    override suspend fun finished(
                        download: Download,
                        outputs: List<Path>,
                    ) {
                        hook += "start"
                        delay(1.seconds)
                        hook += "end"
                    }
                }
            val runner = StepRunner(h.fs, h.store, mapOf(DownloadKind.DIRECT to h.protocol), stepRoot, finish = slow, freeSpaceReserve = 0)
            h.store.register(DownloadId(1))
            val ready = runner.prepare(aPendingDownload(), aMirror()).shouldBeInstanceOf<PrepareOutcome.Ready>()
            var outcome: RunOutcome? = null
            val job = launch { outcome = runner.run(ready) }
            testScheduler.advanceTimeBy(4500.milliseconds) // the transfer took 4 s; the hook is mid-delay
            hook shouldBe listOf("start")
            job.cancel()
            job.join()
            hook shouldBe listOf("start", "end")
            outcome.shouldBeInstanceOf<RunOutcome.Finished>().download.status shouldBe COMPLETED
        }

    private val threeFiles = listOf(FakeFile("1.flac", 3_000), FakeFile("2.flac", 5_000), FakeFile("3.flac", 700))

    private fun runnerWith(
        protocol: Protocol,
        fs: FakeFileSystem,
    ) = StepRunner(
        fs,
        InMemoryPlanStore().also {
            it.register(DownloadId(1))
        },
        mapOf(DownloadKind.DIRECT to protocol),
        stepRoot,
        freeSpaceReserve = 0,
    )

    private fun throwingTransfer(error: Throwable) =
        object : Protocol by FakeProtocol(files = threeFiles) {
            override suspend fun transfer(
                transfer: Transfer,
                io: TransferIo,
            ): StepResult<Unit> = throw error
        }

    @Test
    fun aCheckpointForAnotherTransfersSegmentIsABug() =
        runTest {
            val stray =
                object : Protocol by FakeProtocol() {
                    override suspend fun transfer(
                        transfer: Transfer,
                        io: TransferIo,
                    ): StepResult<Unit> {
                        io.checkpoint(mapOf(SegmentId(999) to 0L))
                        return StepResult.Ok(Unit)
                    }
                }
            val runner = runnerWith(stray, FakeFileSystem())
            val ready = runner.prepare(aPendingDownload(), aMirror()).shouldBeInstanceOf<PrepareOutcome.Ready>()
            shouldThrow<IllegalArgumentException> { runner.run(ready) }
        }

    // Batched checkpoints (#554). At 1 KiB/s a 4 KiB chunk lands every 4 s, so 64 KiB takes 64 s.

    private fun TestScope.slowRun(h: StepHarness): Job {
        h.protocol.bytesPerSecond = 1024
        val ready = runBlockingPrepare(h)
        return launch { h.runner.run(ready, h.listener) }
    }

    private fun TestScope.runBlockingPrepare(h: StepHarness): PrepareOutcome.Ready {
        var ready: PrepareOutcome.Ready? = null
        launch { ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>() }
        testScheduler.runCurrent()
        return ready!!
    }

    @Test
    fun checkpointsAreSavedEveryInterval() =
        runTest {
            val h = StepHarness()
            val job = slowRun(h)

            testScheduler.advanceTimeBy(14_900.milliseconds)
            h.store.written() shouldBe 0L
            testScheduler.advanceTimeBy(200.milliseconds)
            h.store.written() shouldBe 3 * 4096L

            job.cancel()
        }

    @Test
    fun stoppingSavesEverythingWritten() =
        runTest {
            val h = StepHarness()
            val job = slowRun(h)
            testScheduler.advanceTimeBy(9.seconds)

            job.cancel()
            job.join()

            h.store.written() shouldBe h.protocol.fetched.sumOf { it.end - it.start }
            h.store.written() shouldBe 2 * 4096L
        }

    @Test
    fun aFailedStepSavesItsBytesFirst() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))

            h.runAll().failure!!.step shouldBe Step.TRANSFER

            h.store.written() shouldBe 10_000L
        }

    @Test
    fun aFailedTickSyncEndsTheStep() =
        runTest {
            val h = StepHarness()
            h.fs.faults[FakeFileSystem.Op.SYNC] = { FileResult.IoError(it, "disk gone") }
            h.protocol.bytesPerSecond = 1024

            val result = h.runAll()

            result.failure!!.step shouldBe Step.TRANSFER
            h.store.written() shouldBe 0L
            (h.protocol.fetched.sumOf { it.end - it.start } < 64 * 1024L) shouldBe true
            h.fs.openWriters shouldBe 0
        }

    @Test
    fun aStopRightAfterATickSavesNothingNew() =
        runTest {
            val h = StepHarness()
            val job = slowRun(h)
            testScheduler.advanceTimeBy(15_500.milliseconds) // chunks at 4, 8, 12 s; tick at 15 s; next chunk at 16 s
            h.fs.syncs shouldBe 1

            job.cancel()
            job.join()

            h.fs.syncs shouldBe 1
            h.store.written() shouldBe 3 * 4096L
        }

    @Test
    fun aFailedFinalFlushStillLetsThePauseFinish() =
        runTest {
            val h = StepHarness()
            val job = slowRun(h)
            testScheduler.advanceTimeBy(5.seconds)
            h.fs.faults[FakeFileSystem.Op.SYNC] = { FileResult.IoError(it, "disk gone") }

            job.cancel()
            job.join()

            job.isCancelled shouldBe true
            h.fs.openWriters shouldBe 0
            h.store.written() shouldBe 0L
        }

    @Test
    fun aCheckpointIntervalMustBePositive() {
        shouldThrow<IllegalArgumentException> { StepHarness(checkpointEvery = Duration.ZERO) }
    }

    // Source validators (#554)

    @Test
    fun theValidatorDecidesWhetherPartialsAreKept() =
        runTest {
            data class Row(
                val stored: String?,
                val probed: String?,
                val kept: Boolean,
                val after: String?,
                val reason: String?,
            )
            val rows =
                listOf(
                    Row(null, null, kept = true, after = null, reason = null),
                    Row(null, "v", kept = true, after = "v", reason = null),
                    Row("v", "v", kept = true, after = "v", reason = null),
                    Row("v", "w", kept = false, after = "w", reason = "source changed (validator \"v\" → \"w\"): starting from zero"),
                    Row("v", null, kept = true, after = "v", reason = null),
                )
            for (row in rows) {
                withClue(row) {
                    val h = StepHarness()
                    h.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
                    h.runAll()
                    h.protocol.failure = null
                    h.protocol.validator = row.probed

                    val prepared =
                        h
                            .prepare(
                                aPendingDownload().copy(sourceValidator = row.stored),
                            ).shouldBeInstanceOf<PrepareOutcome.Ready>()

                    h.fs.exists(partial) shouldBe row.kept
                    h.store.files(DownloadId(1)).isNotEmpty() shouldBe row.kept
                    prepared.download.sourceValidator shouldBe row.after
                    h.listener.startedOver shouldBe listOfNotNull(row.reason)
                }
            }
        }

    @Test
    fun aSourceThatChangedStartsFromZero() =
        runTest {
            val h = StepHarness()
            h.protocol.failure = FakeFailure.AtTransfer(10_000, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            h.runAll()
            h.protocol.failure = null
            h.protocol.validator = "w"
            h.protocol.fetched.clear()

            h.runAll(aPendingDownload().copy(sourceValidator = "v")).failure shouldBe null

            h.protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L
            h.fs.read(target) shouldBe whole
        }

    // Outputs placed before a stop (#554)

    @Test
    fun anOutputPlacedBeforeAStopIsNotFetchedAgain() =
        runTest {
            val h = StepHarness()
            h.protocol.files = listOf(FakeFile("a.bin", 4096), FakeFile("b.bin", 4096))
            var failOnce = true
            h.fs.faults[FakeFileSystem.Op.ATOMIC_MOVE] = { source ->
                if (failOnce &&
                    source.name.startsWith("b.bin")
                ) {
                    FileResult.IoError(source, "stopped here").also { failOnce = false }
                } else {
                    null
                }
            }
            val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
            val failed = h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Failed>()
            failed.failure.step shouldBe Step.POST_PROCESS
            h.protocol.fetched.clear()

            h.runner.run(ready.copy(download = failed.download.movedTo(QUEUED)), h.listener).shouldBeInstanceOf<RunOutcome.Finished>()

            h.protocol.fetched.none { it.index == 0 } shouldBe true
            h.fs.read(Path(stepRoot, "a.bin")) shouldBe fakeContent(0, 0, 4096)
            h.fs.read(Path(stepRoot, "b.bin")) shouldBe fakeContent(1, 0, 4096)
        }
}
