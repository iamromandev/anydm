package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.PauseReason
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** What the queue does when a folder runs low on space and when it recovers (FIL-08). */
abstract class QueueDiskContract : QueueTestBase() {
    private val low = Path("/dl/low")
    private val fine = Path("/dl/fine")

    @Test
    fun aLowFolderPausesItsJobsOnly() =
        runTest {
            val h = started()
            val inLow = h.addJob(folder = low)
            val inFine = h.addJob(folder = fine)
            advanceTimeBy(1.seconds)
            h.status(inLow) shouldBe DOWNLOADING

            h.queue.applyDiskState(setOf(low))
            runCurrent()

            h.status(inLow) shouldBe PAUSED
            h.reasons(inLow) shouldBe setOf(PauseReason.LOW_DISK)
            h.status(inFine) shouldBe DOWNLOADING
        }

    @Test
    fun aRecoveredFolderResumesItsJobs() =
        runTest {
            val h = started()
            val id = h.addJob(folder = low)
            advanceTimeBy(1.seconds)
            h.queue.applyDiskState(setOf(low))
            runCurrent()

            h.queue.applyDiskState(emptySet(), release = setOf(id))
            settle()

            h.status(id) shouldBe COMPLETED
        }

    @Test
    fun aUserPausedJobStaysPausedAfterTheFolderRecovers() =
        runTest {
            val h = started()
            val id = h.addJob(folder = low)
            advanceTimeBy(1.seconds)
            h.queue.pause(id)
            runCurrent()
            h.queue.applyDiskState(setOf(low))
            runCurrent()
            h.reasons(id) shouldBe setOf(PauseReason.USER, PauseReason.LOW_DISK)

            h.queue.applyDiskState(emptySet(), release = setOf(id))
            runCurrent()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.USER)
        }

    @Test
    fun applyingTheSameStateTwiceChangesNothing() =
        runTest {
            val h = started()
            val id = h.addJob(folder = low)
            advanceTimeBy(1.seconds)
            h.queue.applyDiskState(setOf(low))
            runCurrent()
            val before = h.store.downloads.get(id)

            h.queue.applyDiskState(setOf(low))
            runCurrent()

            h.store.downloads.get(id) shouldBe before
        }

    @Test
    fun aWaitingJobInALowFolderIsPausedBeforeItStarts() =
        runTest {
            val h = started(limit = 1)
            h.addJob(folder = fine)
            val waiting = h.addJob(folder = low)
            advanceTimeBy(1.seconds)

            h.queue.applyDiskState(setOf(low))
            runCurrent()

            h.reasons(waiting) shouldBe setOf(PauseReason.LOW_DISK)
        }

    @Test
    fun aJobNotNamedForReleaseStaysPaused() =
        runTest {
            val h = started()
            val id = h.addJob(folder = low)
            advanceTimeBy(1.seconds)
            h.queue.applyDiskState(setOf(low))
            runCurrent()

            h.queue.applyDiskState(emptySet(), release = emptySet())
            runCurrent()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.LOW_DISK)
        }
}
