package dev.anydm.engine.store

import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.ScheduleRule

/** The network policies (NET-05, NET-07) and schedule rules (NET-08) the user set. */
interface RuleStore {
    suspend fun networkRules(): NetworkRules

    /**
     * Sets the policy of [network], or the default for null. A null [policy] removes the entry (the default becomes
     * NetworkPolicy()). The default needs a row only when it isn't empty. A key must not be blank or `*`.
     */
    suspend fun setNetworkPolicy(
        network: String?,
        policy: NetworkPolicy?,
    )

    /** In id order. */
    suspend fun scheduleRules(): List<ScheduleRule>

    suspend fun addScheduleRule(new: NewScheduleRule): ScheduleRule

    /** False if the rule is gone. */
    suspend fun updateScheduleRule(rule: ScheduleRule): Boolean

    suspend fun removeScheduleRule(id: ScheduleRuleId): Boolean
}
