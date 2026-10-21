package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.fakeContent
import dev.anydm.engine.store.DATABASE_FILE
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The Engine on a real database file, so a stop and a restart can be checked from the file itself. */
class EngineFileTest {
    private val root = newTempFolder("anydm-engine-test-")
    private val file = Path(root, DATABASE_FILE)
    private val stores = mutableListOf<Store>()

    @AfterTest
    fun cleanUp() {
        stores.forEach { runCatching { it.close() } }
        deleteRecursively(root)
    }

    private fun TestScope.open(): Store = Store(openDatabaseFile(file), StandardTestDispatcher(testScheduler)).also { stores += it }

    @Test
    fun trafficSurvivesARestart() =
        runTest {
            val first = EngineHarness(this, open())
            first.engine.start()
            first.addJob()
            advanceTimeBy(5.seconds)
            first.engine.stop()
            val day =
                first.clock
                    .now()
                    .toLocalDateTime(TimeZone.UTC)
                    .date
                    .toString()

            val second = EngineHarness(this, open(), clock = first.clock)
            second.store.traffic
                .trafficByDay(day, day)
                .sumOf { it.bytes } shouldBe 64 * 1024L
            second.engine.start()
            second.addJob()
            advanceTimeBy(5.seconds)
            second.engine.stop()

            open().traffic.trafficByDay(day, day).sumOf { it.bytes } shouldBe 2 * 64 * 1024L
        }

    @Test
    fun stopMidTransferSavesAndCloses() =
        runTest {
            val h = EngineHarness(this, open())
            h.engine.start()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.engine.stop()

            h.engine.state.value shouldBe EngineState.Stopped
            shouldThrow<IllegalStateException> { h.engine.pause(a) }
            val reopened = open()
            val written = reopened.downloads.files(a).sumOf { f -> reopened.downloads.segments(f.id).sumOf { it.written } }
            written shouldBe h.protocol.fetched.sumOf { it.end - it.start }
            (written > 0) shouldBe true
        }

    @Test
    fun queuedPausedRetriedAndResumedAcrossARestart() =
        runTest {
            val fake = FakeProtocol()
            var flaky: DownloadId? = null
            var failedOnce = false
            // The first transfer of the flaky job fails once with a transient error; every other one works.
            val protocol =
                object : Protocol by fake {
                    override suspend fun transfer(
                        transfer: Transfer,
                        io: TransferIo,
                    ): StepResult<Unit> {
                        if (transfer.download.id == flaky && !failedOnce) {
                            failedOnce = true
                            return StepResult.Failed(ErrorClass.TRANSIENT, "reset")
                        }
                        return fake.transfer(transfer, io)
                    }
                }
            val protocols = DownloadKind.entries.associateWith<DownloadKind, Protocol> { protocol }
            val before = EngineHarness(this, open(), fake, protocols)
            before.engine.start()
            before.engine.setLimit(2)
            val a = before.addJob().also { flaky = it }
            val b = before.addJob()
            val c = before.addJob()
            advanceTimeBy(1.seconds)
            before.engine.pause(b)
            advanceTimeBy(1.seconds)

            before.engine.stop()

            val store = open()
            val after = EngineHarness(this, store, fake, protocols, fs = before.fs, clock = before.clock)
            after.engine.start()
            after.engine.resume(b)
            advanceTimeBy(10.minutes)
            runCurrent()

            listOf(a, b, c).map { after.status(it) } shouldBe List(3) { DownloadStatus.COMPLETED }
            for (n in 1..3) before.fs.read(Path(stepRoot, "job-$n.zip"))?.toList() shouldBe fakeContent(0, 0, 64 * 1024).toList()
            store.settings.load().concurrencyLimit shouldBe 2
            (store.downloads.attempts(a).size >= 2) shouldBe true
            after.engine.stop()
        }
}
