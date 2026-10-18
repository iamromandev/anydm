package dev.anydm.engine.core

import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

/** A protocol that merges its files into an output of its own: the runner records the output and drops the streams. */
class StepRunnerMergedOutputTest {
    private val id = DownloadId(1)

    private fun merging(): StepHarness =
        StepHarness().also { h ->
            h.protocol.files = listOf(FakeFile("v.f1.mp4", 8 * 1024), FakeFile("a.f2.m4a", 4 * 1024))
            h.protocol.outputs = listOf("m.mp4")
            h.protocol.consumed = listOf("v.f1.mp4", "a.f2.m4a")
            h.fs.put(partialPath(Path(stepRoot, "m.mp4"), id), byteArrayOf(1, 2, 3))
        }

    @Test
    fun aMergedOutputGetsAFileRowAndPlacedPath() =
        runTest {
            val h = merging()

            h.runAll().failure shouldBe null

            val row = h.store.files(id).single { it.path == "m.mp4" }
            row.placedPath shouldBe "m.mp4"
            row.size shouldBe 3L
            h.fs.exists(Path(stepRoot, "m.mp4")) shouldBe true
        }

    @Test
    fun consumedStreamsAreRemovedAfterCompletion() =
        runTest {
            val h = merging()

            h.runAll().failure shouldBe null

            h.store.files(id).map { it.path } shouldBe listOf("m.mp4")
            h.fs.exists(Path(stepRoot, "v.f1.mp4")) shouldBe false
            h.fs.exists(partialPath(Path(stepRoot, "v.f1.mp4"), id)) shouldBe false
            h.fs.exists(partialPath(Path(stepRoot, "a.f2.m4a"), id)) shouldBe false
        }

    @Test
    fun aFileOfTheUserAtAConsumedNameIsLeftAlone() =
        runTest {
            val h = merging()
            h.fs.put(Path(stepRoot, "v.f1.mp4"), byteArrayOf(7))

            h.runAll().failure shouldBe null

            h.fs.exists(Path(stepRoot, "v.f1.mp4")) shouldBe true
        }

    @Test
    fun aFailedFinalizeKeepsTheStreamRowsAndPartials() =
        runTest {
            val h = merging()
            h.protocol.failure = FakeFailure.AtFinalize(StepResult.Failed(null, "merge failed"))

            h.runAll().failure!!.message shouldBe "merge failed"

            h.store.files(id).map { it.path } shouldBe listOf("v.f1.mp4", "a.f2.m4a")
            h.fs.exists(partialPath(Path(stepRoot, "v.f1.mp4"), id)) shouldBe true
            h.fs.exists(partialPath(Path(stepRoot, "a.f2.m4a"), id)) shouldBe true
        }

    @Test
    fun anOutputWithARowIsNotAddedTwice() =
        runTest {
            val h = StepHarness()

            h.runAll().failure shouldBe null

            h.store.files(id).map { it.path } shouldBe listOf("file.zip")
            h.store
                .files(id)
                .single()
                .placedPath shouldBe "file.zip"
        }
}
