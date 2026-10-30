package dev.anydm.engine.net

import dev.anydm.engine.core.ScheduleRuleId
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Instant

/** The decision of the rules (NET-05..08): strictest wins, caps are the minimum per direction. 2026-10-21 is a Wednesday. */
class RuleDecisionTest {
    private val now = Instant.parse("2026-10-21T09:30:00Z")
    private val slowLimit = 1_048_576L
    private val wednesday = setOf(DayOfWeek.WEDNESDAY)

    private fun schedule(
        action: RuleAction,
        start: Int = 540,
        end: Int = 600,
    ) = ScheduleRule(ScheduleRuleId(1), wednesday, start, end, action)

    private fun inputs(
        networks: NetworkRules = NetworkRules(),
        schedule: List<ScheduleRule> = emptyList(),
        slowOn: Boolean = false,
        used: Long = 0,
        network: String = "unknown",
        at: Instant = now,
        detectedMetered: Boolean = false,
    ) = RuleInputs(at, TimeZone.UTC, network, networks, schedule, SlowMode(slowOn, slowLimit), used, detectedMetered)

    private fun allowance(
        bytes: Long = 1000,
        atLimit: AllowanceAction = AllowanceAction.PAUSE,
        warnPercent: Int? = null,
        period: AllowancePeriod = AllowancePeriod.Daily,
    ) = NetworkRules(NetworkPolicy(allowance = Allowance(bytes, period, warnPercent, atLimit)))

    @Test
    fun scheduleSlowAndAllowancePauseGivePauseBecauseOfTheAllowance() {
        val d = decide(inputs(allowance(), listOf(schedule(RuleAction.SLOW)), used = 1000))
        d.status.action shouldBe RuleAction.PAUSE
        d.status.because shouldBe setOf(RuleSource.ALLOWANCE)
        d.effect.pause shouldBe true
    }

    @Test
    fun scheduleSlowAndSlowModeGiveSlowBecauseOfBoth() {
        val d = decide(inputs(schedule = listOf(schedule(RuleAction.SLOW)), slowOn = true))
        d.status.action shouldBe RuleAction.SLOW
        d.status.because shouldBe setOf(RuleSource.SCHEDULE, RuleSource.SLOW_MODE)
        d.effect.pause shouldBe false
    }

    @Test
    fun aNetworkLimitUnderTheSlowLimitIsTheCap() {
        val d = decide(inputs(NetworkRules(NetworkPolicy(speedLimit = 512_000)), listOf(schedule(RuleAction.SLOW))))
        d.effect.downloadCap shouldBe 512_000L
        d.status.downloadCap shouldBe 512_000L
    }

    @Test
    fun allowWithANetworkLimitCapsAtIt() {
        val d = decide(inputs(NetworkRules(NetworkPolicy(speedLimit = 512_000))))
        d.status.action shouldBe RuleAction.ALLOW
        d.status.because shouldBe emptySet()
        d.effect.downloadCap shouldBe 512_000L
        d.effect.pause shouldBe false
    }

    @Test
    fun anEntryReplacesTheWholeDefault() {
        val rules =
            NetworkRules(
                NetworkPolicy(speedLimit = 1000, allowance = Allowance(10, AllowancePeriod.Daily)),
                mapOf("wifi" to NetworkPolicy(uploadLimit = 2000)),
            )
        val d = decide(inputs(rules, network = "wifi", used = 50))
        d.status.allowance shouldBe null
        d.effect.downloadCap shouldBe null
        d.effect.uploadCap shouldBe 2000L
        d.status.action shouldBe RuleAction.ALLOW
    }

    @Test
    fun unknownFallsBackToTheDefault() {
        val rules = NetworkRules(NetworkPolicy(speedLimit = 1000), mapOf("wifi" to NetworkPolicy(speedLimit = 5)))
        decide(inputs(rules, network = "unknown")).effect.downloadCap shouldBe 1000L
        decide(inputs(rules, network = "wifi")).effect.downloadCap shouldBe 5L
    }

    @Test
    fun anAllowanceOfZeroIsOverAtOnce() {
        val d = decide(inputs(allowance(bytes = 0), used = 0))
        d.status.action shouldBe RuleAction.PAUSE
        d.status.because shouldBe setOf(RuleSource.ALLOWANCE)
        d.status.allowance!!.over shouldBe true
    }

    @Test
    fun usedEqualToTheLimitIsOverAndOneLessIsNot() {
        decide(inputs(allowance(1000), used = 1000)).status.allowance!!.over shouldBe true
        val under = decide(inputs(allowance(1000), used = 999))
        under.status.allowance!!.over shouldBe false
        under.status.action shouldBe RuleAction.ALLOW
    }

    @Test
    fun atLimitSlowSlowsWithoutPausing() {
        val d = decide(inputs(allowance(1000, AllowanceAction.SLOW), used = 1000))
        d.status.action shouldBe RuleAction.SLOW
        d.effect.pause shouldBe false
        d.effect.downloadCap shouldBe slowLimit
        d.effect.uploadCap shouldBe slowLimit
    }

    @Test
    fun capsAreTheMinimumPerDirection() {
        val rules = NetworkRules(NetworkPolicy(speedLimit = 2_097_152, uploadLimit = 100_000))
        val d = decide(inputs(rules, listOf(schedule(RuleAction.SLOW))))
        d.effect.downloadCap shouldBe 1_048_576L
        d.effect.uploadCap shouldBe 100_000L
    }

    @Test
    fun aPauseStillCapsAtTheSlowLimitForSeeds() {
        val d = decide(inputs(schedule = listOf(schedule(RuleAction.PAUSE))))
        d.effect.pause shouldBe true
        d.effect.uploadCap shouldBe 1_048_576L
    }

    @Test
    fun nextChangeIsTheEarlierOfBoundaryAndReset() {
        decide(inputs(allowance(), listOf(schedule(RuleAction.PAUSE, 540, 600)))).status.nextChange shouldBe
            Instant.parse("2026-10-21T10:00:00Z")
        decide(inputs(allowance())).status.nextChange shouldBe Instant.parse("2026-10-22T00:00:00Z")
        decide(inputs()).status.nextChange shouldBe null
    }

    @Test
    fun warnAtIsIntegerPercent() {
        decide(inputs(allowance(1000, warnPercent = 33))).status.allowance!!.warnAt shouldBe 330L
        decide(inputs(allowance(102_400, warnPercent = 50))).status.allowance!!.warnAt shouldBe 51_200L
        decide(inputs(allowance(1000))).status.allowance!!.warnAt shouldBe null
    }

    @Test
    fun theStatusCarriesNetworkPeriodUsedAndReset() {
        val d = decide(inputs(allowance(1000, period = AllowancePeriod.Monthly(1)), used = 400, network = "eth"))
        d.status.network shouldBe "eth"
        val a = d.status.allowance!!
        a.network shouldBe "eth"
        a.limit shouldBe 1000L
        a.used shouldBe 400L
        a.from shouldBe LocalDate.parse("2026-10-01")
        a.to shouldBe LocalDate.parse("2026-10-31")
        a.resetsAt shouldBe Instant.parse("2026-11-01T00:00:00Z")
        d.status.slowMode shouldBe SlowMode(false, slowLimit)
    }

    @Test
    fun noRulesAtAllAllowWithNoCaps() {
        val d = decide(inputs())
        d.effect shouldBe RuleEffect.None
        d.status.action shouldBe RuleAction.ALLOW
        d.status.allowance shouldBe null
        allowancePeriod(NetworkRules(), "unknown", now, TimeZone.UTC) shouldBe null
    }

    private fun meteredRules(
        onMetered: MeteredAction,
        metered: Boolean = false,
    ) = NetworkRules(NetworkPolicy(metered = metered, onMetered = onMetered))

    @Test
    fun meteredByTheUsersFlag() {
        val d = decide(inputs(meteredRules(MeteredAction.PAUSE, metered = true)))
        d.status.action shouldBe RuleAction.PAUSE
        d.status.because shouldBe setOf(RuleSource.METERED)
        d.status.metered shouldBe true
        d.effect.pause shouldBe true
    }

    @Test
    fun meteredByDetection() {
        val d = decide(inputs(meteredRules(MeteredAction.PAUSE), detectedMetered = true))
        d.status.action shouldBe RuleAction.PAUSE
        d.status.because shouldBe setOf(RuleSource.METERED)
        d.status.metered shouldBe true
    }

    @Test
    fun meteredByBoth() {
        val d = decide(inputs(meteredRules(MeteredAction.PAUSE, metered = true), detectedMetered = true))
        d.status.action shouldBe RuleAction.PAUSE
        d.status.because shouldBe setOf(RuleSource.METERED)
        d.status.metered shouldBe true
    }

    @Test
    fun onMeteredNoneSlowAndPause() {
        val none = decide(inputs(meteredRules(MeteredAction.NONE), detectedMetered = true))
        none.status.action shouldBe RuleAction.ALLOW
        none.status.because shouldBe emptySet()
        none.status.metered shouldBe true

        val slow = decide(inputs(meteredRules(MeteredAction.SLOW), detectedMetered = true))
        slow.status.action shouldBe RuleAction.SLOW
        slow.status.because shouldBe setOf(RuleSource.METERED)
        slow.status.downloadCap shouldBe 1_048_576L
        slow.status.uploadCap shouldBe 1_048_576L

        val pause = decide(inputs(meteredRules(MeteredAction.PAUSE), detectedMetered = true))
        pause.status.action shouldBe RuleAction.PAUSE
        pause.status.because shouldBe setOf(RuleSource.METERED)
    }

    @Test
    fun notMeteredIgnoresOnMetered() {
        val d = decide(inputs(meteredRules(MeteredAction.PAUSE)))
        d.status.action shouldBe RuleAction.ALLOW
        d.status.metered shouldBe false
    }

    @Test
    fun aMeteredPauseAndAnAllowancePauseNameBoth() {
        val rules =
            NetworkRules(
                NetworkPolicy(
                    allowance = Allowance(1000, AllowancePeriod.Daily),
                    metered = true,
                    onMetered = MeteredAction.PAUSE,
                ),
            )
        val d = decide(inputs(rules, used = 1000))
        d.status.action shouldBe RuleAction.PAUSE
        d.status.because shouldBe setOf(RuleSource.ALLOWANCE, RuleSource.METERED)
    }

    @Test
    fun anEntryWithoutMeteredReplacesAMeteredDefault() {
        val rules =
            NetworkRules(
                default = NetworkPolicy(metered = true, onMetered = MeteredAction.PAUSE),
                networks = mapOf("wifi" to NetworkPolicy()),
            )
        val d = decide(inputs(rules, network = "wifi"))
        d.status.action shouldBe RuleAction.ALLOW
        d.status.metered shouldBe false
    }

    @Test
    fun noneIsNotMetered() {
        RuleStatus.None.metered shouldBe false
    }
}
