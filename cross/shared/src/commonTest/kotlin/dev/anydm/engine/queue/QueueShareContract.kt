package dev.anydm.engine.queue

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** What a running job is told about its share of the bandwidth limit (MED-05). */
abstract class QueueShareContract : QueueTestBase() {
    @Test
    fun aJobsShareFollowsItsSpeedLimit() =
        runTest {
            val h = started()
            var seen: Long? = -1
            h.protocol.onTransfer = { seen = it.speedShare() }
            h.addJob(speedLimit = 5000)
            advanceTimeBy(1.seconds)

            seen shouldBe 5000L
        }

    @Test
    fun aJobWithNoLimitHasNoShare() =
        runTest {
            val h = started()
            var seen: Long? = -1
            h.protocol.onTransfer = { seen = it.speedShare() }
            h.addJob()
            advanceTimeBy(1.seconds)

            seen shouldBe null
        }
}
