package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek

private const val AMOUNT_ERROR = "Enter a number above 0, up to 100000 with one decimal"

/** The Speed & data limits as an editable draft with Save and Cancel. Content only: the Settings page provides the frame. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LimitsEditor(
    initial: Limits,
    networks: List<Network>,
    unit: SpeedUnit,
    slow: Boolean,
    onCancel: () -> Unit,
    onSave: (Limits, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialPane: String = GENERAL_PANE,
    onPaneChange: (String) -> Unit = {},
    /** Where the draft lives. The Settings page owns it, so the draft outlives this editor when another section is shown. */
    draftHolder: LimitsDraftHolder = remember { LimitsDraftHolder() },
) {
    val c = colors
    // The draft remembers the unit and the footer's slow switch it was last shown under. When either has changed since, the draft
    // is converted to the new unit and takes the new switch value, so an unsaved draft is kept, not rebuilt.
    var held by draftHolder.state
    val shown = held
    if (shown == null) {
        held = HeldDraft(draftOf(initial, unit, slow), unit, slow)
    } else if (shown.unit != unit || shown.slow != slow) {
        held = HeldDraft(shown.under(unit, slow), unit, slow)
    }
    val draft = (held ?: HeldDraft(draftOf(initial, unit, slow), unit, slow)).draft
    val setDraft = { next: LimitsDraft -> held = HeldDraft(next, unit, slow) }
    var pane by remember { mutableStateOf(initialPane) }
    val errors = errorPanes(draft)
    val save = { limitsOf(draft, unit)?.let { onSave(it, it.slowOn) } }

    Column(
        modifier
            .testTag("limits-editor")
            .fillMaxSize()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter) && errors.isEmpty()) {
                    save()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(Modifier.weight(1f)) {
            Sidebar(draft, networks, unit, pane, errors) {
                pane = it
                onPaneChange(it)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val net = networks.firstOrNull { it.id == pane }
                val nd = draft.networks[pane]
                if (net != null && nd != null) {
                    NetworkPane(net, nd, unit) { next -> setDraft(draft.copy(networks = draft.networks + (pane to next))) }
                } else {
                    GeneralPane(draft.general, unit) { next -> setDraft(draft.copy(general = next)) }
                }
            }
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            OutlineButton("Cancel", "limits-cancel", onCancel)
            PrimaryButton("Save", "limits-save", errors.isEmpty()) { save() }
        }
    }
}

/** The unsaved Speed & data draft, kept by whoever shows the editor; [discard] throws it away. */
class LimitsDraftHolder {
    internal val state = mutableStateOf<HeldDraft?>(null)

    fun discard() {
        state.value = null
    }
}

internal class HeldDraft(
    val draft: LimitsDraft,
    val unit: SpeedUnit,
    val slow: Boolean,
) {
    fun under(
        unit: SpeedUnit,
        slow: Boolean,
    ): LimitsDraft {
        val converted = rebaseDraft(draft, this.unit, unit)
        return if (slow == this.slow) converted else converted.copy(general = converted.general.copy(slowOn = slow))
    }
}

@Composable
private fun Sidebar(
    draft: LimitsDraft,
    networks: List<Network>,
    unit: SpeedUnit,
    selected: String,
    errors: Set<String>,
    onSelect: (String) -> Unit,
) {
    val c = colors
    Column(Modifier.width(200.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SidebarHeader("GENERAL")
        NavRow(
            GENERAL_PANE,
            "All downloads",
            summaryGeneral(draft.general, unit),
            c.textSecondary,
            null,
            false,
            selected,
            GENERAL_PANE in errors,
            onSelect,
        )
        Spacer(Modifier.height(8.dp))
        SidebarHeader("NETWORKS")
        networks.forEach { n ->
            val d = draft.networks[n.id] ?: return@forEach
            val dot = networkColor(n.kind, n.color, c)
            NavRow(
                n.id,
                n.name,
                summaryNetwork(d, unit),
                if (n.enabled) dot else c.textSecondary.copy(alpha = 0.45f),
                n.color,
                !n.enabled,
                selected,
                n.id in errors,
                onSelect,
            )
        }
    }
}

@Composable
private fun SidebarHeader(text: String) {
    Text(
        text,
        color = colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun NavRow(
    id: String,
    name: String,
    summary: String,
    dot: Color,
    choice: NetworkColor?,
    off: Boolean,
    selected: String,
    error: Boolean,
    onSelect: (String) -> Unit,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    val on = id == selected
    Row(
        Modifier
            .testTag("limits-nav-$id")
            .fillMaxWidth()
            .background(if (on) c.track else Color.Transparent, shape)
            .selectable(on, role = Role.Tab, onClick = { onSelect(id) })
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).networkDot("limits-dot-$id", choice).background(if (error) c.danger else dot, CircleShape))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    name,
                    color = if (off) c.textSecondary else c.text,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (off) Text("Off", color = c.textSecondary, fontSize = 10.sp, maxLines = 1, modifier = Modifier.testTag("limits-off-$id"))
            }
            Text(
                summary,
                color = if (error) c.danger else c.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("limits-nav-summary-$id"),
            )
        }
        if (error) Box(Modifier.testTag("limits-nav-error-$id").size(6.dp).background(c.danger, CircleShape))
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        content()
    }
}

@Composable
private fun ErrorText() {
    Text(AMOUNT_ERROR, color = colors.danger, fontSize = 11.sp, modifier = Modifier.testTag("limits-error"))
}

/** "No limit" / "Limit to <field>" pair; the field is disabled while "No limit" is selected. */
@Composable
private fun LimitChoice(
    field: LimitField,
    onChange: (LimitField) -> Unit,
    suffix: String,
    noneTag: String,
    limitTag: String,
    fieldTag: String,
    fieldLabel: String,
    trailing: @Composable () -> Unit = {},
) {
    val bad = field.on && parseAmount(field.text) == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioDot(!field.on, { onChange(field.copy(on = false)) }, noneTag, "No limit")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioDot(field.on, { onChange(field.copy(on = true)) }, limitTag, "Limit to")
            NumberField(field.text, { onChange(field.copy(text = it)) }, field.on, bad, suffix, fieldTag, fieldLabel)
            trailing()
        }
        if (bad) ErrorText()
    }
}

@Composable
private fun GeneralPane(
    d: GeneralDraft,
    unit: SpeedUnit,
    onChange: (GeneralDraft) -> Unit,
) {
    val c = colors
    val label = unitLabel(unit)
    Text("All downloads", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    Section("Total speed") {
        LimitChoice(d.total, { onChange(d.copy(total = it)) }, label, "total-none", "total-limit", "total-field", "Total speed limit")
    }
    Section("Slow mode") {
        val bad = d.slowOn && slowBad(d)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(d.slowText, { onChange(d.copy(slowText = it)) }, true, bad, label, "slow-field", "Slow mode speed")
            Spacer(Modifier.weight(1f))
            Text("Enabled", color = c.textSecondary, fontSize = 13.sp, maxLines = 1)
            AnydmSwitch(d.slowOn, { onChange(d.copy(slowOn = it)) }, "slow-enabled", "Slow mode enabled")
        }
        if (bad) ErrorText()
        if (d.slowOn) Text("Replaces the total speed limit while enabled", color = c.textSecondary, fontSize = 12.sp)
    }
    Section("Downloads at once") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stepper(d.concurrency, MIN_CONCURRENCY, MAX_CONCURRENCY, { onChange(d.copy(concurrency = it)) }, "concurrency")
            Text("No active downloads", color = c.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
    Section("Download schedule") {
        ScheduleEditor(d.schedule) { onChange(d.copy(schedule = it)) }
    }
    OutlineButton("Reset to defaults", "limits-reset-defaults", { onChange(defaultGeneral(unit)) })
}

private val dayChips =
    listOf(
        DayOfWeek.MONDAY to "Mon",
        DayOfWeek.TUESDAY to "Tue",
        DayOfWeek.WEDNESDAY to "Wed",
        DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri",
        DayOfWeek.SATURDAY to "Sat",
        DayOfWeek.SUNDAY to "Sun",
    )

@Composable
private fun ScheduleEditor(
    s: Schedule,
    onChange: (Schedule) -> Unit,
) {
    val c = colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Only download at set times", color = c.textSecondary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text("Enabled", color = c.textSecondary, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        AnydmSwitch(s.enabled, { onChange(s.copy(enabled = it)) }, "schedule-enabled", "Download schedule enabled")
    }
    if (!s.enabled) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        dayChips.forEach { (day, label) ->
            val on = day in s.days
            Box(
                Modifier
                    .testTag("schedule-day-${day.name.take(3).lowercase()}")
                    .background(if (on) c.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .border(1.dp, if (on) c.accent else c.border, RoundedCornerShape(6.dp))
                    .toggleable(
                        on,
                        role = Role.Checkbox,
                        onValueChange = { onChange(s.copy(days = if (on) s.days - day else s.days + day)) },
                    ).semantics { contentDescription = label }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    color = if (on) c.onAccent else c.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
    val error = scheduleError(s)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("From", color = c.textSecondary, fontSize = 13.sp, maxLines = 1)
        NumberField(s.start, { onChange(s.copy(start = it)) }, true, badStart(s), "", "schedule-start", "Schedule start")
        Text("to", color = c.textSecondary, fontSize = 13.sp, maxLines = 1)
        NumberField(s.end, { onChange(s.copy(end = it)) }, true, badEnd(s), "", "schedule-end", "Schedule end")
    }
    if (error != null) Text(error, color = c.danger, fontSize = 11.sp, modifier = Modifier.testTag("schedule-error"))
}

@Composable
private fun NetworkPane(
    n: Network,
    d: NetworkDraft,
    unit: SpeedUnit,
    onChange: (NetworkDraft) -> Unit,
) {
    val c = colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(n.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(
            "${formatBytes(d.used)} used this month",
            color = c.textSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.testTag("limits-usage"),
        )
    }
    Section("Speed") {
        LimitChoice(d.speed, {
            onChange(d.copy(speed = it))
        }, unitLabel(unit), "net-speed-none", "net-speed-limit", "net-speed-field", "Speed limit")
    }
    Section("Data limit") {
        LimitChoice(d.data, { onChange(d.copy(data = it)) }, "GB", "net-data-none", "net-data-limit", "net-data-field", "Data limit") {
            Text("per", color = c.textSecondary, fontSize = 13.sp, maxLines = 1)
            SegmentedChips(DataPeriod.entries.map { it to it.label }, d.period, { onChange(d.copy(period = it)) }) {
                "net-period-${it.name.lowercase()}"
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlineButton("Reset data usage", "reset-usage", { onChange(d.copy(used = 0)) })
        OutlineButton("Remove limits", "remove-limits", { onChange(removeLimits(d)) }, enabled = d.speed.on || d.data.on)
    }
}
