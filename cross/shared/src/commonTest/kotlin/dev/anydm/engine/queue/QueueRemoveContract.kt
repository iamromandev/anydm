package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.protocol.FakeFile
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** What removing a job sends to the trash (FIL-09): its own files, never another file that happens to share a name. */
abstract class QueueRemoveContract : QueueTestBase() {
    @Test
    fun aJobThatCouldNotPlaceItsFileDoesNotTrashTheFileInTheWay() =
        runTest {
            val h = started()
            val id = h.addJob() // job-1.zip in stepRoot
            advanceTimeBy(1.seconds)
            val other = Path(dev.anydm.engine.core.stepRoot, "job-1.zip")
            h.fs.put(other, byteArrayOf(9)) // somebody else's file appears before this one is placed
            settle()
            h.status(id) shouldBe FAILED
            h.fs.trashAvailable = true

            h.queue.remove(id, RemoveFiles.TRASH)
            runCurrent()

            h.fs.exists(other) shouldBe true
            h.fs.trashed.none { it == other } shouldBe true
        }

    @Test
    fun trashingAMergedJobMovesTheMergedFileAndNothingElse() =
        runTest {
            val h = started()
            h.protocol.files = listOf(FakeFile("v.f1.mp4", 8 * 1024), FakeFile("a.f2.m4a", 4 * 1024))
            h.protocol.outputs = listOf("m.mp4")
            h.protocol.consumed = listOf("v.f1.mp4", "a.f2.m4a")
            h.fs.put(partialPath(Path(dev.anydm.engine.core.stepRoot, "m.mp4"), DownloadId(1)), byteArrayOf(1, 2, 3))
            h.fs.trashAvailable = true
            val id = h.addJob()
            settle()
            h.status(id) shouldBe COMPLETED

            h.queue.remove(id, RemoveFiles.TRASH) shouldBe CommandResult.Done(null)

            h.fs.trashed.map { it.name } shouldBe listOf("m.mp4")
        }

    @Test
    fun removingAJobDeletesItsWorkFolders() =
        runTest {
            val h = started()
            val work = Path(dev.anydm.engine.core.stepRoot, ".work")
            h.protocol.workFolderNames = listOf(".work")
            h.fs.put(Path(work, "000000"), byteArrayOf(1))
            h.fs.trashAvailable = true
            val id = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue.remove(id, RemoveFiles.KEEP) shouldBe CommandResult.Done(null)

            h.fs.exists(work) shouldBe false
            h.fs.trashed.none { it == work } shouldBe true
        }
}
