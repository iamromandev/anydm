package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What the queue does with a job whose tool is missing and with the install that frees it (BIN-01). */
abstract class QueueToolContract : QueueTestBase() {
    private fun missing() = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.TOOL_MISSING, "yt-dlp")))

    @Test
    fun aMissingToolPausesTheJobForTools() =
        runTest {
            val h = started { QueueHarness(it, newStore(), missing()) }
            val id = h.addJob()
            settle()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.TOOLS)
        }

    @Test
    fun applyToolStateReleasesTheJob() =
        runTest {
            val protocol = missing()
            val h = started { QueueHarness(it, newStore(), protocol) }
            val id = h.addJob()
            settle()
            protocol.failure = null

            h.queue.applyToolState()
            settle()

            h.status(id) shouldBe COMPLETED
        }

    @Test
    fun aJobThatStillLacksItsToolPausesAgain() =
        runTest {
            val h = started { QueueHarness(it, newStore(), missing()) }
            val id = h.addJob()
            settle()

            h.queue.applyToolState()
            settle()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.TOOLS)
        }

    @Test
    fun aUserPausedJobStaysPausedAfterTheRelease() =
        runTest {
            val protocol = missing()
            val h = started { QueueHarness(it, newStore(), protocol) }
            val id = h.addJob()
            settle()
            h.queue.pause(id)
            runCurrent()
            h.reasons(id) shouldBe setOf(PauseReason.TOOLS, PauseReason.USER)
            protocol.failure = null

            h.queue.applyToolState()
            runCurrent()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.USER)
        }

    @Test
    fun applyingTwiceChangesNothing() =
        runTest {
            val protocol = missing()
            val h = started { QueueHarness(it, newStore(), protocol) }
            val id = h.addJob()
            settle()
            protocol.failure = null
            h.queue.applyToolState()
            settle()
            val before = h.store.downloads.get(id)

            h.queue.applyToolState()
            settle()

            h.store.downloads.get(id) shouldBe before
        }
}
