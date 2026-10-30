package dev.anydm.desktop

import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RulesState(
    val loaded: Boolean = false,
    val rules: NetworkRules = NetworkRules(),
    val schedule: List<ScheduleRule> = emptyList(),
    /** The network whose policy shows; null for the default, which networks without their own use. */
    val target: String? = null,
    val policy: PolicyForm = PolicyForm(),
    val savedPolicy: PolicyForm = PolicyForm(),
    val policyErrors: Map<RulesField, String> = emptyMap(),
    /** The schedule rule being added or changed. */
    val ruleForm: ScheduleForm? = null,
    val ruleErrors: Map<RulesField, String> = emptyMap(),
    val message: String? = null,
    val busy: Boolean = false,
) {
    val errors: Map<RulesField, String> get() = policyErrors + ruleErrors

    /** The selected network has a policy of its own. */
    val hasOverride: Boolean get() = target != null && target in rules.networks

    val canSavePolicy: Boolean get() = loaded && policy != savedPolicy && policyErrors.isEmpty() && !busy

    val canSaveRule: Boolean get() = ruleForm != null && ruleErrors.isEmpty() && !busy
}

/**
 * The network rules section (#609): limits and data allowances for the default and for single networks, and the weekly
 * schedule. The engine applies each save to running downloads at once.
 */
class RulesModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
) {
    private val ui = MutableStateFlow(RulesState())
    val state: StateFlow<RulesState> = ui.asStateFlow()

    /** The networks the machine has, for the target list. */
    val networks get() = source.networks

    fun load() =
        scope.launch {
            guarded {
                val rules = source.networkRules()
                val schedule = source.scheduleRules()
                ui.update {
                    // An edit of the policy that is open is kept, unless the engine's policy under it changed.
                    val form = policyOf(rules, it.target)
                    val keep = it.loaded && it.policy != it.savedPolicy
                    it.copy(
                        loaded = true,
                        rules = rules,
                        schedule = schedule,
                        savedPolicy = form,
                        policy = if (keep) it.policy else form,
                        policyErrors = if (keep) it.policyErrors else emptyMap(),
                    )
                }
            }
        }

    private fun policyOf(
        rules: NetworkRules,
        target: String?,
    ): PolicyForm = policyForm(if (target == null) rules.default else rules.policyFor(target))

    fun select(key: String?) =
        ui.update {
            val form = policyOf(it.rules, key)
            it.copy(target = key, policy = form, savedPolicy = form, policyErrors = emptyMap(), message = null)
        }

    fun editPolicy(change: (PolicyForm) -> PolicyForm) =
        ui.update {
            val form = change(it.policy)
            it.copy(policy = form, policyErrors = form.validate(), message = null)
        }

    fun savePolicy() {
        var saving: PolicyForm? = null
        var target: String? = null
        ui.update {
            // The lambda may run again; only its last run counts.
            saving = null
            if (!it.canSavePolicy) return@update it
            saving = it.policy
            target = it.target
            it.copy(busy = true)
        }
        val form = saving ?: return
        scope.launch {
            try {
                guarded {
                    when (val result = source.setNetworkPolicy(target, form.toPolicy())) {
                        is CommandResult.Done -> {
                            reloadRules()
                            // The user may have moved to another network while this was saving.
                            ui.update { if (it.target == target) it.copy(savedPolicy = form, message = "Saved.") else it }
                        }

                        is CommandResult.Invalid -> {
                            ui.update { it.copy(message = result.reason) }
                        }

                        else -> {
                            ui.update { it.copy(message = result.toString()) }
                        }
                    }
                }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    /** The selected network goes back to the default policy. */
    fun removeOverride() {
        val target = ui.value.target ?: return
        scope.launch {
            guarded {
                when (val result = source.setNetworkPolicy(target, null)) {
                    is CommandResult.Done -> {
                        reloadRules()
                        if (ui.value.target == target) {
                            select(target)
                            ui.update { it.copy(message = "$target uses the default again.") }
                        }
                    }

                    is CommandResult.Invalid -> {
                        ui.update { it.copy(message = result.reason) }
                    }

                    else -> {
                        ui.update { it.copy(message = result.toString()) }
                    }
                }
            }
        }
    }

    private suspend fun reloadRules() {
        val rules = source.networkRules()
        ui.update { it.copy(rules = rules) }
    }

    // --- schedule

    fun addRule() = openRule(ScheduleForm())

    fun editRule(rule: ScheduleRule) = openRule(scheduleForm(rule))

    private fun openRule(form: ScheduleForm) = ui.update { it.copy(ruleForm = form, ruleErrors = form.validate(), message = null) }

    fun editRule(change: (ScheduleForm) -> ScheduleForm) =
        ui.update {
            val form = change(it.ruleForm ?: return@update it)
            it.copy(ruleForm = form, ruleErrors = form.validate(), message = null)
        }

    fun cancelRule() = ui.update { it.copy(ruleForm = null, ruleErrors = emptyMap()) }

    fun saveRule() {
        var saving: ScheduleForm? = null
        ui.update {
            saving = null
            if (!it.canSaveRule) return@update it
            saving = it.ruleForm
            it.copy(busy = true)
        }
        val form = saving ?: return
        scope.launch {
            try {
                guarded {
                    val id = form.id
                    val found =
                        if (id == null) {
                            source.addScheduleRule(form.toNew())
                            true
                        } else {
                            source.updateScheduleRule(form.toRule(id))
                        }
                    reloadSchedule()
                    ui.update {
                        it.copy(
                            ruleForm = null,
                            ruleErrors = emptyMap(),
                            message = if (found) "Saved." else "That rule was removed in the meantime.",
                        )
                    }
                }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    fun removeRule(id: ScheduleRuleId) {
        scope.launch {
            guarded {
                source.removeScheduleRule(id)
                reloadSchedule()
                ui.update { it.copy(message = null) }
            }
        }
    }

    private suspend fun reloadSchedule() {
        val schedule = source.scheduleRules()
        ui.update { it.copy(schedule = schedule) }
    }

    // A stopped engine takes no command: the screen says so instead.
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            ui.update { it.copy(message = "The download engine has stopped, so nothing was changed.") }
        } catch (e: IllegalArgumentException) {
            ui.update { it.copy(message = e.message ?: "The engine refused that rule.") }
        }
    }
}
