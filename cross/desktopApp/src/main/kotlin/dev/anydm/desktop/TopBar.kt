package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TopBar(
    filter: Filter,
    onFilter: (Filter) -> Unit,
    filterOpen: Boolean,
    onFilterOpenChange: (Boolean) -> Unit,
    downloadingCount: Int,
    networks: List<Network>,
    unit: SpeedUnit,
    onNetworksClick: () -> Unit,
    onChipBounds: (Rect) -> Unit,
    onNewDownload: () -> Unit = {},
) {
    val c = colors
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier
                    .testTag("filter")
                    .clickable(role = Role.DropdownList) { onFilterOpenChange(true) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(filter.label, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                ChevronIcon(c.textSecondary, down = true)
            }
            DropdownMenu(expanded = filterOpen, onDismissRequest = { onFilterOpenChange(false) }) {
                Filter.entries.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(f.label, color = c.text, fontSize = 14.sp) },
                        onClick = {
                            onFilter(f)
                            onFilterOpenChange(false)
                        },
                        modifier = Modifier.testTag("filter-${f.name.lowercase()}"),
                    )
                }
            }
        }
        if (downloadingCount > 0) {
            Spacer(Modifier.width(12.dp))
            Text(
                "$downloadingCount downloading",
                color = c.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.testTag("status-text"),
            )
        }
        Spacer(Modifier.weight(1f))
        NetworkChip(networks, unit, onNetworksClick, onChipBounds)
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier
                .testTag("new-download")
                .height(36.dp)
                .background(c.accent, RoundedCornerShape(Radius))
                .clickable(role = Role.Button, onClick = onNewDownload)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlusIcon(c.onAccent)
            Text("New download", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private const val MAX_CHIP_DOTS = 3

@Composable
private fun NetworkChip(
    networks: List<Network>,
    unit: SpeedUnit,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val c = colors
    Row(
        Modifier
            .testTag("network-chip")
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .height(36.dp)
            .border(1.dp, c.border, RoundedCornerShape(Radius))
            .background(c.surface, RoundedCornerShape(Radius))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            networks.filter { it.enabled }.take(MAX_CHIP_DOTS).forEach { n ->
                Box(Modifier.size(7.dp).networkDot("chip-dot-${n.id}", n.color).background(networkColor(n.kind, n.color, c), CircleShape))
            }
        }
        Text(
            networkLabel(enabledCount(networks)),
            color = c.text,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Text(
            formatSpeed(totalSpeed(networks), unit),
            color = c.textSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.testTag("chip-speed"),
        )
        ChevronIcon(c.textSecondary, down = true, size = 12)
    }
}
