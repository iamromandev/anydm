package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.NetworkIdentity
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.SpeedLimit
import dev.anydm.engine.net.currentBond
import dev.anydm.engine.platform.FakeNetworkBinder
import dev.anydm.engine.platform.FakeNetworkLister
import dev.anydm.engine.platform.network
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What limits a running download is published in its snapshot (#634). FakeProtocol: 16 KiB/s over a 1 MiB file. */
abstract class EngineLimitContract : StoreTestBase() {
    private val rate = 16 * 1024L

    private fun engineTest(body: suspend TestScope.(EngineHarness) -> Unit) =
        runTest {
            val h = EngineHarness(this, newStore(), FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))))
            try {
                h.engine.start()
                body(h)
            } finally {
                h.engine.stop()
            }
        }

    private suspend fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun nothingIsNamedWhenNothingLimits() =
        engineTest { h ->
            val id = h.addJob()
            advance(4.seconds)

            h.snapshot(id)!!.speed shouldBe rate
            h.snapshot(id)!!.limitedBy shouldBe null
        }

    @Test
    fun aSpeedLimitIsNamedWhileTheDownloadRunsAtIt() =
        engineTest { h ->
            h.engine.setGlobalSpeedLimit(rate)
            val id = h.addJob()
            advance(4.seconds)

            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.SpeedCap
        }

    @Test
    fun slowModeIsNamedBeforeASpeedLimit() =
        engineTest { h ->
            h.engine.setGlobalSpeedLimit(rate)
            h.engine.setSlowModeLimit(rate)
            h.engine.setSlowMode(true)
            val id = h.addJob()
            advance(4.seconds)

            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.SlowMode
        }

    @Test
    fun aLimitThatGoesIsStillNamedForFiveSeconds() =
        engineTest { h ->
            h.engine.setGlobalSpeedLimit(rate)
            val id = h.addJob()
            advance(1.seconds) // the fake delivers its first second in one burst, at the limit
            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.SpeedCap
            h.engine.setGlobalSpeedLimit(rate * 20) // far above what it runs at: no longer the reason

            advance(3.seconds)
            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.SpeedCap

            advance(4.seconds)
            h.snapshot(id)!!.limitedBy shouldBe null
        }

    @Test
    fun nothingIsNamedOnceTheDownloadStops() =
        engineTest { h ->
            h.engine.setGlobalSpeedLimit(rate)
            val id = h.addJob()
            advance(4.seconds)
            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.SpeedCap

            h.engine.pause(id)
            advance(1.seconds)

            h.snapshot(id)!!.limitedBy shouldBe null
        }

    @Test
    fun aSlowModeBelowAHigherGlobalLimitIsNamedSlowMode() =
        engineTest { h ->
            h.engine.setGlobalSpeedLimit(rate * 20)
            h.engine.setSlowModeLimit(rate)
            h.engine.setSlowMode(true)
            val id = h.addJob()
            advance(4.seconds)

            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.SlowMode
        }

    @Test
    fun aGlobalLimitBelowSlowModeIsNamedASpeedLimit() =
        engineTest { h ->
            h.engine.setGlobalSpeedLimit(rate)
            h.engine.setSlowModeLimit(rate * 20)
            h.engine.setSlowMode(true)
            val id = h.addJob()
            advance(4.seconds)

            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.SpeedCap
        }

    @Test
    fun theGlobalLimitIsReadFromTheStoreOnlyAtStart() =
        engineTest { h ->
            val id = h.addJob()
            advance(2.seconds)
            h.snapshot(id)!!.limitedBy shouldBe null

            // Written behind the engine's back: the engine knows the limit only from start and from its own commands.
            h.store.settings.update { it.copy(globalSpeedLimit = rate) }
            advance(4.seconds)
            h.snapshot(id)!!.limitedBy shouldBe null
        }

    @Test
    fun anOwnLimitIsNamedOnlyForTheDownloadThatHasIt() =
        engineTest { h ->
            val limited = h.addJob()
            val free = h.addJob()
            h.engine.setSpeedLimit(limited, rate)
            advance(4.seconds)

            h.snapshot(limited)!!.limitedBy shouldBe SpeedLimit.SpeedCap
            h.snapshot(free)!!.limitedBy shouldBe null
        }

    @Test
    fun anEndedDownloadStartsAgainWithoutItsOldHeldReason() =
        engineTest { h ->
            h.engine.setGlobalSpeedLimit(rate)
            val id = h.addJob()
            advance(4.seconds)
            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.SpeedCap

            h.engine.pause(id)
            h.engine.setGlobalSpeedLimit(null)
            h.engine.resume(id)
            advance(1.seconds) // within the hold of the old reason, which would still show had its hold been kept
            h.snapshot(id)!!.speed shouldBe rate
            h.snapshot(id)!!.limitedBy shouldBe null
        }

    @Test
    fun aDataLimitWhoseSlowActionCapsTheSpeedIsNamedWithItsNetwork() =
        engineTest { h ->
            h.engine.setNetworkPolicy(
                "unknown",
                NetworkPolicy(allowance = Allowance(1_000, AllowancePeriod.Daily, atLimit = AllowanceAction.SLOW)),
            )
            h.engine.setSlowModeLimit(rate)
            val id = h.addJob()
            advance(5.seconds)

            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.DataLimit("unknown", "unknown")
        }

    @Test
    fun aSlowerRunAfterALongPauseIsNotComparedWithThePeakOfTheEarlierRun() =
        engineTest { h ->
            h.engine.setNetworkPolicy(
                "unknown",
                NetworkPolicy(allowance = Allowance(1_000, AllowancePeriod.Daily, atLimit = AllowanceAction.SLOW)),
            )
            h.engine.setSlowModeLimit(rate * 100) // the data limit caps nothing here, so it counts only below the peak
            val id = h.addJob()
            advance(3.seconds)
            h.snapshot(id)!!.limitedBy shouldBe null

            h.engine.pause(id)
            advance(30.seconds)
            h.protocol.bytesPerSecond = rate / 4
            h.engine.resume(id)
            advance(3.seconds)

            h.snapshot(id)!!.limitedBy shouldBe null
        }

    // Two networks, the download on [used]; the other one fails once the download has run for 3 s, and the download slows to a quarter.
    private fun bondedTest(
        used: String,
        failing: String,
        body: suspend TestScope.(EngineHarness, DownloadId) -> Unit,
    ) = runTest {
        val h =
            EngineHarness(
                this,
                newStore(),
                FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))),
                network = NetworkIdentity.Unknown,
                networkLister = FakeNetworkLister(network("en0"), network("en5")),
                networkBinder = FakeNetworkBinder(),
            )
        val scope = this
        try {
            h.engine.start()
            h.engine.setNetworkEnabled("en0", true)
            h.engine.setNetworkEnabled("en5", true)
            h.protocol.network = used
            h.protocol.onTransfer = {
                val bond = currentBond()
                scope.launch {
                    kotlinx.coroutines.delay(3.seconds)
                    bond?.fault(failing)
                    h.protocol.bytesPerSecond = rate / 4
                }
            }
            val id = h.addJob()
            body(h, id)
        } finally {
            h.engine.stop()
        }
    }

    @Test
    fun anUnreachableNetworkTheDownloadUsesIsNamedWhenItSlows() =
        bondedTest("en5", "en5") { h, id ->
            advance(8.seconds)

            h.snapshot(id)!!.limitedBy shouldBe SpeedLimit.Unreachable("en5")
        }

    @Test
    fun anUnreachableNetworkTheDownloadDoesNotUseIsNotBlamed() =
        bondedTest("en0", "en5") { h, id ->
            advance(8.seconds)

            h.snapshot(id)!!.limitedBy shouldBe null
        }
}
