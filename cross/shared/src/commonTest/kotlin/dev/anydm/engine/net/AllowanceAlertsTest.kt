package dev.anydm.engine.net

import dev.anydm.engine.EngineEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant

/** The once-per-period warning and limit events (NET-07). */
class AllowanceAlertsTest {
    private val allowance = Allowance(1000, AllowancePeriod.Daily, warnPercent = 50)
    private val resets = Instant.parse("2026-10-22T00:00:00Z")

    private fun status(
        used: Long,
        network: String = "wifi",
        day: String = "2026-10-21",
        limit: Long = 1000,
    ) = AllowanceStatus(
        network,
        limit,
        used,
        LocalDate.parse(day),
        LocalDate.parse(day),
        resets,
        warnAt = limit * 50 / 100,
        over = used >= limit,
    )

    @Test
    fun aWarningIsToldOncePerPeriod() {
        val alerts = AllowanceAlerts()
        alerts.crossed(status(100), allowance) shouldBe emptyList()
        alerts.crossed(status(500), allowance) shouldBe listOf(EngineEvent.AllowanceWarning("wifi", 500, 1000, resets))
        alerts.crossed(status(600), allowance) shouldBe emptyList()
    }

    @Test
    fun jumpingPastBothTellsWarningThenReached() {
        val events = AllowanceAlerts().crossed(status(1200), allowance)
        events.size shouldBe 2
        events[0].shouldBeInstanceOf<EngineEvent.AllowanceWarning>()
        events[1].shouldBeInstanceOf<EngineEvent.AllowanceReached>()
    }

    @Test
    fun primeTellsNothingAndMarksWhatIsCrossed() {
        val alerts = AllowanceAlerts()
        alerts.prime(status(1200), allowance)
        alerts.crossed(status(1200), allowance) shouldBe emptyList()
        alerts.crossed(status(1300), allowance) shouldBe emptyList()
    }

    @Test
    fun aNewPeriodReArms() {
        val alerts = AllowanceAlerts()
        alerts.crossed(status(1200), allowance).size shouldBe 2
        alerts.crossed(status(1200, day = "2026-10-22"), allowance).size shouldBe 2
    }

    @Test
    fun anEditedAllowanceReArms() {
        val alerts = AllowanceAlerts()
        alerts.crossed(status(1200), allowance).size shouldBe 2
        val edited = allowance.copy(bytes = 1100)
        alerts.crossed(status(1200, limit = 1100), edited).size shouldBe 2
    }

    @Test
    fun aNetworkRoundTripInOnePeriodDoesNotTellAgain() {
        val alerts = AllowanceAlerts()
        alerts.crossed(status(1200), allowance).size shouldBe 2
        alerts.crossed(status(10, network = "eth"), allowance) shouldBe emptyList()
        alerts.crossed(status(1200), allowance) shouldBe emptyList()
    }

    @Test
    fun noAllowanceTellsNothing() {
        val alerts = AllowanceAlerts()
        alerts.crossed(null, null) shouldBe emptyList()
        alerts.prime(null, null)
    }

    @Test
    fun reachedCarriesTheActionAndReset() {
        val slow = allowance.copy(atLimit = AllowanceAction.SLOW)
        val events = AllowanceAlerts().crossed(status(1000), slow)
        events.last() shouldBe EngineEvent.AllowanceReached("wifi", 1000, 1000, AllowanceAction.SLOW, resets)
    }
}
