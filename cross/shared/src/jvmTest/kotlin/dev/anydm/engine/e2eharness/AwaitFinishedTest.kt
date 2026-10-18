package dev.anydm.engine.e2eharness

import dev.anydm.engine.EngineHarness
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.e2e.awaitFinished
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Final review (#576): the throughput test's wait ends on success, on failure, and at a timeout — never hangs. */
class AwaitFinishedTest {
    @Test
    fun aCompletedJobIsReturned() =
        runTest {
            val h = EngineHarness(this, Store(openInMemoryDatabase(), StandardTestDispatcher(testScheduler)))
            h.engine.start()
            h.addJob()
            h.engine
                .awaitFinished(1.minutes)
                .download.status shouldBe DownloadStatus.COMPLETED
            h.engine.stop()
        }

    @Test
    fun aFailedJobFailsAtOnce() =
        runTest {
            val protocol = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.GONE, "no such file")))
            val h = EngineHarness(this, Store(openInMemoryDatabase(), StandardTestDispatcher(testScheduler)), protocol)
            h.engine.start()
            h.addJob()
            shouldThrow<AssertionError> { h.engine.awaitFinished(1.minutes) }.message shouldContain "no such file"
            h.engine.stop()
        }

    @Test
    fun aJobThatNeverEndsTimesOut() =
        runTest {
            val protocol = FakeProtocol(bytesPerSecond = 1)
            val h = EngineHarness(this, Store(openInMemoryDatabase(), StandardTestDispatcher(testScheduler)), protocol)
            h.engine.start()
            h.addJob()
            shouldThrow<TimeoutCancellationException> { h.engine.awaitFinished(10.seconds) }
            h.engine.stop()
        }
}
