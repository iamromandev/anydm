package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One network in the map's legend: its key (what a cell names it by), its name and its color. */
class LegendEntry(
    val key: String,
    val name: String,
    val color: androidx.compose.ui.graphics.Color,
)

/**
 * The file as squares, each in the color of the network that did it (the accent while no network is named): a filled
 * square is done, a part-filled one is part done. At most [MAX_CHUNK_CELLS] squares; more chunks share them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChunkMapView(
    map: ChunkMap,
    legend: List<LegendEntry>,
    unitLabel: String,
    totalBytes: Long?,
    modifier: Modifier = Modifier,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(10.dp)
    val each = if (totalBytes != null && totalBytes > 0 && map.chunks > 0) " · ${formatBytes(totalBytes / map.chunks)} each" else ""
    val byKey = legend.associate { it.key to it.color }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bgSecondary)
            .border(0.5.dp, colors.border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for (entry in legend) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(entry.color))
                    Txt(entry.name, Plexo.type.hint.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                }
            }
            Box(Modifier.weight(1f))
            Txt(
                "${map.chunks} $unitLabel$each",
                Plexo.type.detail.copy(fontSize = Plexo.type.detail.fontSize * 0.9f),
                color = colors.textTertiary,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 70.dp)
                .verticalScroll(rememberScrollState())
                .semantics { contentDescription = "Chunk map, ${map.chunks} $unitLabel" },
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (cell in map.cells) {
                    val solid = cell.key?.let { byKey[it] } ?: colors.accent
                    val cellShape = RoundedCornerShape(3.dp)
                    Box(
                        Modifier
                            .size(width = 12.dp, height = 13.dp)
                            .clip(cellShape)
                            .background(if (cell.fill >= 1f) solid else colors.trackBg)
                            .let { if (cell.fill in 0.001f..0.999f) it.border(1.dp, solid, cellShape) else it },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (cell.fill in
                            0.001f..0.999f
                        ) {
                            Box(Modifier.fillMaxHeight(cell.fill).fillMaxWidth().background(solid.copy(alpha = 0.6f)))
                        }
                    }
                }
            }
        }
    }
}
