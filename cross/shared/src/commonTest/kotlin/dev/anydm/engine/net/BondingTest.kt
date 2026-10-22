package dev.anydm.engine.net

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.FakeNetworkBinder
import dev.anydm.engine.platform.FakeNetworkLister
import dev.anydm.engine.platform.network
import dev.anydm.engine.platform.virtualClock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The network list, the user's choices and which networks are in use (BND-01, BND-02), on virtual time. */
class BondingTest {
    private val t0 = Instant.parse("2026-10-21T12:00:00Z")

    private class Choices(
        var enabled: Set<String> = emptySet(),
    ) : BondChoices {
        override suspend fun enabled() = enabled

        override suspend fun set(
            key: String,
            enabled: Boolean,
        ) {
            this.enabled = if (enabled) this.enabled + key else this.enabled - key
        }
    }

    private class Fixture(
        test: TestScope,
        t0: Instant,
        vararg networks: dev.anydm.engine.net.NetworkInfo,
    ) {
        val lister = FakeNetworkLister(*networks)
        val binder = FakeNetworkBinder()
        val choices = Choices()
        val clock = test.virtualClock(t0)
        val bonding = Bonding(lister, binder, choices, clock, Log(RecordingLogSink(), clock))

        fun active() =
            bonding.state.value.active
                .map { it.info.key }
    }

    @Test
    fun listsEveryNetworkWithItsChoiceAndBindStatus() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.choices.enabled = setOf("en5")
            f.binder.statuses["en5"] = BindStatus.BEST_EFFORT

            f.bonding.refresh()

            f.bonding.state.value.networks
                .map { Triple(it.info.key, it.enabled, it.status) } shouldContainExactly
                listOf(
                    Triple("en0", false, BindStatus.ISOLATED),
                    Triple("en5", true, BindStatus.BEST_EFFORT),
                )
        }

    @Test
    fun bondingNeedsTwoEnabledNetworksThatAreUp() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.bonding.refresh()
            f.bonding.state.value.bonded shouldBe false

            f.bonding.setEnabled("en0", true)
            f.active() shouldContainExactly listOf("en0")
            f.bonding.state.value.bonded shouldBe false

            f.bonding.setEnabled("en5", true)
            f.active() shouldContainExactly listOf("en0", "en5")
            f.bonding.state.value.bonded shouldBe true
        }

    @Test
    fun aNetworkThatCannotBeBoundIsListedButNeverUsed() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.binder.statuses["en5"] = BindStatus.UNSUPPORTED
            f.choices.enabled = setOf("en0", "en5")

            f.bonding.refresh()

            f.bonding.state.value.networks.size shouldBe 2
            f.active() shouldContainExactly listOf("en0")
            f.bonding.state.value.bonded shouldBe false
        }

    @Test
    fun aNetworkThatIsDownIsNotUsedAndIsProbedAgainWhenItReturns() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.choices.enabled = setOf("en0", "en5")
            f.bonding.refresh()
            f.binder.probes["en5"] shouldBe 1

            f.lister.networks = listOf(network("en0"), network("en5", up = false))
            f.bonding.refresh()
            f.active() shouldContainExactly listOf("en0")

            f.lister.networks = listOf(network("en0"), network("en5"))
            f.bonding.refresh()
            f.active() shouldContainExactly listOf("en0", "en5")
            f.binder.probes["en5"] shouldBe 2
        }

    @Test
    fun aNetworkIsNotProbedAgainWhileItStaysTheSame() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.bonding.refresh()
            f.bonding.refresh()
            f.bonding.refresh()

            f.binder.probes shouldBe mapOf("en0" to 1, "en5" to 1)
        }

    @Test
    fun aNewAddressMeansAnotherProbe() =
        runTest {
            val f = Fixture(this, t0, network("en5", addresses = arrayOf("10.0.0.1")))
            f.bonding.refresh()

            f.lister.networks = listOf(network("en5", addresses = arrayOf("192.168.1.9")))
            f.bonding.refresh()

            f.binder.probes["en5"] shouldBe 2
        }

    @Test
    fun theChoiceForANetworkThatIsNotThereIsKeptForWhenItReturns() =
        runTest {
            val f = Fixture(this, t0, network("en0"))
            f.bonding.refresh()

            f.bonding.setEnabled("en7", true)
            f.choices.enabled shouldBe setOf("en7")
            f.bonding.state.value.networks
                .map { it.info.key } shouldContainExactly listOf("en0")

            f.lister.networks = listOf(network("en0"), network("en7"))
            f.bonding.refresh()
            f.bonding.state.value.networks
                .first { it.info.key == "en7" }
                .enabled shouldBe true
        }

    @Test
    fun turningANetworkOffRemovesItFromUse() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.choices.enabled = setOf("en0", "en5")
            f.bonding.refresh()

            f.bonding.setEnabled("en5", false)

            f.active() shouldContainExactly listOf("en0")
            f.choices.enabled shouldBe setOf("en0")
        }

    @Test
    fun theListOnlyChangesWhenSomethingChanged() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.bonding.refresh()
            val first = f.bonding.state.value

            f.bonding.refresh()

            (f.bonding.state.value === first) shouldBe true
        }

    @Test
    fun speedsAreSmoothedAndKeptWhileANetworkIsIdle() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.bonding.observe(mapOf("en0" to 1000L))
            f.bonding.speeds()["en0"] shouldBe 1000.0

            f.bonding.observe(mapOf("en0" to 2000L))
            // 30% of the new second: 1000 + 0.3 * (2000 - 1000)
            f.bonding.speeds()["en0"] shouldBe 1300.0

            f.bonding.observe(emptyMap())
            f.bonding.speeds()["en0"] shouldBe 1300.0
            f.bonding.speeds().containsKey("en5") shouldBe false
        }

    @Test
    fun connectionsAreSharedByMeasuredSpeedAmongTheActiveNetworks() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"), network("en6"))
            f.choices.enabled = setOf("en0", "en5")
            f.bonding.refresh()
            f.bonding.observe(mapOf("en0" to 3000L, "en5" to 1000L, "en6" to 9000L))

            f.bonding.targets(8) shouldBe mapOf("en0" to 6, "en5" to 2)
        }

    @Test
    fun withoutBondingThereAreNoTargets() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.choices.enabled = setOf("en0")
            f.bonding.refresh()

            f.bonding.targets(8) shouldBe emptyMap()
        }

    @Test
    fun wholeDownloadsAreSpreadAndALeaseGivesItsPlaceBack() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.choices.enabled = setOf("en0", "en5")
            f.bonding.refresh()

            val first = checkNotNull(f.bonding.leaseWhole())
            val second = checkNotNull(f.bonding.leaseWhole())
            setOf(first.network.key, second.network.key) shouldBe setOf("en0", "en5")

            first.close()
            checkNotNull(f.bonding.leaseWhole()).network.key shouldBe first.network.key
        }

    @Test
    fun noLeaseWithoutBonding() =
        runTest {
            val f = Fixture(this, t0, network("en0"))
            f.choices.enabled = setOf("en0")
            f.bonding.refresh()

            f.bonding.leaseWhole() shouldBe null
        }

    @Test
    fun aNetworkThatFailedIsLeftOutForAWhile() =
        runTest {
            val f = Fixture(this, t0, network("en0"), network("en5"))
            f.choices.enabled = setOf("en0", "en5")
            f.bonding.refresh()

            f.bonding.fault("en5")
            f.active() shouldContainExactly listOf("en0")
            f.bonding.state.value.bonded shouldBe false

            advanceTimeBy(29.seconds)
            f.bonding.refresh()
            f.active() shouldContainExactly listOf("en0")

            advanceTimeBy(2.seconds)
            f.bonding.refresh()
            f.active() shouldContainExactly listOf("en0", "en5")
        }
}
