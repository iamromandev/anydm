package dev.anydm.engine.core

import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class DownloadPauseTest {
    private fun TransitionResult.moved(): Download = shouldBeInstanceOf<TransitionResult.Moved>().download

    private fun pausedFor(vararg reasons: PauseReason): Download =
        reasons.fold(aDownload(DOWNLOADING)) { download, reason -> download.pause(reason).moved() }

    @Test
    fun aJobThatIsNotPausedCantHoldPauseReasons() {
        shouldThrow<IllegalArgumentException> { aDownload(QUEUED).copy(pauseReasons = setOf(PauseReason.USER)) }
    }

    @Test
    fun pauseMovesIntoPausedWithTheReason() {
        val paused = aDownload(DOWNLOADING).pause(PauseReason.USER).moved()

        paused.status shouldBe PAUSED
        paused.pauseReasons shouldBe setOf(PauseReason.USER)
    }

    @Test
    fun pauseOnAPausedJobAddsTheReason() {
        val paused = pausedFor(PauseReason.USER).pause(PauseReason.GLOBAL).moved()

        paused.status shouldBe PAUSED
        paused.pauseReasons shouldBe setOf(PauseReason.USER, PauseReason.GLOBAL)
    }

    @Test
    fun pauseFromAStatusThatCantPauseIsRejected() {
        for (status in listOf(FAILED, COMPLETED, CANCELLED, SEEDING)) {
            withClue(status) {
                aDownload(status).pause(PauseReason.USER) shouldBe TransitionResult.Rejected(status, PAUSED)
            }
        }
    }

    @Test
    fun releaseOfTheLastReasonQueuesTheJob() {
        val released = pausedFor(PauseReason.GLOBAL).release(PauseReason.GLOBAL).moved()

        released.status shouldBe QUEUED
        released.pauseReasons shouldBe emptySet()
    }

    @Test
    fun releaseOfOneOfSeveralReasonsKeepsItPaused() {
        val released = pausedFor(PauseReason.USER, PauseReason.GLOBAL).release(PauseReason.GLOBAL).moved()

        released.status shouldBe PAUSED
        released.pauseReasons shouldBe setOf(PauseReason.USER)
    }

    @Test
    fun releaseOfAReasonNotHeldChangesNothing() {
        val paused = pausedFor(PauseReason.USER)
        paused.release(PauseReason.GLOBAL) shouldBe TransitionResult.Moved(paused)

        val queued = aDownload(QUEUED)
        queued.release(PauseReason.GLOBAL) shouldBe TransitionResult.Moved(queued)
    }

    @Test
    fun leavingPausedClearsEveryReason() {
        val paused = pausedFor(PauseReason.USER, PauseReason.GLOBAL)

        paused.transitionTo(QUEUED).moved().pauseReasons shouldBe emptySet()
        paused.transitionTo(CANCELLED).moved().pauseReasons shouldBe emptySet()
    }
}
