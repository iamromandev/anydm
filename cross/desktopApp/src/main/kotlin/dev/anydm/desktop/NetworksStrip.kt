package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anydm.engine.net.AllowanceStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/** Sizes of the networks strip. */
internal object NetworkStripTokens {
    val ChipMinWidth = 220.dp
    val BarHeight = 8.dp
    val Dot = 10.dp
    val ToggleHeight = 28.dp
}

private const val DASH = "–"

/** The colour of the [index]th network in the split bar and on its chip. */
@Composable
private fun segmentColor(index: Int): Color = SplitPalette.of(LocalDark.current)[index % SplitPalette.SIZE]

/** What the split bar says to a screen reader and a test: each active network's whole percent. */
internal fun splitDescription(rows: List<StripRow>): String {
    val shown = rows.filter { it.share > 0f }
    return if (shown.isEmpty()) "No traffic" else shown.joinToString(", ") { "${it.name} ${(it.share * 100).roundToInt()}%" }
}

internal fun resetText(
    usage: AllowanceStatus,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val at = usage.resetsAt.toLocalDateTime(zone)
    val time = "%02d:%02d".format(at.hour, at.minute)
    return if (usage.from == usage.to) "resets $time" else "resets ${at.date} $time"
}

/** The networks above the downloads (Plexo): each network's speed and part of the total, with its limits one click away. */
@Composable
internal fun NetworksStrip(
    model: NetworksStripModel,
    onTyping: (Boolean) -> Unit,
) {
    val state by model.state.collectAsState()
    // With no network to show there is nothing to put in the strip.
    if (state.rows.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Space.lg, vertical = Space.xs)
            .testTag("networks-strip"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text("Networks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Total ${formatSpeed(state.total)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).testTag("strip-total"),
            )
            if (state.rows.any { it.usage?.over == true }) {
                Text(
                    "Over budget",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("strip-over-budget"),
                )
            }
            TextButton(
                onClick = { model.setCollapsed(!state.collapsed) },
                contentPadding = PaddingValues(horizontal = Space.sm),
                modifier = Modifier.height(NetworkStripTokens.ToggleHeight).testTag("networks-strip-toggle"),
            ) {
                Text(if (state.collapsed) "Show" else "Hide")
            }
        }
        if (!state.collapsed) {
            if (state.rows.count { it.active } >= 2) SplitBar(state.rows)
            // One row of chips, so the strip keeps one height; more networks than fit scroll sideways.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("strip-chips"),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                state.rows.forEachIndexed { index, row -> Chip(row, segmentColor(index), model) }
            }
            state.message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("strip-message"),
                )
            }
        }
    }
    state.editing?.let { key ->
        state.rows.firstOrNull { it.key == key }?.let { NetworkLimitsPopover(it, state.message, model, onTyping) }
    }
}

@Composable
private fun SplitBar(rows: List<StripRow>) {
    val shown = rows.withIndex().filter { it.value.share > 0f }
    Row(
        Modifier
            .fillMaxWidth()
            .height(NetworkStripTokens.BarHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50))
            .semantics { contentDescription = splitDescription(rows) }
            .testTag("network-split-bar"),
    ) {
        shown.forEach { (index, row) ->
            Box(
                Modifier
                    .weight(
                        row.share,
                    ).height(NetworkStripTokens.BarHeight)
                    .background(segmentColor(index))
                    .testTag("network-split-${row.key}"),
            )
        }
    }
}

@Composable
private fun Chip(
    row: StripRow,
    color: Color,
    model: NetworksStripModel,
) {
    val key = row.key
    val muted = !row.active
    val stateText =
        when {
            !row.up -> "Down"
            row.faulted -> "Left out"
            !row.enabled -> "Off"
            !row.active -> "Not usable"
            else -> "Bonded"
        }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(min = NetworkStripTokens.ChipMinWidth).testTag("network-chip-$key"),
    ) {
        Row(Modifier.padding(horizontal = Space.sm, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                row.enabled,
                { model.enable(key, it) },
                Modifier.semantics { contentDescription = "Use ${row.name}" }.testTag("strip-toggle-$key"),
                enabled = row.switchable || row.enabled,
            )
            Column(Modifier.padding(end = Space.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(
                        Modifier
                            .size(
                                NetworkStripTokens.Dot,
                            ).background(if (muted) MaterialTheme.colorScheme.outlineVariant else color, CircleShape),
                    )
                    Text(row.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("strip-name-$key"))
                    Text(stateText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("strip-state-$key"))
                    Text(
                        row.speed?.let(::formatSpeed) ?: DASH,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("strip-speed-$key"),
                    )
                }
                row.usage?.let { usage ->
                    val over = usage.over
                    Text(
                        "${budgetText(usage)} · ${resetText(usage)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .semantics { if (over) stateDescription = "Over budget" }
                                .testTag("strip-usage-$key"),
                    )
                }
            }
            TextButton(
                onClick = { model.openLimits(key) },
                modifier = Modifier.semantics { contentDescription = "Limits for ${row.name}" }.testTag("network-limits-$key"),
            ) {
                Text(row.cap?.let { "Cap ${formatSpeed(it)}" } ?: "Limits", modifier = Modifier.testTag("strip-cap-$key"))
            }
        }
    }
}
