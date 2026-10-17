package dev.anydm.engine.core

import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.StoredFile
import dev.anydm.engine.protocol.fakeContent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** StepRunner.storedBytes: what a relink compares a new link with (HTTP-13). */
class PartialBytesTest {
    private val partial = Path(stepRoot, "file.zip.1.anydm")

    // FakeProtocol fetches its two 32 KiB segments in order, 4 KiB every 250 ms: 1 s leaves 12 KiB in the first.
    private suspend fun TestScope.stoppedAfter(
        h: StepHarness,
        time: Duration,
    ): Download {
        val ready = h.prepare().shouldBeInstanceOf<PrepareOutcome.Ready>()
        val job = launch { h.runner.run(ready, h.listener) }
        testScheduler.advanceTimeBy(time)
        job.cancelAndJoin()
        return ready.download
    }

    @Test
    fun writtenRangesComeFromTheWatermarks() =
        runTest {
            val h = StepHarness()
            val download = stoppedAfter(h, 1.seconds)
            h.runner.storedBytes(download).files shouldBe listOf(StoredFile(0, 65536, listOf(0L until 12288L)))
        }

    @Test
    fun readGivesThePartialsBytes() =
        runTest {
            val h = StepHarness()
            val download = stoppedAfter(h, 1.seconds)
            h.runner
                .storedBytes(download)
                .read(0, 100, 50)
                .shouldBeInstanceOf<StepResult.Ok<ByteArray>>()
                .value shouldBe
                fakeContent(0, 100, 50)
        }

    @Test
    fun aMissingPartialIsNothingWritten() =
        runTest {
            val h = StepHarness()
            val download = stoppedAfter(h, 1.seconds)
            h.fs.remove(partial)
            h.runner
                .storedBytes(download)
                .files
                .single()
                .written shouldBe emptyList()
        }

    @Test
    fun noSaveFolderIsNothingWritten() =
        runTest {
            StepHarness().runner.storedBytes(aPendingDownload(saveFolder = null)).files shouldBe emptyList()
        }

    @Test
    fun aTruncatedPartialFailsTheRead() =
        runTest {
            val h = StepHarness()
            val download = stoppedAfter(h, 1.seconds)
            h.fs.put(partial, fakeContent(0, 0, 1000))
            h.runner.storedBytes(download).read(0, 0, 12288) shouldBe
                StepResult.Failed(null, "partial is shorter than its watermarks")
        }

    @Test
    fun aReadFailureIsAFailedStep() =
        runTest {
            val h = StepHarness()
            val download = stoppedAfter(h, 1.seconds)
            h.fs.faults[FakeFileSystem.Op.READ] = { FileResult.IoError(it, "boom") }
            h.runner
                .storedBytes(download)
                .read(0, 0, 10)
                .shouldBeInstanceOf<StepResult.Failed>()
                .message shouldStartWith "can't read the partial"
        }
}
