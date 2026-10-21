package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.AfterQueueState
import dev.anydm.engine.platform.FakeKeepAwake
import dev.anydm.engine.platform.FakeOnlineProbe
import dev.anydm.engine.platform.FakePowerActions
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.DATABASE_FILE
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The OS and network state across an engine stop and start on a real database file (#543): offline at start, a network
 * pause left by the last run, an armed after-queue action and the keep-awake setting. A 1 MiB file at 16 KiB/s.
 */
class EngineOsStateRestartTest {
    private val root = newTempFolder("anydm-engine-os-state-test-")
    private val file = Path(root, DATABASE_FILE)
    private val stores = mutableListOf<Store>()
    private val harnesses = mutableListOf<EngineHarness>()

    @AfterTest
    fun cleanUp() {
        stores.forEach { runCatching { it.close() } }
        deleteRecursively(root)
    }

    private fun TestScope.open(): Store = Store(openDatabaseFile(file), StandardTestDispatcher(testScheduler)).also { stores += it }

    private fun tracked(h: EngineHarness): EngineHarness = h.also { harnesses += it }

    // A failed assertion must not leave an engine's endless timers for runTest to drain.
    private fun restartTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                harnesses.forEach { it.engine.stop() }
            }
        }

    private fun protocol() = FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20)))

    private fun EngineHarness.reasons(id: DownloadId) = snapshot(id)!!.download.pauseReasons

    private suspend fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun offlineAtRestartLetsNoJobStart() =
        restartTest {
            val clock = virtualClock()
            val first = tracked(EngineHarness(this, open(), protocol(), clock = clock))
            first.engine.start()
            first.engine.setLimit(1)
            val a = first.addJob()
            val b = first.addJob()
            advance(3.seconds)
            first.status(a) shouldBe DownloadStatus.DOWNLOADING
            (first.status(b) in setOf(DownloadStatus.PENDING, DownloadStatus.QUEUED)) shouldBe true
            first.engine.stop()

            val probe = FakeOnlineProbe(online = false)
            val second = tracked(EngineHarness(this, open(), protocol(), fs = first.fs, clock = clock, onlineProbe = probe))
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { second.engine.events.collect { events += it } }
            runCurrent()
            second.engine.start()
            advance(30.seconds)

            events.filterIsInstance<EngineEvent.StatusChanged>().filter { it.to == DownloadStatus.DOWNLOADING }.shouldBeEmpty()
            second.protocol.fetched.shouldBeEmpty()
            for (id in listOf(a, b)) {
                second.status(id) shouldBe DownloadStatus.PAUSED
                second.reasons(id) shouldBe setOf(PauseReason.NETWORK)
            }

            probe.online = true
            advance(4.seconds)

            second.status(a) shouldBe DownloadStatus.DOWNLOADING
            second.reasons(a) shouldBe emptySet()
        }

    @Test
    fun aNetworkPauseLeftByTheLastRunEndsWhenOnline() =
        restartTest {
            val clock = virtualClock()
            val probe = FakeOnlineProbe()
            val first = tracked(EngineHarness(this, open(), protocol(), clock = clock, onlineProbe = probe))
            first.engine.start()
            val id = first.addJob()
            advance(3.seconds)
            first.status(id) shouldBe DownloadStatus.DOWNLOADING
            probe.online = false
            advance(10.seconds)
            first.status(id) shouldBe DownloadStatus.PAUSED
            first.reasons(id) shouldBe setOf(PauseReason.NETWORK)
            first.engine.stop()

            val second = tracked(EngineHarness(this, open(), protocol(), fs = first.fs, clock = clock))
            second.engine.start()
            advance(4.seconds)

            second.status(id) shouldBe DownloadStatus.DOWNLOADING
            second.reasons(id) shouldBe emptySet()
            second.snapshot(id)!!.download.attemptCount shouldBe 0
        }

    @Test
    fun anArmedActionSurvivesARestartWithoutFiring() =
        restartTest {
            val clock = virtualClock()
            val first = tracked(EngineHarness(this, open(), protocol(), clock = clock))
            first.engine.start()
            first.engine.setAfterQueue(AfterQueueAction.SLEEP)
            runCurrent()
            first.engine.stop()

            val power = FakePowerActions()
            val second = tracked(EngineHarness(this, open(), protocol(), clock = clock, powerActions = power))
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { second.engine.events.collect { events += it } }
            runCurrent()
            second.engine.start()
            advance(2.minutes)

            second.engine.afterQueue.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)
            events.filterIsInstance<EngineEvent.AfterQueueCountdown>().shouldBeEmpty()
            power.calls.shouldBeEmpty()
        }

    @Test
    fun anArmedActionFiresAfterTheRestartedWorkEnds() =
        restartTest {
            val clock = virtualClock()
            val first = tracked(EngineHarness(this, open(), protocol(), clock = clock))
            first.engine.start()
            val id = first.addJob()
            first.engine.setAfterQueue(AfterQueueAction.SLEEP)
            advance(5.seconds)
            first.status(id) shouldBe DownloadStatus.DOWNLOADING
            first.engine.stop()

            val power = FakePowerActions()
            val second = tracked(EngineHarness(this, open(), protocol(), fs = first.fs, clock = clock, powerActions = power))
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { second.engine.events.collect { events += it } }
            runCurrent()
            second.engine.start()
            advance(1.seconds)
            // Starting fired nothing: the action waits for the work to end.
            events.filterIsInstance<EngineEvent.AfterQueueCountdown>().shouldBeEmpty()
            second.engine.afterQueue.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)

            var spent = Duration.ZERO
            while (second.status(id) != DownloadStatus.COMPLETED && spent < 3.minutes) {
                advance(100.milliseconds)
                spent += 100.milliseconds
            }
            second.status(id) shouldBe DownloadStatus.COMPLETED
            events.filterIsInstance<EngineEvent.AfterQueueCountdown>().size shouldBe 1
            power.calls.shouldBeEmpty()

            advance(31.seconds)

            power.calls shouldBe listOf("sleep")
            second.engine.afterQueue.value shouldBe AfterQueueState.Idle
        }

    @Test
    fun keepAwakeOffSurvivesARestart() =
        restartTest {
            val clock = virtualClock()
            val first = tracked(EngineHarness(this, open(), protocol(), clock = clock))
            first.engine.start()
            first.engine.setKeepAwake(false)
            first.engine.stop()

            val keep = FakeKeepAwake()
            val second = tracked(EngineHarness(this, open(), protocol(), clock = clock, keepAwake = keep))
            second.engine.start()
            second.addJob()
            advance(10.seconds)

            second.engine.keepAwake() shouldBe false
            keep.acquires shouldBe 0
        }
}
