package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

/** The steps the engine takes from what the monitor observed (NET-12, NET-13). */
class NetworkWatchTest {
    private val t0 = Instant.parse("2026-10-21T12:00:00Z")

    private fun seen(
        online: Boolean = true,
        key: String = "en0",
        metered: Boolean? = null,
        drops: Int = 0,
    ) = Observed(NetworkState(online, if (online) key else "unknown", metered, t0), drops)

    @Test
    fun aDropIsLost() {
        val watch = NetworkWatch(seen())
        watch.next(seen(online = false, drops = 1)) shouldBe listOf(NetworkStep.Lost)
    }

    @Test
    fun aReturnIsRestored() {
        val watch = NetworkWatch(seen())
        watch.next(seen(online = false, drops = 1))
        watch.next(seen(drops = 1)) shouldBe listOf(NetworkStep.Restored)
    }

    @Test
    fun aMissedDropStillEndsRestored() {
        val watch = NetworkWatch(seen())
        watch.next(seen(drops = 1)) shouldBe listOf(NetworkStep.Lost, NetworkStep.Restored)
    }

    @Test
    fun aKeyChangeWhileOnlineIsChanged() {
        val watch = NetworkWatch(seen())
        watch.next(seen(key = "en7")) shouldBe listOf(NetworkStep.Changed("en0", "en7"))
    }

    @Test
    fun toOrFromUnknownIsNoChange() {
        val watch = NetworkWatch(seen())
        watch.next(seen(key = "unknown")) shouldBe emptyList()
        watch.next(seen(key = "en7")) shouldBe emptyList()
    }

    @Test
    fun aReturnOnANewKeyIsOnlyRestored() {
        val watch = NetworkWatch(seen())
        watch.next(seen(online = false, drops = 1))
        watch.next(seen(key = "en7", drops = 1)) shouldBe listOf(NetworkStep.Restored)
    }

    @Test
    fun meteredChangedOnTheSameKey() {
        val watch = NetworkWatch(seen())
        watch.next(seen(metered = true)) shouldBe listOf(NetworkStep.MeteredChanged)
        watch.next(seen(metered = true)) shouldBe emptyList()
        watch.next(seen(metered = null)) shouldBe listOf(NetworkStep.MeteredChanged)
    }

    @Test
    fun nothingNewIsNothing() {
        val watch = NetworkWatch(seen())
        watch.next(seen()) shouldBe emptyList()
    }
}
