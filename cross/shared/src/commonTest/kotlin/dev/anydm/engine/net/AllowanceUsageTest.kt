package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.store.DayTraffic
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Instant

/** Every network's usage against its allowance (plexo-networks-ui), from the stored days. UTC. */
class AllowanceUsageTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z") // a Wednesday
    private val gb = 1_000_000_000L

    private fun day(
        day: String,
        network: String,
        bytes: Long,
        up: Long = 0,
    ) = DayTraffic(day, network, DownloadKind.DIRECT, bytes, up)

    private fun usage(
        rules: NetworkRules,
        days: List<DayTraffic>,
        extra: Set<String> = emptySet(),
        pending: (String, String, String) -> Long = { _, _, _ -> 0 },
    ) = allowanceUsageOf(rules, days, now, TimeZone.UTC, extra, pending)

    @Test
    fun aDailyAllowanceCountsOnlyTodayOnItsNetwork() {
        val rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(allowance = Allowance(2 * gb, AllowancePeriod.Daily))))
        val days =
            listOf(
                day("2026-10-20", "usb", 9 * gb),
                day("2026-10-21", "usb", gb, up = gb / 2),
                day("2026-10-21", "wifi", 7 * gb),
            )

        val status = usage(rules, days).getValue("usb")

        status.used shouldBe 3 * gb / 2
        status.limit shouldBe 2 * gb
        status.over shouldBe false
        status.from shouldBe LocalDate.parse("2026-10-21")
        status.resetsAt shouldBe Instant.parse("2026-10-22T00:00:00Z")
        usage(rules, days).keys shouldBe setOf("usb")
    }

    @Test
    fun reachingTheLimitIsOver() {
        val rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(allowance = Allowance(2 * gb, AllowancePeriod.Daily))))

        usage(rules, listOf(day("2026-10-21", "usb", 2 * gb))).getValue("usb").over shouldBe true
    }

    @Test
    fun aWeeklyAllowanceSumsItsWholePeriodAndTheWarnLevelIsSet() {
        val policy = NetworkPolicy(allowance = Allowance(10 * gb, AllowancePeriod.Weekly(DayOfWeek.MONDAY), warnPercent = 80))
        val rules = NetworkRules(networks = mapOf("usb" to policy))
        val days = listOf(day("2026-10-18", "usb", 5 * gb), day("2026-10-19", "usb", gb), day("2026-10-21", "usb", 2 * gb))

        val status = usage(rules, days).getValue("usb")

        status.used shouldBe 3 * gb
        status.from shouldBe LocalDate.parse("2026-10-19")
        status.to shouldBe LocalDate.parse("2026-10-25")
        status.warnAt shouldBe 8 * gb
    }

    @Test
    fun theDefaultAllowanceAppliesToAKnownNetworkWithNoPolicyOfItsOwn() {
        val rules = NetworkRules(default = NetworkPolicy(allowance = Allowance(gb, AllowancePeriod.Daily)))

        val result = usage(rules, listOf(day("2026-10-21", "wifi", gb / 4)), extra = setOf("wifi", "usb"))

        result.keys shouldBe setOf("wifi", "usb")
        result.getValue("wifi").used shouldBe gb / 4
        result.getValue("usb").used shouldBe 0L
    }

    @Test
    fun aNetworkWithoutAnAllowanceHasNoEntry() {
        val rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(speedLimit = 1000)))

        usage(rules, listOf(day("2026-10-21", "usb", gb)), extra = setOf("usb")) shouldBe emptyMap()
    }

    @Test
    fun unsavedBytesOfThePeriodAreAdded() {
        val rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(allowance = Allowance(2 * gb, AllowancePeriod.Daily))))
        val asked = mutableListOf<Triple<String, String, String>>()

        val status =
            usage(rules, listOf(day("2026-10-21", "usb", gb))) { network, from, to ->
                asked += Triple(network, from, to)
                gb / 2
            }.getValue("usb")

        status.used shouldBe 3 * gb / 2
        asked shouldBe listOf(Triple("usb", "2026-10-21", "2026-10-21"))
    }
}
