package dev.anydm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.MeteredAction
import dev.anydm.engine.net.RuleAction
import kotlinx.datetime.DayOfWeek

/** The network rules section (#609): limits and allowances per network, and the weekly schedule. */
@Composable
internal fun RulesPanel(
    model: RulesModel,
    typing: Modifier,
) {
    val state by model.state.collectAsState()
    val bonding by model.networks.collectAsState()
    if (!state.loaded) {
        Text(state.message ?: "Reading the rules…", modifier = Modifier.testTag("rules-unavailable"))
        return
    }
    Text("Limits and data allowances. A network with its own policy uses only that; every other network uses the default.")
    val keys = (bonding.networks.map { it.info.key } + state.rules.networks.keys).distinct()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.target == null,
            onClick = { model.select(null) },
            label = { Text("Default") },
            modifier = Modifier.testTag("rules-target-default"),
        )
        keys.forEach { key ->
            FilterChip(
                selected = state.target == key,
                onClick = { model.select(key) },
                label = {
                    Text(
                        bonding.networks
                            .firstOrNull { it.info.key == key }
                            ?.info
                            ?.name ?: key,
                    )
                },
                modifier = Modifier.testTag("rules-target-$key"),
            )
        }
    }
    if (state.target != null) {
        Text(
            if (state.hasOverride) {
                "${state.target} has its own policy."
            } else {
                "${state.target} uses the default. Change something and save to give it its own."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("rules-override-note"),
        )
    }
    PolicyEditor(state, model, typing)
    HorizontalDivider()
    SchedulePanel(state, model, typing)
    state.message?.let { Text(it, modifier = Modifier.testTag("rules-message")) }
}

@Composable
private fun PolicyEditor(
    state: RulesState,
    model: RulesModel,
    typing: Modifier,
) {
    val form = state.policy
    val errors = state.policyErrors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(
            "Download limit (empty: none)",
            form.downKbps,
            "rules-down",
            errors[RulesField.DOWN],
            typing,
            unit = "KB/s",
            width = 240,
        ) { v ->
            model.editPolicy { it.copy(downKbps = v) }
        }
        Field("Upload limit (empty: none)", form.upKbps, "rules-up", errors[RulesField.UP], typing, unit = "KB/s", width = 240) { v ->
            model.editPolicy { it.copy(upKbps = v) }
        }
    }
    Toggle("Data allowance (download plus upload)", form.allowanceOn, "rules-allowance") { on ->
        model.editPolicy {
            it.copy(allowanceOn = on)
        }
    }
    if (form.allowanceOn) {
        Field("Allowance", form.allowanceMb, "rules-allowance-mb", errors[RulesField.ALLOWANCE], typing, unit = "MB", width = 240) { v ->
            model.editPolicy { it.copy(allowanceMb = v) }
        }
        Choice(
            "It starts again",
            PeriodKind.entries,
            form.period,
            "rules-period",
            { it.label },
        ) { p -> model.editPolicy { it.copy(period = p) } }
        when (form.period) {
            PeriodKind.DAILY -> {
                Unit
            }

            PeriodKind.WEEKLY -> {
                Choice(
                    "On",
                    DayOfWeek.entries,
                    form.weekDay,
                    "rules-weekday",
                    { it.short },
                ) { d -> model.editPolicy { it.copy(weekDay = d) } }
            }

            PeriodKind.MONTHLY -> {
                Field("On day of the month", form.resetDay, "rules-reset-day", errors[RulesField.RESET_DAY], typing, width = 240) { v ->
                    model.editPolicy { it.copy(resetDay = v) }
                }
            }
        }
        Field("Warn me at (empty: never)", form.warnPercent, "rules-warn", errors[RulesField.WARN], typing, unit = "%", width = 240) { v ->
            model.editPolicy { it.copy(warnPercent = v) }
        }
        Choice("At the limit", AllowanceAction.entries, form.atLimit, "rules-atlimit", {
            if (it ==
                AllowanceAction.PAUSE
            ) {
                "Pause"
            } else {
                "Slow down"
            }
        }) { a ->
            model.editPolicy { it.copy(atLimit = a) }
        }
    }
    Toggle("Treat this network as metered", form.metered, "rules-metered") { on -> model.editPolicy { it.copy(metered = on) } }
    if (form.metered) {
        Choice(
            "On a metered network",
            MeteredAction.entries,
            form.onMetered,
            "rules-onmetered",
            {
                when (it) {
                    MeteredAction.NONE -> "Nothing"
                    MeteredAction.SLOW -> "Slow down"
                    MeteredAction.PAUSE -> "Pause"
                }
            },
        ) { a -> model.editPolicy { it.copy(onMetered = a) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.hasOverride) {
            OutlinedButton(
                onClick = model::removeOverride,
                modifier = Modifier.testTag("rules-remove-override"),
            ) { Text("Use the default") }
        }
        Button(
            onClick = model::savePolicy,
            enabled = state.canSavePolicy,
            modifier = Modifier.testTag("rules-save-policy"),
        ) { Text("Save") }
    }
}

@Composable
private fun SchedulePanel(
    state: RulesState,
    model: RulesModel,
    typing: Modifier,
) {
    Text("Schedule", style = MaterialTheme.typography.titleMedium)
    Text(
        "Rules by day and time, in your local time. When several apply, the strictest one wins.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.schedule.isEmpty()) Text("No rules yet.", modifier = Modifier.testTag("rule-empty"))
    state.schedule.forEach { rule ->
        val id = rule.id.value
        Row(Modifier.fillMaxWidth().testTag("rule-row-$id"), verticalAlignment = Alignment.CenterVertically) {
            Text(describe(rule), modifier = Modifier.weight(1f).testTag("rule-text-$id"))
            TextButton(onClick = { model.editRule(rule) }, modifier = Modifier.testTag("rule-edit-$id")) { Text("Edit") }
            TextButton(onClick = { model.removeRule(rule.id) }, modifier = Modifier.testTag("rule-remove-$id")) { Text("Remove") }
        }
    }
    val form = state.ruleForm
    if (form == null) {
        OutlinedButton(onClick = model::addRule, modifier = Modifier.testTag("rule-add")) { Text("Add a rule") }
        return
    }
    val errors = state.ruleErrors
    Text("Days", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day in form.days,
                onClick = { model.editRule { it.copy(days = if (day in it.days) it.days - day else it.days + day) } },
                label = { Text(day.short) },
                modifier = Modifier.testTag("rule-day-${day.name.lowercase()}"),
            )
        }
    }
    errors[RulesField.DAYS]?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("rule-days-error")) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(
            "From",
            form.start,
            "rule-start",
            errors[RulesField.START],
            typing,
            width = 160,
        ) { v -> model.editRule { it.copy(start = v) } }
        Field("Until", form.end, "rule-end", errors[RulesField.END], typing, width = 160) { v -> model.editRule { it.copy(end = v) } }
    }
    Choice("Then", RuleAction.entries, form.action, "rule-action", { it.label }) { a -> model.editRule { it.copy(action = a) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = model::cancelRule, modifier = Modifier.testTag("rule-cancel")) { Text("Cancel") }
        Button(onClick = model::saveRule, enabled = state.canSaveRule, modifier = Modifier.testTag("rule-save")) { Text("Save") }
    }
}
