package dev.anydm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.anydm.engine.net.AllowanceAction
import kotlin.math.roundToInt

/** The stops of the cap slider in KB/s; one more stop after them is "unlimited". */
internal val CAP_STEPS_KBPS = listOf(128L, 256L, 512L, 1024L, 2048L, 5120L, 10240L, 25600L, 51200L)

// The stop nearest to [kbps]; the last one (unlimited) for an empty or unreadable cap.
private fun stopOf(kbps: String): Int {
    val value = kbps.trim().toLongOrNull() ?: return CAP_STEPS_KBPS.size
    return CAP_STEPS_KBPS.indices.minByOrNull { kotlin.math.abs(CAP_STEPS_KBPS[it] - value) } ?: CAP_STEPS_KBPS.size
}

private fun capLabel(
    form: LimitsForm,
    errors: Map<RulesField, String>,
): String {
    val kbps = form.capKbps.trim().toLongOrNull()
    return if (kbps == null || RulesField.DOWN in errors) "Unlimited" else formatSpeed(kbps * KB)
}

/** The limits of one network: its speed cap and daily data budget. Saving changes only those; closing changes nothing. */
@Composable
internal fun NetworkLimitsPopover(
    row: StripRow,
    message: String?,
    model: NetworksStripModel,
    onTyping: (Boolean) -> Unit,
) {
    var form by remember(row.key) { mutableStateOf(model.limitsFormFor(row.key)) }
    val errors = form.validate()
    val typing = Modifier.onFocusChanged { onTyping(it.isFocused) }
    val capText = capLabel(form, errors)
    AlertDialog(
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = model::closeLimits,
        title = { PanelTitle("Limits · ${row.name}") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Speed cap", style = MaterialTheme.typography.labelLarge)
                    Text(capText, modifier = Modifier.testTag("limits-cap-label"))
                }
                Slider(
                    value = stopOf(form.capKbps).toFloat(),
                    onValueChange = { value ->
                        val stop = value.roundToInt()
                        form = form.copy(capKbps = CAP_STEPS_KBPS.getOrNull(stop)?.toString().orEmpty())
                    },
                    valueRange = 0f..CAP_STEPS_KBPS.size.toFloat(),
                    steps = CAP_STEPS_KBPS.size - 1,
                    modifier = Modifier.semantics { stateDescription = capText }.testTag("limits-cap-slider"),
                )
                Field(
                    "Speed cap (empty: unlimited)",
                    form.capKbps,
                    "limits-cap",
                    errors[RulesField.DOWN],
                    typing,
                    unit = "KB/s",
                    width = 280,
                ) { v ->
                    form = form.copy(capKbps = v)
                }
                val other = form.other
                if (other != null) {
                    Text(
                        "${allowanceSummary(other)}, edit in Rules",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("limits-budget-readonly"),
                    )
                } else {
                    Field(
                        "Daily budget (empty: none)",
                        form.budgetMb,
                        "limits-budget",
                        errors[RulesField.ALLOWANCE],
                        typing,
                        unit = "MB",
                        width = 280,
                    ) { v ->
                        form = form.copy(budgetMb = v)
                    }
                    if (form.budgetMb.isNotBlank()) {
                        Field(
                            "Warn me at (empty: never)",
                            form.warnPercent,
                            "limits-warn",
                            errors[RulesField.WARN],
                            typing,
                            unit = "%",
                            width = 280,
                        ) { v ->
                            form = form.copy(warnPercent = v)
                        }
                        Choice("At the limit", AllowanceAction.entries, form.atLimit, "limits-atlimit", {
                            if (it == AllowanceAction.PAUSE) "Pause" else "Slow down"
                        }) { a -> form = form.copy(atLimit = a) }
                    }
                }
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("limits-message"))
                }
            }
        },
        confirmButton = {
            Button(onClick = { model.saveLimits(row.key, form) }, enabled = errors.isEmpty(), modifier = Modifier.testTag("limits-save")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = model::closeLimits, modifier = Modifier.testTag("limits-cancel")) { Text("Cancel") } },
        modifier = Modifier.testTag("limits-dialog"),
    )
}
