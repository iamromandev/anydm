package dev.anydm.engine.core

import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileWriter
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.InMemoryPlanStore
import dev.anydm.engine.store.NewFile
import dev.anydm.engine.store.NewSegment
import dev.anydm.engine.store.PlanStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class CheckpointerTest {
    private val folder = Path("/fake")

    /** Records each save and how many syncs the file system had done by then. */
    private class RecordingStore(
        private val inner: InMemoryPlanStore,
        private val fs: FakeFileSystem,
    ) : PlanStore by inner {
        val saves = mutableListOf<Pair<Int, Map<SegmentId, Long>>>()

        override suspend fun saveWatermarks(watermarks: Map<SegmentId, Long>) {
            saves += fs.syncs to watermarks
            inner.saveWatermarks(watermarks)
        }

        fun written() = inner.written()
    }

    private class Setup(
        val fs: FakeFileSystem,
        val store: RecordingStore,
        val files: List<File>,
        val segments: List<Segment>,
        val checkpointer: Checkpointer,
    )

    private suspend fun setup(): Setup {
        val fs = FakeFileSystem()
        fs.prepareFolder(folder)
        val inner = InMemoryPlanStore().also { it.register(DownloadId(1)) }
        val store = RecordingStore(inner, fs)
        val files = store.replaceFiles(DownloadId(1), listOf(NewFile(0, "a", 100), NewFile(1, "b", 100)))
        val segments = files.map { store.replaceSegments(it.id, listOf(NewSegment(0, 100))).single() }
        val writers =
            files.associate { file ->
                val path = Path(folder, file.path)
                fs.preallocate(path, 100)
                file.id to fs.openWriter(path).shouldBeInstanceOf<FileResult.Ok<FileWriter>>().value
            }
        return Setup(fs, store, files, segments, Checkpointer(writers, store, segments.associate { it.id to it.fileId }))
    }

    @Test
    fun flushSyncsBeforeItSaves() =
        runTest {
            val s = setup()
            s.checkpointer.wrote(s.files[0].id)
            s.checkpointer.record(mapOf(s.segments[0].id to 40L))

            s.checkpointer.flush() shouldBe StepResult.Ok(Unit)

            s.store.saves shouldBe listOf(1 to mapOf(s.segments[0].id to 40L))
        }

    @Test
    fun aFailedSyncSavesNothingAndKeepsWhatIsPending() =
        runTest {
            val s = setup()
            var failOnce = true
            s.fs.faults[FakeFileSystem.Op.SYNC] =
                { path -> if (failOnce) FileResult.IoError(path, "disk gone").also { failOnce = false } else null }
            s.checkpointer.wrote(s.files[0].id)
            s.checkpointer.record(mapOf(s.segments[0].id to 40L))

            s.checkpointer.flush().shouldBeInstanceOf<StepResult.Failed>()
            s.store.saves shouldBe emptyList()

            s.checkpointer.flush() shouldBe StepResult.Ok(Unit)
            s.store.written() shouldBe 40L
        }

    @Test
    fun aFlushWithNothingNewDoesNoIo() =
        runTest {
            val s = setup()
            s.checkpointer.wrote(s.files[0].id)
            s.checkpointer.record(mapOf(s.segments[0].id to 40L))
            s.checkpointer.flush()

            s.checkpointer.flush() shouldBe StepResult.Ok(Unit)

            s.store.saves.size shouldBe 1
            s.fs.syncs shouldBe 1
        }

    @Test
    fun aLowerWatermarkNeverReplacesAHigherOne() =
        runTest {
            val s = setup()
            s.checkpointer.wrote(s.files[0].id)
            s.checkpointer.record(mapOf(s.segments[0].id to 100L))
            s.checkpointer.record(mapOf(s.segments[0].id to 40L))

            s.checkpointer.flush()

            s.store.written() shouldBe 100L
        }

    @Test
    fun onlyFilesWrittenSinceTheLastFlushAreSynced() =
        runTest {
            val s = setup()
            s.checkpointer.wrote(s.files[0].id)
            s.checkpointer.record(mapOf(s.segments[0].id to 10L))

            s.checkpointer.flush()

            s.fs.syncs shouldBe 1
        }

    @Test
    fun aCheckpointSyncsTheFileItCoversEvenWithoutAWrite() =
        runTest {
            // A protocol whose library writes the partial itself only reports checkpoints.
            val s = setup()
            s.checkpointer.record(mapOf(s.segments[1].id to 30L))

            s.checkpointer.flush()

            s.store.saves shouldBe listOf(1 to mapOf(s.segments[1].id to 30L))
        }
}
