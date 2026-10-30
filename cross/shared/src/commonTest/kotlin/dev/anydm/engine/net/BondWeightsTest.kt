package dev.anydm.engine.net

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** How connections and whole downloads are shared out among networks (BND-03, FTP and SFTP). */
class BondWeightsTest {
    @Test
    fun slotsFollowSpeed() {
        apportion(8, mapOf("a" to 30.0, "b" to 10.0)) shouldBe mapOf("a" to 6, "b" to 2)
        apportion(4, mapOf("a" to 30.0, "b" to 10.0)) shouldBe mapOf("a" to 3, "b" to 1)
    }

    @Test
    fun equalSharesBeforeAnySpeedIsKnown() {
        apportion(6, mapOf("a" to 0.0, "b" to 0.0, "c" to 0.0)) shouldBe mapOf("a" to 2, "b" to 2, "c" to 2)
    }

    @Test
    fun aNetworkWithNoSpeedYetIsTreatedAsAverage() {
        // c is new: it gets the mean of the known speeds, so it is measured on a real share rather than starved.
        apportion(6, mapOf("a" to 20.0, "b" to 20.0, "c" to 0.0)) shouldBe mapOf("a" to 2, "b" to 2, "c" to 2)
    }

    @Test
    fun everyNetworkKeepsOneSlotEvenWhenSlow() {
        apportion(4, mapOf("a" to 1000.0, "b" to 1.0)) shouldBe mapOf("a" to 3, "b" to 1)
        // Fewer slots than networks: the floor wins, and the total is the number of networks.
        apportion(1, mapOf("a" to 5.0, "b" to 1.0)) shouldBe mapOf("a" to 1, "b" to 1)
    }

    @Test
    fun noNetworksNoSlots() {
        apportion(5, emptyMap()) shouldBe emptyMap()
    }

    @Test
    fun theSharesAddUpAndNoneIsBelowOne() =
        runTest {
            checkAll(Arb.int(1..64), Arb.list(Arb.double(0.0, 1e9), 1..6)) { total, speeds ->
                val named = speeds.mapIndexed { i, s -> "n$i" to s }.toMap()
                val shares = apportion(total, named)
                shares.values.sum() shouldBe maxOf(total, named.size)
                shares.values.forEach { it shouldBeGreaterThanOrEqual 1 }
            }
        }

    @Test
    fun aFasterNetworkNeverGetsFewerSlots() =
        runTest {
            checkAll(Arb.int(2..64), Arb.list(Arb.double(1.0, 1e9), 2..6)) { total, speeds ->
                val named = speeds.mapIndexed { i, s -> "n$i" to s }.toMap()
                val shares = apportion(total, named)
                for ((a, sa) in named) {
                    for ((b, sb) in named) {
                        if (sa > sb) shares.getValue(a) shouldBeGreaterThanOrEqual shares.getValue(b)
                    }
                }
            }
        }

    @Test
    fun theSameInputsGiveTheSameShares() {
        val speeds = mapOf("b" to 10.0, "a" to 10.0, "c" to 10.0)
        apportion(10, speeds) shouldBe apportion(10, speeds.entries.reversed().associate { it.key to it.value })
    }

    @Test
    fun theNextConnectionGoesWhereTheShortfallIsLargest() {
        deficitPick(current = mapOf("a" to 3, "b" to 0), targets = mapOf("a" to 4, "b" to 2), speeds = emptyMap()) shouldBe "b"
        deficitPick(current = mapOf("a" to 1, "b" to 1), targets = mapOf("a" to 3, "b" to 2), speeds = emptyMap()) shouldBe "a"
    }

    @Test
    fun aTieGoesToTheFasterNetwork() {
        deficitPick(
            current = mapOf("a" to 0, "b" to 0),
            targets = mapOf("a" to 1, "b" to 1),
            speeds = mapOf("a" to 5.0, "b" to 9.0),
        ) shouldBe "b"
    }

    @Test
    fun nothingIsPickedWhenEveryNetworkHasItsShare() {
        deficitPick(current = mapOf("a" to 2, "b" to 1), targets = mapOf("a" to 2, "b" to 1), speeds = emptyMap()) shouldBe null
    }

    @Test
    fun aNetworkWithNoConnectionsYetCountsAsZero() {
        deficitPick(current = emptyMap(), targets = mapOf("a" to 1), speeds = emptyMap()) shouldBe "a"
    }

    @Test
    fun aWholeDownloadGoesToTheLeastLoadedPerUnitOfSpeed() {
        // a is three times as fast: the first goes to the faster of two idle networks, the second to the idle one, and then a
        // takes three more before b's load per unit of speed is the lower again.
        val speeds = mapOf("a" to 30.0, "b" to 10.0)
        val running = mutableMapOf("a" to 0, "b" to 0)
        val order = mutableListOf<String>()
        repeat(5) {
            val chosen = pickForDownload(listOf("a", "b"), running, speeds)
            order += chosen
            running[chosen] = running.getValue(chosen) + 1
        }
        order shouldContainExactly listOf("a", "b", "a", "a", "a")
    }

    @Test
    fun withNoSpeedsWholeDownloadsAlternate() {
        pickForDownload(listOf("a", "b"), mapOf("a" to 1, "b" to 0), emptyMap()) shouldBe "b"
        pickForDownload(listOf("a", "b"), mapOf("a" to 0, "b" to 0), emptyMap()) shouldBe "a"
    }
}
