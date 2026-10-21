package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.net.MeteredAction
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkState
import dev.anydm.engine.platform.FakeMeteredDetector
import dev.anydm.engine.platform.FakeOnlineProbe
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
import kotlin.time.Duration.Companion.seconds

/**
 * The engine follows the network (#543, NET-12/13): a drop pauses within two polls, a return resumes, a change of network
 * restarts the connections, and none of it counts an attempt. A 1 MiB file at 16 KiB/s, on a new database from
 * [newDriver] for each test.
 */
abstract class EngineNetworkContract : StoreTestBase() {
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

    private class Fixture(
        val h: EngineHarness,
        val probe: FakeOnlineProbe,
        val events: MutableList<EngineEvent>,
        private val key: Array<String>,
    ) {
        /** The network key the identity answers; set it to switch networks. */
        var net: String
            get() = key[0]
            set(value) {
                key[0] = value
            }
    }

    private fun TestScope.fixture(
        online: Boolean = true,
        detector: FakeMeteredDetector = FakeMeteredDetector(),
        protocol: FakeProtocol = FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))),
    ): Fixture {
        val probe = FakeOnlineProbe(online)
        val key = arrayOf("en0")
        val h =
            EngineHarness(
                this,
                newStore(),
                protocol,
                clock = virtualClock(),
                network = { key[0] },
                onlineProbe = probe,
                meteredDetector = detector,
            ).also { harnesses += it }
        val events = mutableListOf<EngineEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.engine.events.collect { events += it } }
        return Fixture(h, probe, events, key)
    }

    private suspend fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private fun EngineHarness.reasons(id: DownloadId) = snapshot(id)!!.download.pauseReasons

    private fun EngineHarness.attempts(id: DownloadId) = snapshot(id)!!.download.attemptCount

    @Test
    fun networkIsUnknownBeforeStartAndShowsTheStateAfter() =
        engineTest {
            val f = fixture(detector = FakeMeteredDetector(mutableMapOf("en0" to true)))
            f.h.engine.network.value shouldBe NetworkState.Unknown

            f.h.engine.start()

            val state = f.h.engine.network.value
            state.online shouldBe true
            state.key shouldBe "en0"
            state.metered shouldBe true
        }

    @Test
    fun aDropPausesWithinTwoPollsAndTheReturnResumes() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            advance(2.seconds)
            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING

            f.probe.online = false
            advance(9.seconds)

            f.h.status(id) shouldBe DownloadStatus.PAUSED
            f.h.reasons(id) shouldBe setOf(PauseReason.NETWORK)
            f.h.engine.network.value.online shouldBe false
            f.events.filterIsInstance<EngineEvent.NetworkLost>().size shouldBe 1

            f.probe.online = true
            advance(3.seconds)

            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING
            f.events.filterIsInstance<EngineEvent.NetworkRestored>().size shouldBe 1

            advance(80.seconds)

            f.h.status(id) shouldBe DownloadStatus.COMPLETED
            f.h.attempts(id) shouldBe 0
            f.events.filterIsInstance<EngineEvent.RetryScheduled>().shouldBeEmpty()
        }

    @Test
    fun aConnectionErrorWhileTheProbeIsOfflinePausesAtOnce() =
        engineTest {
            val f = fixture()
            f.h.protocol.failure = FakeFailure.AtTransfer(32_768, StepResult.Failed(ErrorClass.TRANSIENT, "reset"))
            f.h.engine.start()
            val id = f.h.addJob()
            advance(1.seconds)
            f.probe.online = false

            // The failure comes at 2 s, before the monitor's own second poll (6 s).
            advance(1100.milliseconds)

            f.h.status(id) shouldBe DownloadStatus.PAUSED
            f.h.reasons(id) shouldBe setOf(PauseReason.NETWORK)
            f.h.attempts(id) shouldBe 0
            f.h.engine.network.value.online shouldBe false
            f.events.filterIsInstance<EngineEvent.RetryScheduled>().shouldBeEmpty()
        }

    @Test
    fun aNetworkChangeRestartsTheConnection() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            advance(5.seconds)
            f.h.protocol.transfers.size shouldBe 1

            f.net = "en7"
            advance(3.seconds)

            f.events.filterIsInstance<EngineEvent.NetworkChanged>() shouldBe listOf(EngineEvent.NetworkChanged("en0", "en7"))
            f.h.protocol.transfers.size shouldBe 2
            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING
            f.h.attempts(id) shouldBe 0

            advance(80.seconds)

            f.h.status(id) shouldBe DownloadStatus.COMPLETED
            f.h.attempts(id) shouldBe 0
        }

    @Test
    fun goingToUnknownWhileOnlineDoesNotRestart() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            advance(5.seconds)

            f.net = "unknown"
            advance(10.seconds)

            f.events.filterIsInstance<EngineEvent.NetworkChanged>().shouldBeEmpty()
            f.h.protocol.transfers.size shouldBe 1
            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING
        }

    @Test
    fun aMeteredNewNetworkPausesInsteadOfRestarting() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            f.h.engine.setNetworkPolicy("en7", NetworkPolicy(metered = true, onMetered = MeteredAction.PAUSE))
            val id = f.h.addJob()
            advance(5.seconds)
            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING

            f.net = "en7"
            advance(5.seconds)

            f.h.status(id) shouldBe DownloadStatus.PAUSED
            f.h.reasons(id) shouldBe setOf(PauseReason.SCHEDULE)
            f.h.protocol.transfers.size shouldBe 1
        }

    @Test
    fun offlineAtStartPausesStoredJobsAndOnlineReleasesThem() =
        engineTest {
            val f = fixture(online = false)
            val ids = (1..2).map { storedJob(f, it) }
            f.h.engine.start()
            advance(30.seconds)

            f.events
                .filterIsInstance<EngineEvent.StatusChanged>()
                .filter { it.to == DownloadStatus.DOWNLOADING }
                .shouldBeEmpty()
            f.h.protocol.transfers
                .shouldBeEmpty()
            for (id in ids) {
                f.h.status(id) shouldBe DownloadStatus.PAUSED
                f.h.reasons(id) shouldBe setOf(PauseReason.NETWORK)
            }
            f.h.engine.network.value.online shouldBe false

            f.probe.online = true
            advance(3.seconds)

            for (id in ids) f.h.status(id) shouldBe DownloadStatus.DOWNLOADING
        }

    @Test
    fun aJobAddedWhileOfflineIsPausedForNetwork() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            f.probe.online = false
            advance(7.seconds)

            val id = f.h.addJob()
            runCurrent()

            f.h.status(id) shouldBe DownloadStatus.PAUSED
            f.h.reasons(id) shouldBe setOf(PauseReason.NETWORK)
        }

    @Test
    fun globalPauseSurvivesTheReturn() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            advance(2.seconds)
            f.h.engine.pauseAll()
            runCurrent()
            f.h.reasons(id) shouldBe setOf(PauseReason.GLOBAL)

            f.probe.online = false
            advance(7.seconds)
            f.h.reasons(id) shouldBe setOf(PauseReason.GLOBAL, PauseReason.NETWORK)

            f.probe.online = true
            advance(3.seconds)

            f.h.status(id) shouldBe DownloadStatus.PAUSED
            f.h.reasons(id) shouldBe setOf(PauseReason.GLOBAL)
        }

    // A job in the store before the engine starts, as the last run left it.
    private suspend fun storedJob(
        f: Fixture,
        number: Int,
    ): DownloadId =
        f.h.store.downloads
            .add(
                NewDownload(
                    DownloadKind.DIRECT,
                    mirrors = listOf("https://example.com/file-$number.zip"),
                    saveFolder = stepRoot.toString(),
                    fileName = "job-$number.zip",
                ),
            ).id
}
