package dev.anydm.engine.store

import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.platform.virtualClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test

/** What every [RuleStore] must do (NET-05, NET-07, NET-08), each test on a new database from [newDriver]. */
abstract class RuleStoreContract : StoreTestBase() {
    @Test
    fun nothingStoredIsNoRules() =
        runTest {
            val rules = newStore().rules

            rules.networkRules() shouldBe NetworkRules()
            rules.scheduleRules() shouldBe emptyList()
        }

    @Test
    fun theDefaultPolicyRoundTrips() =
        runTest {
            val rules = newStore().rules
            val policy = NetworkPolicy(1000, 2000, Allowance(5_000_000, AllowancePeriod.Daily, 80, AllowanceAction.PAUSE))

            rules.setNetworkPolicy(null, policy)

            rules.networkRules() shouldBe NetworkRules(default = policy)
        }

    @Test
    fun everyPeriodKindRoundTrips() =
        runTest {
            val rules = newStore().rules
            val networks =
                mapOf(
                    "a" to NetworkPolicy(allowance = Allowance(1, AllowancePeriod.Daily, atLimit = AllowanceAction.SLOW)),
                    "b" to
                        NetworkPolicy(
                            allowance = Allowance(2, AllowancePeriod.Weekly(DayOfWeek.SUNDAY), atLimit = AllowanceAction.SLOW),
                        ),
                    "c" to NetworkPolicy(allowance = Allowance(3, AllowancePeriod.Monthly(31), 99, AllowanceAction.SLOW)),
                )
            networks.forEach { (key, policy) -> rules.setNetworkPolicy(key, policy) }

            rules.networkRules() shouldBe NetworkRules(networks = networks)
        }

    @Test
    fun nullFieldsRoundTrip() =
        runTest {
            val rules = newStore().rules
            for (policy in listOf(
                NetworkPolicy(speedLimit = 5),
                NetworkPolicy(uploadLimit = 6),
                NetworkPolicy(allowance = Allowance(0, AllowancePeriod.Daily)),
            )) {
                rules.setNetworkPolicy("wifi", policy)
                rules.networkRules().networks shouldBe mapOf("wifi" to policy)
            }
        }

    @Test
    fun aNullPolicyRemovesTheEntry() =
        runTest {
            val rules = newStore().rules
            rules.setNetworkPolicy("wifi", NetworkPolicy(speedLimit = 5))
            rules.setNetworkPolicy("eth", NetworkPolicy(speedLimit = 7))

            rules.setNetworkPolicy("wifi", null)

            rules.networkRules().networks shouldBe mapOf("eth" to NetworkPolicy(speedLimit = 7))
        }

    @Test
    fun theDefaultBackToEmptyHasNoRow() =
        runTest {
            val rules = newStore().rules
            rules.setNetworkPolicy(null, NetworkPolicy(speedLimit = 5))
            driver.count("network_policy") shouldBe 1L

            rules.setNetworkPolicy(null, NetworkPolicy())
            driver.count("network_policy") shouldBe 0L

            rules.setNetworkPolicy(null, NetworkPolicy(speedLimit = 5))
            rules.setNetworkPolicy(null, null)
            driver.count("network_policy") shouldBe 0L
            rules.networkRules() shouldBe NetworkRules()
        }

    @Test
    fun anEmptyEntryForANetworkIsKept() =
        runTest {
            val rules = newStore().rules
            rules.setNetworkPolicy(null, NetworkPolicy(speedLimit = 5))

            rules.setNetworkPolicy("wifi", NetworkPolicy())

            rules.networkRules() shouldBe NetworkRules(NetworkPolicy(speedLimit = 5), mapOf("wifi" to NetworkPolicy()))
            rules.networkRules().policyFor("wifi") shouldBe NetworkPolicy()
        }

    @Test
    fun aBlankOrStarKeyIsRefused() =
        runTest {
            val rules = newStore().rules
            for (key in listOf("", " ", "*")) {
                shouldThrow<IllegalArgumentException> { rules.setNetworkPolicy(key, NetworkPolicy()) }
            }
        }

    @Test
    fun scheduleRulesAddListUpdateRemove() =
        runTest {
            val rules = newStore().rules
            val a = rules.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.MONDAY), 540, 1020, RuleAction.PAUSE))
            val b = rules.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.FRIDAY), 1320, 360, RuleAction.SLOW))
            (b.id.value > a.id.value) shouldBe true
            a.action shouldBe RuleAction.PAUSE
            rules.scheduleRules() shouldBe listOf(a, b)

            val changed = a.copy(startMinute = 0, endMinute = 0, action = RuleAction.SLOW)
            rules.updateScheduleRule(changed) shouldBe true
            rules.scheduleRules() shouldBe listOf(changed, b)

            rules.removeScheduleRule(a.id) shouldBe true
            rules.updateScheduleRule(changed) shouldBe false
            rules.removeScheduleRule(a.id) shouldBe false
            rules.scheduleRules() shouldBe listOf(b)
        }

    @Test
    fun daysRoundTripAsASet() =
        runTest {
            val rules = newStore().rules
            val days = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.THURSDAY)

            val added = rules.addScheduleRule(NewScheduleRule(days, 0, 1439, RuleAction.PAUSE))

            added.days shouldBe days
            rules.scheduleRules().single().days shouldBe days
            driver.texts("SELECT days FROM schedule_rule") shouldBe listOf("MONDAY,THURSDAY,SUNDAY")
        }

    @Test
    fun anUnreadableRuleIsSkippedAndLogged() =
        runTest {
            val sink = RecordingLogSink()
            val raw = newDriver()
            val store = Store(raw, StandardTestDispatcher(testScheduler), Log(sink, virtualClock()))
            raw.exec("INSERT INTO schedule_rule(days, start_minute, end_minute, action) VALUES ('MONDAY', 0, 10, 'NAP')")
            raw.exec("INSERT INTO schedule_rule(days, start_minute, end_minute, action) VALUES ('', 0, 10, 'PAUSE')")
            raw.exec("INSERT INTO schedule_rule(days, start_minute, end_minute, action) VALUES ('FUNDAY', 0, 10, 'PAUSE')")
            raw.exec("INSERT INTO schedule_rule(days, start_minute, end_minute, action) VALUES ('MONDAY', 5, 10, 'PAUSE')")

            val rules = store.rules.scheduleRules()

            rules.map { it.id } shouldBe listOf(ScheduleRuleId(4))
            sink.lines.size shouldBe 3
            sink.lines[0] shouldContain "WARN  store schedule rule 1 unreadable, skipped"
            sink.lines.last() shouldContain "schedule rule 3 unreadable, skipped"
            raw.close()
        }

    @Test
    fun anUnreadablePolicyIsSkipped() =
        runTest {
            val sink = RecordingLogSink()
            val raw = newDriver()
            val store = Store(raw, StandardTestDispatcher(testScheduler), Log(sink, virtualClock()))
            raw.exec(
                "INSERT INTO network_policy(network, speed_limit, allowance_bytes, allowance_period, allowance_action) VALUES ('wifi', 5, 10, 'HOURLY', 'PAUSE')",
            )
            raw.exec("INSERT INTO network_policy(network, speed_limit) VALUES ('eth', 7)")

            store.rules.networkRules() shouldBe NetworkRules(networks = mapOf("eth" to NetworkPolicy(speedLimit = 7)))

            sink.lines.single() shouldContain "WARN  store network policy wifi unreadable, skipped"
            raw.close()
        }

    @Test
    fun anUnreadableDefaultPolicyIsTheEmptyOne() =
        runTest {
            val raw = newDriver()
            val store = Store(raw, StandardTestDispatcher(testScheduler), Log(RecordingLogSink(), virtualClock()))
            raw.exec(
                "INSERT INTO network_policy(network, allowance_bytes, allowance_period, allowance_action) VALUES ('*', 10, 'WEEKLY', 'PAUSE')",
            )

            store.rules.networkRules() shouldBe NetworkRules()
            raw.close()
        }
}
