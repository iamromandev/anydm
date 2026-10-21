package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DefaultRouteNetworkTest {
    private class SetClock(
        var now: Instant = Instant.parse("2026-10-18T12:00:00Z"),
    ) : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun theAnswerIsCachedForTenSeconds() {
        val clock = SetClock()
        var lookups = 0
        val network =
            DefaultRouteNetwork(clock) {
                lookups++
                "en0"
            }

        network.current() shouldBe "en0"
        network.current() shouldBe "en0"
        lookups shouldBe 1

        clock.now += 11.seconds
        network.current() shouldBe "en0"
        lookups shouldBe 2
    }

    @Test
    fun noRouteIsUnknown() {
        DefaultRouteNetwork(SetClock()) { null }.current() shouldBe "unknown"
        DefaultRouteNetwork(SetClock()) { error("no network") }.current() shouldBe "unknown"
    }

    @Test
    fun theRealLookupNeverThrows() {
        DefaultRouteNetwork().current().shouldNotBeBlank()
    }

    @Test
    fun freshIgnoresTheCacheAndUpdatesIt() {
        val clock = SetClock()
        var lookups = 0
        val network =
            DefaultRouteNetwork(clock) {
                lookups++
                "if$lookups"
            }

        network.current() shouldBe "if1"
        network.fresh() shouldBe "if2"
        lookups shouldBe 2

        clock.now += 9.seconds
        network.current() shouldBe "if2"
        lookups shouldBe 2
    }
}
