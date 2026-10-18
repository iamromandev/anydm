package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadStatus.FAILED
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
}
