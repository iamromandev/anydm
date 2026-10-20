package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What the queue does for seeds (TOR-05) and for the limits protocols that pace their own sockets are told (TOR-08). */
abstract class QueueSeedingContract : QueueTestBase() {
    @Test
    fun globalLimitsReachProtocolsAtStartAndOnChange() =
        runTest {
            val store = newStore()
            store.settings.update { it.copy(globalSpeedLimit = 1000, globalUploadLimit = 200) }
            val h = started(harness = { QueueHarness(it, store) })

            h.protocol.globalLimits shouldBe listOf(1000L to 200L)

            h.queue.setGlobalSpeedLimit(5000)
            h.protocol.globalLimits.last() shouldBe (5000L to 200L)

            h.queue.setGlobalUploadLimit(300)
            h.protocol.globalLimits.last() shouldBe (5000L to 300L)
        }

    @Test
    fun setGlobalUploadLimitIsStoredAndApplied() =
        runTest {
            val h = started()

            h.queue.setGlobalUploadLimit(4096).shouldBeInstanceOf<CommandResult.Done>()
            h.store.settings
                .load()
                .globalUploadLimit shouldBe 4096L

            h.queue.setGlobalUploadLimit(0).shouldBeInstanceOf<CommandResult.Invalid>()
            h.store.settings
                .load()
                .globalUploadLimit shouldBe 4096L

            h.queue.setGlobalUploadLimit(null).shouldBeInstanceOf<CommandResult.Done>()
            h.store.settings
                .load()
                .globalUploadLimit shouldBe null
            h.protocol.globalLimits.last() shouldBe (null to null)
        }

    @Test
    fun finishSeedingCompletesASeedOnce() =
        runTest {
            val h = started()
            h.protocol.seeding = true
            val id = h.addJob()
            settle()
            h.status(id) shouldBe SEEDING

            h.queue.finishSeeding(id, null).shouldBeInstanceOf<CommandResult.Done>()

            h.status(id) shouldBe COMPLETED
            h.queue.finishSeeding(id, null).shouldBeInstanceOf<CommandResult.Rejected>()
        }

    @Test
    fun finishSeedingWithAFailureFailsTheJobWithItsMessage() =
        runTest {
            val h = started()
            h.protocol.seeding = true
            val id = h.addJob()
            settle()

            h.queue.finishSeeding(id, "the files are gone").shouldBeInstanceOf<CommandResult.Done>()
            runCurrent()

            h.status(id) shouldBe FAILED
            h.store.downloads
                .attempts(id)
                .last()
                .message shouldBe "the files are gone"
        }

    @Test
    fun finishSeedingOnAnotherStatusIsRejected() =
        runTest {
            val h = started()
            val job = h.addJob()
            runCurrent()
            val before = h.status(job)

            h.queue.finishSeeding(job, null).shouldBeInstanceOf<CommandResult.Rejected>()
            h.queue.finishSeeding(job, "x").shouldBeInstanceOf<CommandResult.Rejected>()
            h.queue.finishSeeding(DownloadId(9999), null) shouldBe CommandResult.NotFound

            h.status(job) shouldBe before
        }
}
