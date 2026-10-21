package dev.anydm.engine

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.net.MeteredAction
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.RuleSource
import dev.anydm.engine.net.SlowMode
import dev.anydm.engine.platform.FakeMeteredDetector
import dev.anydm.engine.platform.FakeOnlineProbe
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Metered networks (#543, NET-14): the detector's answer and the user's flag reach the rules, on a 1 MiB file at 16 KiB/s,
 * on a new database from [newDriver] for each test.
 */
abstract class EngineMeteredContract : StoreTestBase() {
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
        detector: FakeMeteredDetector,
        key: Array<String> = arrayOf("en0"),
    ): EngineHarness =
        EngineHarness(
            this,
            newStore(),
            FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))),
            clock = virtualClock(),
            network = { key[0] },
            meteredDetector = detector,
        ).also { harnesses += it }

    private suspend fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun aDetectedMeteredNetworkWithPausePauses() =
        engineTest {
            val h = harness(FakeMeteredDetector(mutableMapOf("en0" to true)))
            h.store.rules.setNetworkPolicy("en0", NetworkPolicy(onMetered = MeteredAction.PAUSE))
            h.engine.start()
            val id = h.addJob()
            advance(3.seconds)

            h.engine.rules.value.metered shouldBe true
            h.engine.rules.value.action shouldBe RuleAction.PAUSE
            h.engine.rules.value.because shouldBe setOf(RuleSource.METERED)
            h.status(id) shouldBe DownloadStatus.PAUSED
            h.snapshot(id)!!.download.pauseReasons shouldBe setOf(PauseReason.SCHEDULE)
        }

    @Test
    fun slowRunsAtTheSlowLimit() =
        engineTest {
            val h = harness(FakeMeteredDetector(mutableMapOf("en0" to true)))
            h.store.rules.setNetworkPolicy("en0", NetworkPolicy(onMetered = MeteredAction.SLOW))
            h.engine.start()
            val id = h.addJob()
            advance(3.seconds)

            h.engine.rules.value.action shouldBe RuleAction.SLOW
            h.protocol.globalLimits.last() shouldBe (SlowMode.DEFAULT_LIMIT to SlowMode.DEFAULT_LIMIT)
            h.protocol.globalLimits.last() shouldBe (1_048_576L to 1_048_576L)
            h.status(id) shouldBe DownloadStatus.DOWNLOADING
        }

    @Test
    fun notMeteredIsAllowed() =
        engineTest {
            val h = harness(FakeMeteredDetector(mutableMapOf("en0" to false)))
            h.store.rules.setNetworkPolicy("en0", NetworkPolicy(onMetered = MeteredAction.PAUSE))
            h.engine.start()
            val id = h.addJob()
            advance(3.seconds)

            h.engine.rules.value.metered shouldBe false
            h.engine.rules.value.action shouldBe RuleAction.ALLOW
            h.status(id) shouldBe DownloadStatus.DOWNLOADING
        }

    @Test
    fun theUsersFlagWinsOverANullDetector() =
        engineTest {
            val h = harness(FakeMeteredDetector())
            h.store.rules.setNetworkPolicy("en0", NetworkPolicy(metered = true, onMetered = MeteredAction.PAUSE))
            h.engine.start()
            val id = h.addJob()
            advance(3.seconds)

            h.engine.network.value.metered shouldBe null
            h.engine.rules.value.metered shouldBe true
            h.status(id) shouldBe DownloadStatus.PAUSED
        }

    @Test
    fun detectionIsRefreshedEveryFiveMinutesAndOnAKeyChange() =
        engineTest {
            val key = arrayOf("en0")
            val detector = FakeMeteredDetector(mutableMapOf("en0" to false, "en7" to true))
            val h = harness(detector, key)
            h.engine.start()
            detector.calls shouldBe listOf("en0")

            advance(4.minutes)
            detector.calls shouldBe listOf("en0")

            advance(1.minutes + 5.seconds)
            detector.calls shouldBe listOf("en0", "en0")

            key[0] = "en7"
            advance(3.seconds)
            detector.calls.last() shouldBe "en7"
            detector.calls.size shouldBe 3
            h.engine.network.value.metered shouldBe true
        }

    @Test
    fun aWakeAsksTheDetectorAgainOnTheSameInterface() =
        engineTest {
            val detector = FakeMeteredDetector(mutableMapOf("en0" to false))
            val clock = ShiftedClock(virtualClock())
            val h =
                EngineHarness(
                    this,
                    newStore(),
                    FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))),
                    clock = clock,
                    network = { "en0" },
                    meteredDetector = detector,
                    wakeGap = 30.seconds,
                ).also { harnesses += it }
            val woke = mutableListOf<EngineEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.engine.events.collect { woke += it } }
            h.engine.start()
            h.engine.network.value.metered shouldBe false
            advance(6.seconds)

            // Home Wi-Fi to a hotspot on the same interface while asleep: no change of key, and well under five minutes.
            detector.answers["en0"] = true
            clock.offset += 1.minutes
            advance(10.seconds)

            woke.filterIsInstance<EngineEvent.SystemWoke>().size shouldBe 1
            detector.calls.size shouldBe 2
            h.engine.network.value.metered shouldBe true
        }

    @Test
    fun aReturnAfterAConnectionErrorAsksTheDetectorAgain() =
        engineTest {
            val detector = FakeMeteredDetector(mutableMapOf("en0" to false))
            val probe = FakeOnlineProbe()
            val h =
                EngineHarness(
                    this,
                    newStore(),
                    FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))).also {
                        it.failure = FakeFailure.AtTransfer(32_768, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
                    },
                    clock = virtualClock(),
                    network = { "en0" },
                    onlineProbe = probe,
                    meteredDetector = detector,
                ).also { harnesses += it }
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.engine.events.collect { events += it } }
            h.engine.start()
            h.addJob()
            advance(1.seconds)
            probe.online = false
            advance(1100.milliseconds)
            probe.online = true
            detector.answers["en0"] = true
            advance(5.seconds)

            events.filterIsInstance<EngineEvent.NetworkRestored>().size shouldBe 1
            detector.calls.size shouldBe 2
            h.engine.network.value.metered shouldBe true
        }

    @Test
    fun aSlowDetectorKeepsThePreviousValue() =
        engineTest {
            val detector = FakeMeteredDetector(mutableMapOf("en0" to true))
            val h = harness(detector)
            h.engine.start()
            h.engine.network.value.metered shouldBe true

            detector.answers["en0"] = false
            detector.delay = 10.seconds
            advance(5.minutes + 20.seconds)

            // The answer came after the timeout: the first call's value stands, and the next ask is 30 s away.
            detector.calls.size shouldBe 2
            h.engine.network.value.metered shouldBe true
        }

    @Test
    fun aStartOnAMeteredNetworkNeverStartsAJob() =
        engineTest {
            val h = harness(FakeMeteredDetector(mutableMapOf("en0" to true)))
            h.store.rules.setNetworkPolicy("en0", NetworkPolicy(onMetered = MeteredAction.PAUSE))
            val id =
                h.store.downloads
                    .add(
                        NewDownload(
                            DownloadKind.DIRECT,
                            mirrors = listOf("https://example.com/file-1.zip"),
                            saveFolder = stepRoot.toString(),
                            fileName = "job-1.zip",
                        ),
                    ).id
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.engine.events.collect { events += it } }

            h.engine.start()
            advance(30.seconds)

            events.filterIsInstance<EngineEvent.StatusChanged>().filter { it.to == DownloadStatus.DOWNLOADING }.shouldBeEmpty()
            h.protocol.transfers.shouldBeEmpty()
            h.status(id) shouldBe DownloadStatus.PAUSED
            h.snapshot(id)!!.download.pauseReasons shouldBe setOf(PauseReason.SCHEDULE)
        }
}
