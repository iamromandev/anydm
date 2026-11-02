package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anydm.engine.net.AllowanceAction
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek

/** Which question the dialog is asking over its pages. */
private enum class LimitsAsk { RESET_GENERAL, RESET_USAGE, REMOVE_LIMITS, REPLACE_PAUSE_RULES }

/** How many of the other pause rules are named in a line before "and N more". */
private const val NAMED_RULES = 3

/** The pause rules set up elsewhere in a line: "Pause Mon–Fri 09:00–17:00; Pause Sat all day and 2 more". */
private fun namedRules(rules: List<String>): String {
    val shown = rules.take(NAMED_RULES).joinToString("; ")
    return if (rules.size > NAMED_RULES) "$shown and ${rules.size - NAMED_RULES} more" else shown
}

/** What a data limit does at the limit, as the dialog says it. */
private val AllowanceAction.verb: String get() = if (this == AllowanceAction.SLOW) "Slows" else "Stops"

/**
 * "Speed & data limits": All downloads and each network on a sidebar, a page for the one picked, and Cancel and Save. It edits
 * [model]'s draft; nothing reaches the engine until Save. It holds the window's shortcuts back while shown, so a number field
 * gets Backspace, select all and paste. [networks] are the rows of the networks menu (name, kind and color); [onClose] runs when
 * the model says it is done.
 */
@Composable
fun LimitsDialog(
    model: LimitsModel,
    networks: List<NetworkRow>,
    keys: WindowKeys,
    onClose: () -> Unit,
) {
    BlockWindowKeys(keys)
    val state by model.state.collectAsState()
    val colors = Plexo.colors
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<LimitsAsk?>(null) }
    val focus = remember { FocusRequester() }
    // Back on the dialog whenever no question is open, so Escape still finds it after a question closed.
    LaunchedEffect(ask) { if (ask == null) runCatching { focus.requestFocus() } }
    LaunchedEffect(state.done) { if (state.done) onClose() }
    val shown = networks.firstOrNull { it.key == state.page }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .focusRequester(focus)
            .focusTarget()
            .onPreviewKeyEvent {
                // Escape leaves everything as it was, unless a question is open: that one takes it.
                if (ask == null && it.type == KeyEventType.KeyDown && it.key == Key.Escape && !state.saving) {
                    model.cancel()
                    true
                } else {
                    false
                }
            }.pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 680.dp)
                .fillMaxHeight(0.92f)
                .heightIn(max = 520.dp)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .semantics { contentDescription = "Speed & data limits" },
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Txt("Speed & data limits", Plexo.type.title)
            }
            Rule()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Sidebar(state, model, networks)
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(colors.border))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    when {
                        state.loading -> {
                            Txt("Reading the limits…", Plexo.type.hint, color = colors.textSecondary)
                        }

                        state.failure != null -> {
                            Txt(state.failure.orEmpty(), Plexo.type.hint, color = colors.danger, maxLines = 6)
                        }

                        shown != null -> {
                            NetworkPage(state, model, shown, onAsk = { ask = it })
                        }

                        else -> {
                            GeneralPage(state, model, onAsk = { ask = it })
                        }
                    }
                }
            }
            val problem = state.message ?: state.errors.firstOrNull()?.let { errorLine(it, networks) }
            if (problem != null) {
                Txt(
                    problem,
                    Plexo.type.hint,
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { contentDescription = "Limits problem" },
                    color = colors.danger,
                    maxLines = 3,
                )
            }
            Rule()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PButton("Cancel", model::cancel, enabled = !state.saving)
                PButton(
                    if (state.saving) "Saving…" else "Save",
                    {
                        // A schedule over pause rules set up elsewhere deletes them: ask before anything is written.
                        if (state.replacesForeign) ask = LimitsAsk.REPLACE_PAUSE_RULES else scope.launch { model.save() }
                    },
                    kind = ButtonKind.PRIMARY,
                    enabled = state.canSave,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                if (state.canSave) "Save limits" else "Save limits (nothing to save)"
                        },
                )
            }
        }
        when (ask) {
            LimitsAsk.RESET_GENERAL -> {
                ConfirmDialog(
                    title = "Reset all downloads to defaults?",
                    body =
                        if (state.foreignSchedule) {
                            // The pause rules set up elsewhere are not the dialog's schedule: a reset leaves them.
                            "This resets total speed, slow mode and downloads at once. Network limits and the pause rules set up " +
                                "elsewhere stay unchanged. It takes effect when you save."
                        } else {
                            "This resets total speed, slow mode, downloads at once and the download schedule. " +
                                "Network limits stay unchanged. It takes effect when you save."
                        },
                    confirmText = "Reset to defaults",
                    onConfirm = {
                        model.resetGeneral()
                        ask = null
                    },
                    onCancel = { ask = null },
                )
            }

            LimitsAsk.RESET_USAGE -> {
                val name = shown?.name ?: "this network"
                val period = state.usage[state.page]?.period ?: DataPeriod.MONTH
                val slows = state.draft.networks[state.page]?.atLimit == AllowanceAction.SLOW
                ConfirmDialog(
                    title = "Reset data usage for $name?",
                    body =
                        "Takes effect now, even if you Cancel the Limits dialog afterwards. Deletes this network's recorded " +
                            "traffic for ${period.phrase}, so it also leaves the traffic history. " +
                            if (slows) {
                                "Downloads on this network are no longer slowed if its data limit was reached."
                            } else {
                                "Downloads can use this network again if its data limit was reached."
                            },
                    maxBodyLines = 7,
                    confirmText = "Reset data usage",
                    destructive = true,
                    onConfirm = {
                        val key = state.page
                        ask = null
                        if (key != null) scope.launch { model.resetUsage(key) }
                    },
                    onCancel = { ask = null },
                )
            }

            LimitsAsk.REMOVE_LIMITS -> {
                val name = shown?.name ?: "this network"
                ConfirmDialog(
                    title = "Remove limits for $name?",
                    body =
                        "This removes $name's speed and data limits when you save. Its recorded traffic is not deleted. " +
                            "The total speed limit and slow mode still apply.",
                    confirmText = "Remove limits",
                    onConfirm = {
                        state.page?.let(model::removeLimits)
                        ask = null
                    },
                    onCancel = { ask = null },
                    destructive = true,
                )
            }

            LimitsAsk.REPLACE_PAUSE_RULES -> {
                val rules = state.foreignRules
                ConfirmDialog(
                    title = "Replace the other pause rules?",
                    body =
                        "Saving deletes ${rules.size} pause ${if (rules.size == 1) "rule" else "rules"} set up elsewhere and puts " +
                            "the schedule in ${if (rules.size == 1) "its" else "their"} place: ${namedRules(rules)}.",
                    maxBodyLines = 6,
                    confirmText = "Replace and save",
                    onConfirm = {
                        ask = null
                        scope.launch { model.save(replaceForeign = true) }
                    },
                    onCancel = { ask = null },
                    destructive = true,
                )
            }

            null -> {}
        }
    }
}

private fun errorLine(
    error: FieldError,
    networks: List<NetworkRow>,
): String {
    val where = if (error.page == null) "All downloads" else networks.firstOrNull { it.key == error.page }?.name ?: error.page
    return "$where: ${error.message}"
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Plexo.colors.border))
}

@Composable
private fun Sidebar(
    state: LimitsState,
    model: LimitsModel,
    networks: List<NetworkRow>,
) {
    val colors = Plexo.colors
    Column(
        Modifier
            .width(190.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SideLabel("GENERAL")
        val general = generalSummary(state.draft.general, state.unit)
        SideItem("All downloads", colors.textSecondary, general, state.page == null || networks.none { it.key == state.page }) {
            model.select(null)
        }
        SideLabel("NETWORKS")
        val swatches = LocalNetworkLooks.current.swatches(colors)
        for (row in networks) {
            val draft = state.draft.networks[row.key] ?: NetworkDraft()
            val summary = networkSummary(draft, state.usage[row.key], state.unit)
            SideItem(row.name, swatches[row.key]?.solid ?: colors.accent, summary, state.page == row.key) { model.select(row.key) }
        }
    }
}

@Composable
private fun SideLabel(text: String) {
    Txt(text, Plexo.type.groupLabel, Modifier.padding(start = 10.dp, top = 12.dp, bottom = 6.dp), color = Plexo.colors.textTertiary)
}

@Composable
private fun SideItem(
    name: String,
    dot: Color,
    summary: PageSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .semantics { contentDescription = "Limits page: $name" }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(name, Plexo.type.rowName.copy(fontSize = Plexo.type.rowName.fontSize * 0.97f))
            for (line in summary.lines) {
                Txt(
                    line,
                    Plexo.type.detail.copy(fontSize = Plexo.type.detail.fontSize * 0.95f),
                    color = if (summary.reached) colors.danger else colors.textTertiary,
                )
            }
        }
    }
}

// Pages

@Composable
private fun PageHeader(
    title: String,
    sub: String,
) {
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(title, Plexo.type.title.copy(fontSize = Plexo.type.title.fontSize * 1.12f))
        Txt(sub, Plexo.type.detail, color = Plexo.colors.textTertiary)
    }
}

@Composable
private fun Section(
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Rule()
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(title, Plexo.type.menu)
            if (hint != null) Txt(hint, Plexo.type.hint, color = Plexo.colors.textSecondary, maxLines = 3)
            content()
        }
    }
}

@Composable
private fun GeneralPage(
    state: LimitsState,
    model: LimitsModel,
    onAsk: (LimitsAsk) -> Unit,
) {
    val g = state.draft.general
    val unitLabel = state.unit.fieldLabel
    PageHeader("All downloads", if (state.running == 0) "No active downloads" else "${state.running} downloading right now")
    Section("Total speed", "Limits the combined download speed across all networks.") {
        LimitChoice(
            label = "Total speed",
            on = g.totalOn,
            onToggle = model::setTotalLimited,
            text = g.totalText,
            onText = model::setTotalText,
            unit = unitLabel,
            error = state.errorOf(null, LimitField.TOTAL),
        )
    }
    Section("Slow mode", "Replaces the total speed limit while enabled. Useful during calls or streaming.") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmountField(
                g.slowText,
                model::setSlowText,
                "Slow mode speed",
                enabled = true,
                invalid =
                    state.errorOf(null, LimitField.SLOW) != null,
            )
            Txt(unitLabel, Plexo.type.detail, color = Plexo.colors.textTertiary)
            Box(Modifier.weight(1f))
            Txt("Enabled", Plexo.type.body)
            PSwitch(g.slowOn, model::setSlowOn, "Slow mode enabled")
        }
        state.errorOf(null, LimitField.SLOW)?.let { Txt(it, Plexo.type.hint, color = Plexo.colors.danger) }
    }
    Section("Downloads at once", "Other downloads wait in the queue and start automatically.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stepper(
                g.atOnce,
                onMinus = { model.stepAtOnce(-1) },
                onPlus = { model.stepAtOnce(1) },
                minusOff = g.atOnce <= MIN_AT_ONCE,
                plusOff =
                    g.atOnce >= maxOf(MAX_AT_ONCE, g.atOnce),
            )
            Txt("at the same time", Plexo.type.body)
        }
    }
    SchedulePart(state, model)
    Rule()
    Row(Modifier.padding(top = 12.dp)) {
        PButton("Reset to defaults", { onAsk(LimitsAsk.RESET_GENERAL) }, enabled = !generalAtDefaults(state.draft.general, state.unit))
    }
}

/** Whether the [minute] box needs another look: it is empty (outside the day) or start and stop are the same. */
private fun DownloadSchedule.invalidTime(minute: Int) = !isTimeOfDay(minute) || startMinute == endMinute

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedulePart(
    state: LimitsState,
    model: LimitsModel,
) {
    val colors = Plexo.colors
    val schedule = state.draft.general.schedule
    Rule()
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Download schedule", Plexo.type.menu, Modifier.weight(1f))
            Txt("Enabled", Plexo.type.body, Modifier.padding(end = 8.dp))
            PSwitch(schedule.enabled, model::setScheduleEnabled, "Enable download schedule")
        }
        // Pause rules set up elsewhere are named while the switch is off and while it is on: saving the schedule deletes them.
        if (state.foreignSchedule) {
            Txt(
                if (schedule.enabled) {
                    "Saving replaces the pause rules set up elsewhere: ${namedRules(state.foreignRules)}."
                } else {
                    "Other pause rules are set up (${namedRules(state.foreignRules)}). Turning the schedule on replaces them."
                },
                Plexo.type.hint,
                color = colors.textSecondary,
                maxLines = 4,
            )
        } else if (!schedule.enabled) {
            Txt("Downloads run only between the times you pick.", Plexo.type.hint, color = colors.textSecondary, maxLines = 3)
        }
        if (schedule.enabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("Start at", Plexo.type.body)
                    TimeField("Start at", schedule.startMinute, schedule.invalidTime(schedule.startMinute), model::setScheduleStart)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Txt("Stop at", Plexo.type.body, Modifier.weight(1f))
                        if (schedule.endsNextDay) Txt("Next day", Plexo.type.hint, color = colors.textTertiary)
                    }
                    TimeField("Stop at", schedule.endMinute, schedule.invalidTime(schedule.endMinute), model::setScheduleEnd)
                }
            }
            Txt("Repeat on", Plexo.type.body)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (day in DayOfWeek.entries) {
                    DayChip(day, day in schedule.days) { model.toggleScheduleDay(day) }
                }
            }
            state.errorOf(null, LimitField.SCHEDULE)?.let { Txt(it, Plexo.type.hint, color = colors.danger) }
        }
    }
}

@Composable
private fun NetworkPage(
    state: LimitsState,
    model: LimitsModel,
    row: NetworkRow,
    onAsk: (LimitsAsk) -> Unit,
) {
    val draft = state.draft.networks[row.key] ?: NetworkDraft()
    val usage = state.usage[row.key]
    val used = usage?.bytes ?: 0L
    val period = usage?.period ?: DataPeriod.MONTH
    val unitLabel = state.unit.fieldLabel
    val colors = Plexo.colors
    PageHeader(row.name, "${formatBytes(used)} used by anydm ${period.phrase}")
    Section("Speed", "Limits the combined download speed on ${row.name}.") {
        LimitChoice(
            label = "${row.name} speed",
            on = draft.speedOn,
            onToggle = { model.setSpeedLimited(row.key, it) },
            text = draft.speedText,
            onText = { model.setSpeedText(row.key, it) },
            unit = unitLabel,
            error = state.errorOf(row.key, LimitField.SPEED),
        )
    }
    Section(
        "Data limit",
        "${draft.atLimit.verb} downloads on ${row.name} at the limit. Downloads and uploads on this network count; other apps do not.",
    ) {
        LimitChoice(
            label = "${row.name} data limit",
            on = draft.dataOn,
            onToggle = { model.setDataLimited(row.key, it) },
            text = draft.dataText,
            onText = { model.setDataText(row.key, it) },
            unit = "GB",
            error = state.errorOf(row.key, LimitField.DATA),
        ) {
            Txt("per", Plexo.type.detail, color = colors.textTertiary)
            Segmented(
                DataPeriod.entries.map { it to it.label },
                draft.period,
                { model.setPeriod(row.key, it) },
                "${row.name} data limit period",
            )
        }
        val limit = if (draft.dataOn) amountBytes(draft.dataText, GIB) else null
        if (limit != null) {
            UsageBar(used, limit, period)
            Txt(
                if (draft.atLimit == AllowanceAction.SLOW) {
                    "Raise the limit or reset data usage to end the slowdown sooner."
                } else {
                    "Raise the limit or reset data usage to keep using this network sooner."
                },
                Plexo.type.detail,
                color = colors.textTertiary,
                maxLines = 3,
            )
        }
    }
    Rule()
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PButton("Reset data usage", { onAsk(LimitsAsk.RESET_USAGE) }, enabled = row.key in state.resettable && used > 0)
        PButton("Remove limits", { onAsk(LimitsAsk.REMOVE_LIMITS) }, enabled = draft.speedOn || draft.dataOn)
    }
    if (draft.dataOn && row.key !in state.resettable) {
        Txt(
            "Save the data limit first to be able to reset its usage.",
            Plexo.type.detail,
            Modifier.padding(top = 8.dp),
            color = colors.textTertiary,
            maxLines = 2,
        )
    }
}

@Composable
private fun UsageBar(
    used: Long,
    limit: Long,
    period: DataPeriod,
) {
    val colors = Plexo.colors
    val reached = used >= limit
    val fill = if (reached) colors.danger else colors.wifi.solid
    Row(
        Modifier.fillMaxWidth().semantics {
            contentDescription = "Data usage"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape)
                .background(colors.trackBg),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((used.toFloat() / limit).coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(fill),
            )
        }
        Txt(
            "${formatBytes(used)} of ${formatBytes(limit)} ${period.phrase}",
            Plexo.type.detail,
            color = if (reached) colors.danger else colors.textTertiary,
        )
    }
}

// Parts

/** "No limit" or "Limit to [amount] [unit]" as two radio choices; [trailing] sits after the unit. */
@Composable
private fun LimitChoice(
    label: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    text: String,
    onText: (String) -> Unit,
    unit: String,
    error: String?,
    trailing: @Composable () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Radio(!on, "No limit", "$label: No limit") { onToggle(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Radio(on, "Limit to", "$label: Limit to") { onToggle(true) }
            AmountField(text, onText, "$label amount", enabled = on, invalid = on && error != null)
            Txt(unit, Plexo.type.detail, color = Plexo.colors.textTertiary)
            trailing()
        }
        if (on && error != null) Txt(error, Plexo.type.hint, color = Plexo.colors.danger)
    }
}

@Composable
private fun Radio(
    selected: Boolean,
    text: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = Plexo.colors
    Row(
        Modifier
            .semantics { contentDescription = description }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
        }
        Txt(text, Plexo.type.body)
    }
}

/** A number typed in: digits and a decimal mark, right-aligned in the mono face; dim while [enabled] is off. */
@Composable
private fun AmountField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    invalid: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(PlexoRadius)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = Plexo.type.speed.copy(color = if (enabled) colors.text else colors.textTertiary, textAlign = TextAlign.End),
        cursorBrush = SolidColor(colors.accent),
        modifier =
            modifier
                .width(84.dp)
                .height(34.dp)
                .clip(shape)
                .background(colors.inputBg)
                .border(0.5.dp, if (invalid) colors.danger else colors.borderStrong, shape)
                .semantics { contentDescription = label },
        decorationBox = { inner -> Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterEnd) { inner() } },
    )
}

@Composable
private fun Stepper(
    value: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusOff: Boolean,
    plusOff: Boolean,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.clip(shape).border(0.5.dp, colors.borderStrong, shape), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "Decrease simultaneous downloads", minusOff, onMinus)
        BasicText(
            value.toString(),
            Modifier.width(32.dp).semantics { contentDescription = "Downloads at once" },
            Plexo.type.speed.copy(color = colors.text, textAlign = TextAlign.Center),
            maxLines = 1,
        )
        StepButton("+", "Increase simultaneous downloads", plusOff, onPlus)
    }
}

@Composable
private fun StepButton(
    glyph: String,
    label: String,
    off: Boolean,
    onClick: () -> Unit,
) {
    val colors = Plexo.colors
    Box(
        Modifier
            .size(32.dp)
            .semantics { contentDescription = label }
            .clickable(enabled = !off, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(glyph, Plexo.type.menu, color = if (off) colors.textTertiary.copy(alpha = 0.4f) else colors.text)
    }
}

@Composable
private fun DayChip(
    day: DayOfWeek,
    on: Boolean,
    onChange: () -> Unit,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(12.dp)
    val name = day.name.lowercase().replaceFirstChar { it.uppercase() }
    Box(
        Modifier
            .height(24.dp)
            .widthIn(min = 24.dp)
            .clip(shape)
            .background(if (on) colors.accent else colors.bgTertiary)
            .border(0.5.dp, if (on) colors.accent else colors.border, shape)
            .semantics { contentDescription = name }
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onChange() })
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(name.take(3), Plexo.type.detail, color = if (on) colors.accentOnSolid else colors.textSecondary)
    }
}

/**
 * A 24-hour time as two fields, hour and minute; each takes up to two digits within its range. A box left empty makes the time
 * [NO_TIME] (the schedule is then not valid), never the time it had before.
 */
@Composable
private fun TimeField(
    label: String,
    minute: Int,
    invalid: Boolean,
    onChange: (Int) -> Unit,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(PlexoRadius)
    var hour by remember { mutableStateOf(if (isTimeOfDay(minute)) "%02d".format(minute / 60) else "") }
    var min by remember { mutableStateOf(if (isTimeOfDay(minute)) "%02d".format(minute % 60) else "") }
    // The fields show what was typed; a change from outside (a reset) puts them right again. A time left empty is theirs.
    LaunchedEffect(minute) {
        if (!isTimeOfDay(minute)) return@LaunchedEffect
        if (hour.toIntOrNull() != minute / 60) hour = "%02d".format(minute / 60)
        if (min.toIntOrNull() != minute % 60) min = "%02d".format(minute % 60)
    }

    // Both boxes make the time; one left empty leaves no time.
    fun changed(
        hours: String,
        minutes: String,
    ) {
        val h = hours.toIntOrNull()
        val m = minutes.toIntOrNull()
        onChange(if (h == null || m == null) NO_TIME else h * 60 + m)
    }
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(colors.inputBg)
            .border(0.5.dp, if (invalid) colors.danger else colors.borderStrong, shape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DigitBox(hour, "$label hour", 23) {
            hour = it
            changed(it, min)
        }
        Txt(":", Plexo.type.speed, color = colors.textTertiary)
        DigitBox(min, "$label minute", 59) {
            min = it
            changed(hour, it)
        }
        Box(Modifier.weight(1f))
        Txt("24h", Plexo.type.detail, color = colors.textTertiary)
    }
}

@Composable
private fun DigitBox(
    value: String,
    label: String,
    max: Int,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(2)
            if (digits.isEmpty() || (digits.toInt() <= max)) onChange(digits)
        },
        singleLine = true,
        textStyle = Plexo.type.speed.copy(color = Plexo.colors.text, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Plexo.colors.accent),
        modifier = Modifier.width(26.dp).semantics { contentDescription = label },
    )
}
