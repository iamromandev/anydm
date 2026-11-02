package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

private val HeaderHeight = 48.dp

/** What the header shows while nothing is selected: the filter, the count text, the networks pill and New download. */
@Composable
fun HeaderBar(
    state: HomeState,
    unit: SpeedUnit,
    onFilter: (HomeFilter) -> Unit,
    onNetworks: () -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    onNewDownload: () -> Unit,
    /** Opens the dialog that pairs browsers. */
    onBrowsers: () -> Unit = {},
    /** What opens under the networks pill: the networks menu. */
    networksMenu: @Composable () -> Unit = {},
) {
    val colors = Plexo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterMenu(state.filter, state.counts, onFilter, onMenuOpen)
        state.summary?.let { Txt(it, Plexo.type.detail.copy(fontSize = Plexo.type.detail.fontSize), color = colors.textTertiary) }
        Spacer(Modifier.weight(1f))
        PButton("Browsers", onBrowsers)
        Box {
            NetworksPill(state.pill, unit, onNetworks)
            networksMenu()
        }
        PButton("New download", onNewDownload, kind = ButtonKind.PRIMARY, glyph = Glyph.PLUS)
    }
    HeaderRule()
}

@Composable
internal fun HeaderRule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Plexo.colors.border))
}

/** The header while rows are selected: how many, select all, and the bulk commands that fit them. */
@Composable
fun SelectionBar(
    state: HomeState,
    total: Int,
    model: HomeModel,
) {
    val bulk = state.bulk
    Row(
        Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .padding(horizontal = 20.dp)
            .semantics { contentDescription = "Selected downloads" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Glyph.CLOSE, "Deselect all", model::clearSelection)
        Txt("${state.selection.size} selected", Plexo.type.menu.copy(fontSize = Plexo.type.body.fontSize))
        if (state.selection.size < total) PButton("Select all", model::selectAll, kind = ButtonKind.GHOST)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bulk.pause > 0) PButton(label("Pause", bulk.pause), model::pauseSelected)
            if (bulk.resume > 0) PButton(label("Resume", bulk.resume), model::resumeSelected)
            if (bulk.retry > 0) PButton(label("Retry", bulk.retry), model::retrySelected)
            PButton(label("Remove", bulk.remove), model::askRemoveSelected, kind = ButtonKind.DESTRUCTIVE)
        }
    }
    HeaderRule()
}

private fun label(
    verb: String,
    count: Int,
) = if (count == 1) verb else "$verb $count"

/** The filter as a title with a menu: each choice with how many downloads it would show. */
@Composable
fun FilterMenu(
    value: HomeFilter,
    counts: Map<HomeFilter, Int>,
    onChange: (HomeFilter) -> Unit,
    onOpenChange: (Boolean) -> Unit = {},
) {
    val colors = Plexo.colors
    var open by remember { mutableStateOf(false) }
    // The window's shortcuts wait while the menu is open.
    DisposableEffect(open) {
        onOpenChange(open)
        onDispose { onOpenChange(false) }
    }
    Box {
        Row(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(PlexoRadius))
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(value.label, Plexo.type.menu)
            GlyphIcon(Glyph.CHEVRON_DOWN, colors.textTertiary, size = 14.dp)
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(208.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.bgSecondary)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (filter in HomeFilter.entries) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (filter == value) colors.border else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable(role = Role.RadioButton) {
                                    open = false
                                    onChange(filter)
                                }.padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(16.dp)) {
                                if (filter == value) GlyphIcon(Glyph.CHECK, colors.accent, size = 16.dp)
                            }
                            Txt(filter.label, Plexo.type.body, Modifier.weight(1f))
                            Txt("${counts[filter] ?: 0}", Plexo.type.detail, color = colors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}

/** The networks pill: a colored dot for each network (the first four), how many there are and what they carry together. */
@Composable
fun NetworksPill(
    pill: NetworksPill,
    unit: SpeedUnit,
    onClick: () -> Unit,
) {
    val colors = Plexo.colors
    val swatches = LocalNetworkLooks.current.swatches(colors)
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(PlexoRadius))
            .background(colors.bgTertiary)
            .border(0.5.dp, colors.border, RoundedCornerShape(PlexoRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (key in pill.keys.take(4)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background((swatches[key]?.solid ?: colors.accent)))
            }
        }
        if (pill.count == 0) {
            Txt("No network", Plexo.type.button, color = colors.danger)
        } else {
            Txt(if (pill.count == 1) "1 network" else "${pill.count} networks", Plexo.type.button)
            if (pill.speed > 0) Txt(formatRate(pill.speed, unit), Plexo.type.detail, color = colors.textTertiary)
        }
        GlyphIcon(Glyph.CHEVRON_DOWN, colors.textTertiary, size = 14.dp)
    }
}
