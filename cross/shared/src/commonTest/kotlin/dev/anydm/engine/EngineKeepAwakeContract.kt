package dev.anydm.engine

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.platform.FakeKeepAwake
import dev.anydm.engine.platform.FakeOnlineProbe
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The engine keeps the machine awake while it downloads (#543, NET-11). A 64 KiB file at 16 KiB/s (4 s a job) unless a
 * test says otherwise, on a new database from [newDriver] for each test.
 */
abstract class EngineKeepAwakeContract : StoreTestBase() {
    private val harnesses = mutableListOf<EngineHarness>()

    // A failed assertion must not leave an engine's endless timers for runTest to drain.
    private fun engineTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                harnesses.forEach { it.engine.stop() }
                harnesses.clear()
            }
        }

    private fun TestScope.harness(
        keepAwake: FakeKeepAwake,
        protocol: FakeProtocol = FakeProtocol(),
        protocols: Map<DownloadKind, Protocol> = DownloadKind.entries.associateWith { protocol },
        probe: FakeOnlineProbe = FakeOnlineProbe(),
    ): EngineHarness =
        EngineHarness(
            this,
            newStore(),
            protocol,
            protocols,
            clock = virtualClock(),
            keepAwake = keepAwake,
            onlineProbe = probe,
        ).also { harnesses += it }

    private suspend fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    // Moves time in small steps until the job has [status]; false if it never does within [limit].
    private suspend fun TestScope.until(
        h: EngineHarness,
        id: dev.anydm.engine.core.DownloadId,
        status: DownloadStatus,
        limit: Duration = 2.minutes,
    ): Boolean {
        var spent = Duration.ZERO
        while (h.status(id) != status) {
            if (spent >= limit) return false
            advance(100.milliseconds)
            spent += 100.milliseconds
        }
        return true
    }

    @Test
    fun aDownloadingJobAcquires() =
        engineTest {
            val awake = FakeKeepAwake()
            val h = harness(awake)
            h.engine.start()
            awake.acquires shouldBe 0

            h.addJob()
            advance(1.seconds)

            awake.held shouldBe true
        }

    @Test
    fun queuedOnlyDoesNotAcquire() =
        engineTest {
            val awake = FakeKeepAwake()
            val h = harness(awake)
            h.engine.start()
            h.engine
                .add(
                    NewDownload(
                        DownloadKind.DIRECT,
                        mirrors = listOf("https://example.com/later.zip"),
                        saveFolder = stepRoot.toString(),
                        fileName = "later.zip",
                        startAt = h.clock.now() + 1.hours,
                    ),
                ).shouldBeInstanceOf<CommandResult.Done>()

            advance(30.seconds)

            awake.acquires shouldBe 0
            awake.releases shouldBe 0
        }

    @Test
    fun seedingOnlyReleasesAfterTheGrace() =
        engineTest {
            val awake = FakeKeepAwake()
            val h = harness(awake, FakeProtocol(seeding = true))
            h.engine.start()
            val id = h.addJob()
            until(h, id, DownloadStatus.SEEDING) shouldBe true
            awake.held shouldBe true

            advanceTimeBy(4900.milliseconds)
            runCurrent()
            awake.releases shouldBe 0

            advance(100.milliseconds)
            awake.releases shouldBe 1
            awake.held shouldBe false
        }

    @Test
    fun backToBackJobsKeepOneHold() =
        engineTest {
            val awake = FakeKeepAwake()
            val h = harness(awake)
            h.engine.start()
            h.engine.setLimit(1)
            h.addJob()
            val second = h.addJob()
            until(h, second, DownloadStatus.COMPLETED) shouldBe true
            awake.releases shouldBe 0

            advanceTimeBy(4900.milliseconds)
            runCurrent()
            awake.releases shouldBe 0
            awake.held shouldBe true

            advance(100.milliseconds)
            awake.releases shouldBe 1
            awake.held shouldBe false
        }

    @Test
    fun muxingHolds() =
        engineTest {
            val gate = CompletableDeferred<Unit>()
            val quick = FakeProtocol()
            val gated =
                object : Protocol by quick {
                    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
                        gate.await()
                        return quick.finalize(transfer)
                    }
                }
            val awake = FakeKeepAwake()
            val h = harness(awake, quick, DownloadKind.entries.associateWith { quick } + (DownloadKind.SITE to gated))
            h.engine.start()
            val id =
                h.engine
                    .add(
                        NewDownload(
                            DownloadKind.SITE,
                            mirrors = listOf("https://example.com/video"),
                            saveFolder = stepRoot.toString(),
                            fileName = "video.zip",
                        ),
                    ).shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
                    .id
            until(h, id, DownloadStatus.MUXING) shouldBe true

            advance(30.seconds)

            h.status(id) shouldBe DownloadStatus.MUXING
            awake.held shouldBe true
            awake.releases shouldBe 0

            gate.complete(Unit)
            advance(6.seconds)
            h.status(id) shouldBe DownloadStatus.COMPLETED
            awake.held shouldBe false
        }

    @Test
    fun turningItOffReleasesAtOnceAndALaterJobDoesNotAcquire() =
        engineTest {
            val awake = FakeKeepAwake()
            val h = harness(awake)
            h.engine.start()
            h.addJob()
            advance(1.seconds)
            awake.held shouldBe true
            val acquired = awake.acquires

            h.engine.setKeepAwake(false) shouldBe CommandResult.Done(null)
            runCurrent()

            awake.held shouldBe false
            awake.releases shouldBe 1
            h.engine.keepAwake() shouldBe false

            h.addJob()
            advance(10.seconds)

            awake.acquires shouldBe acquired
            awake.held shouldBe false
        }

    @Test
    fun itIsOnByDefault() =
        engineTest {
            val h = harness(FakeKeepAwake())
            h.engine.keepAwake() shouldBe true
            h.engine.start()
            h.engine.keepAwake() shouldBe true
        }

    @Test
    fun stopReleases() =
        engineTest {
            val awake = FakeKeepAwake()
            val h = harness(awake)
            h.engine.start()
            h.addJob()
            advance(1.seconds)
            awake.held shouldBe true

            h.engine.stop()

            awake.held shouldBe false
            awake.releases shouldBe 1
        }

    @Test
    fun aFailedEngineReleases() =
        engineTest {
            val awake = FakeKeepAwake()
            val h = harness(awake)
            h.engine.start()
            h.addJob()
            advance(1.seconds)
            awake.held shouldBe true

            h.engine.failed(IllegalStateException("x"))
            runCurrent()

            awake.held shouldBe false
            awake.releases shouldBe 1
        }

    @Test
    fun acquireIsAskedAgainWhileWanted() =
        engineTest {
            val awake = FakeKeepAwake()
            val h = harness(awake)
            h.engine.start()
            h.addJob()
            advance(1.seconds)
            awake.acquires shouldBe 1

            h.addJob()
            advance(1.seconds)

            // Asked once for each change the second job brings, as long as the hold is wanted.
            awake.acquires shouldBeGreaterThan 1
            awake.releases shouldBe 0
        }

    @Test
    fun nothingRunsAfterStop() =
        engineTest {
            val awake = FakeKeepAwake()
            val probe = FakeOnlineProbe()
            val h = harness(awake, probe = probe)
            h.engine.start()
            h.addJob()
            advance(2.seconds)
            h.engine.stop()
            val polls = probe.calls
            val log = awake.log.toList()

            advance(1.hours)

            probe.calls shouldBe polls
            awake.log shouldBe log
        }
}
