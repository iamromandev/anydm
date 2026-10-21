package dev.anydm.engine.store

import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.log.Log
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import dev.anydm.engine.store.db.Network_policy as PolicyRow
import dev.anydm.engine.store.db.Schedule_rule as RuleRow

internal class SqlRuleStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
    private val log: Log = Log.None,
) : RuleStore {
    private val queries = database.networkRuleQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    // A row this version can't read is skipped with a warning: a bad rule must not stop the engine from starting.
    override suspend fun networkRules(): NetworkRules =
        withContext(io) {
            var default = NetworkPolicy()
            val networks = mutableMapOf<String, NetworkPolicy>()
            for (row in queries.selectPolicies().executeAsList()) {
                val policy = row.toPolicy()
                if (policy == null || (row.network != DEFAULT_KEY && row.network.isBlank())) {
                    log.warn("store") { "network policy ${row.network} unreadable, skipped" }
                } else if (row.network == DEFAULT_KEY) {
                    default = policy
                } else {
                    networks[row.network] = policy
                }
            }
            NetworkRules(default, networks)
        }

    override suspend fun setNetworkPolicy(
        network: String?,
        policy: NetworkPolicy?,
    ) {
        require(network == null || (network.isNotBlank() && network != DEFAULT_KEY)) { "network key must not be blank or *: $network" }
        withContext(writeContext) {
            val key = network ?: DEFAULT_KEY
            if (policy == null || (network == null && policy == NetworkPolicy())) {
                queries.deletePolicy(key).value
            } else {
                val allowance = policy.allowance
                queries
                    .upsertPolicy(
                        network = key,
                        speed_limit = policy.speedLimit,
                        upload_limit = policy.uploadLimit,
                        allowance_bytes = allowance?.bytes,
                        allowance_period = allowance?.period?.let(::periodName),
                        allowance_reset_day = allowance?.period?.let(::resetDay),
                        allowance_warn_percent = allowance?.warnPercent?.toLong(),
                        allowance_action = allowance?.atLimit?.name,
                    ).value
            }
        }
    }

    override suspend fun scheduleRules(): List<ScheduleRule> =
        withContext(io) {
            queries.selectRules().executeAsList().mapNotNull { row ->
                row.toRule().also { if (it == null) log.warn("store") { "schedule rule ${row.id.value} unreadable, skipped" } }
            }
        }

    override suspend fun addScheduleRule(new: NewScheduleRule): ScheduleRule =
        withContext(writeContext) {
            database.transactionWithResult {
                queries.insertRule(encodeDays(new.days), new.startMinute, new.endMinute, new.action.name)
                queries.selectLastRule().executeAsOne().toRule() ?: error("a rule just stored can't be read")
            }
        }

    override suspend fun updateScheduleRule(rule: ScheduleRule): Boolean =
        withContext(writeContext) {
            queries.updateRule(encodeDays(rule.days), rule.startMinute, rule.endMinute, rule.action.name, rule.id).value > 0
        }

    override suspend fun removeScheduleRule(id: ScheduleRuleId): Boolean = withContext(writeContext) { queries.deleteRule(id).value > 0 }

    private fun periodName(period: AllowancePeriod): String =
        when (period) {
            AllowancePeriod.Daily -> "DAILY"
            is AllowancePeriod.Weekly -> "WEEKLY"
            is AllowancePeriod.Monthly -> "MONTHLY"
        }

    private fun resetDay(period: AllowancePeriod): Long? =
        when (period) {
            AllowancePeriod.Daily -> null
            is AllowancePeriod.Weekly -> period.resetDay.isoDayNumber.toLong()
            is AllowancePeriod.Monthly -> period.resetDay.toLong()
        }

    private fun encodeDays(days: Set<DayOfWeek>): String = days.sortedBy { it.isoDayNumber }.joinToString(",") { it.name }

    private fun PolicyRow.toPolicy(): NetworkPolicy? =
        try {
            val allowance =
                allowance_bytes?.let { bytes ->
                    val period =
                        when (allowance_period) {
                            "DAILY" -> AllowancePeriod.Daily
                            "WEEKLY" -> AllowancePeriod.Weekly(DayOfWeek(allowance_reset_day!!.toInt()))
                            "MONTHLY" -> AllowancePeriod.Monthly(allowance_reset_day!!.toInt())
                            else -> return null
                        }
                    val action = AllowanceAction.entries.firstOrNull { it.name == allowance_action } ?: return null
                    Allowance(bytes, period, allowance_warn_percent?.toInt(), action)
                }
            NetworkPolicy(speed_limit, upload_limit, allowance)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NullPointerException) {
            null
        }

    private fun RuleRow.toRule(): ScheduleRule? =
        try {
            val dayList = days.split(',').map { name -> DayOfWeek.entries.first { it.name == name } }
            val action = RuleAction.entries.first { it.name == action }
            ScheduleRule(id, dayList.toSet(), start_minute, end_minute, action)
        } catch (_: NoSuchElementException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val DEFAULT_KEY = "*"
    }
}
