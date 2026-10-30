package dev.anydm.engine.core

import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.fakeContent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

/** The runner checks a finished single-file download against its checksum before placing it (HTTP-14). */
class ChecksumCheckTest {
    private val partial = Path(stepRoot, "file.zip.1.anydm")
    private val target = Path(stepRoot, "file.zip")
    private val whole = fakeContent(0, 0, 64 * 1024)

    private fun sha256(bytes: ByteArray) =
        Checksum(
            ChecksumAlgorithm.SHA256,
            hasherFor(ChecksumAlgorithm.SHA256)
                .also {
                    it.update(bytes)
                }.hex(),
        )

    private fun md5(bytes: ByteArray) = Checksum(ChecksumAlgorithm.MD5, hasherFor(ChecksumAlgorithm.MD5).also { it.update(bytes) }.hex())

    private val wrongSha256 = Checksum(ChecksumAlgorithm.SHA256, "0".repeat(64))

    private suspend fun StepHarness.runWith(checksum: Checksum?): RunOutcome {
        val ready = prepare(aPendingDownload().copy(checksum = checksum)).shouldBeInstanceOf<PrepareOutcome.Ready>()
        return runner.run(ready, listener)
    }

    @Test
    fun aMatchingChecksumCompletes() =
        runTest {
            val h = StepHarness()
            h.runWith(sha256(whole)).shouldBeInstanceOf<RunOutcome.Finished>()
            h.fs.read(target) shouldBe whole
        }

    @Test
    fun aMismatchFailsAsIntegrity() =
        runTest {
            val h = StepHarness()
            val failed = h.runWith(wrongSha256).shouldBeInstanceOf<RunOutcome.Failed>()
            failed.failure shouldBe
                StepFailure(
                    Step.POST_PROCESS,
                    ErrorClass.INTEGRITY,
                    "sha256 mismatch: expected ${wrongSha256.hex}, got ${sha256(whole).hex}",
                )
            failed.download.status shouldBe MUXING
            h.fs.exists(partial) shouldBe true
            h.fs.exists(target) shouldBe false
        }

    @Test
    fun theProbesChecksumIsUsedWithoutTheUsers() =
        runTest {
            val h = StepHarness()
            val wrong = Checksum(ChecksumAlgorithm.MD5, "0".repeat(32))
            h.protocol.checksum = wrong
            h
                .runWith(null)
                .shouldBeInstanceOf<RunOutcome.Failed>()
                .failure.message shouldBe
                "md5 mismatch: expected ${wrong.hex}, got ${md5(whole).hex}"
        }

    @Test
    fun theUsersChecksumWins() =
        runTest {
            val h = StepHarness()
            h.protocol.checksum = Checksum(ChecksumAlgorithm.MD5, "0".repeat(32))
            h.runWith(sha256(whole)).shouldBeInstanceOf<RunOutcome.Finished>()
        }

    @Test
    fun severalOutputsAreNotChecked() =
        runTest {
            val h = StepHarness()
            h.protocol.files = listOf(FakeFile("a.bin", 1024), FakeFile("b.bin", 1024))
            h.runWith(wrongSha256).shouldBeInstanceOf<RunOutcome.Finished>()
        }

    @Test
    fun aReadFailureIsReturned() =
        runTest {
            val h = StepHarness()
            h.fs.faults[FakeFileSystem.Op.READ] = { FileResult.IoError(it, "boom") }
            val failure = h.runWith(wrongSha256).shouldBeInstanceOf<RunOutcome.Failed>().failure
            failure.errorClass shouldBe null
            failure.message shouldBe "couldn't use $partial: boom"
        }

    @Test
    fun anEmptyFileIsChecked() =
        runTest {
            val h = StepHarness()
            h.protocol.files = listOf(FakeFile("file.zip", 0))
            h.runWith(sha256(ByteArray(0))).shouldBeInstanceOf<RunOutcome.Finished>()
        }

    @Test
    fun aFileOfManyChunksIsHashedWhole() =
        runTest {
            val h = StepHarness()
            val size = 5 * (1 shl 20) / 2
            h.protocol.files = listOf(FakeFile("file.zip", size.toLong()))
            h.protocol.chunk = 64 * 1024
            h.protocol.bytesPerSecond = 64L shl 20
            h.runWith(sha256(fakeContent(0, 0, size))).shouldBeInstanceOf<RunOutcome.Finished>()
        }

    @Test
    fun cancellingTheCheckKeepsThePartial() =
        runTest {
            val h = StepHarness()
            val ready = h.prepare(aPendingDownload().copy(checksum = sha256(whole))).shouldBeInstanceOf<PrepareOutcome.Ready>()
            val gate = CompletableDeferred<Unit>()
            h.fs.readGate = gate
            val job = launch { h.runner.run(ready, h.listener) }
            testScheduler.advanceUntilIdle()
            job.isActive shouldBe true
            job.cancelAndJoin()
            h.fs.exists(partial) shouldBe true
            h.fs.readGate = null
            h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Finished>()
        }

    @Test
    fun startOverDropsThePartials() =
        runTest {
            val h = StepHarness()
            val failed = h.runWith(wrongSha256).shouldBeInstanceOf<RunOutcome.Failed>()
            h.runner.startOver(failed.download) shouldBe StepResult.Ok(Unit)
            h.fs.exists(partial) shouldBe false
            h.store.files(failed.download.id) shouldBe emptyList()
        }
}
