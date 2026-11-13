package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private const val MIB = 1024.0 * 1024
private val LabelHeight = 44.dp
private val PlotHeight = 96.dp
private val CellSize = 14.dp
private val CellGap = 4.dp
private const val PIECE_ROWS = 4

@Composable
private fun Mono(
    text: String,
    color: Color,
    size: Int,
    modifier: Modifier = Modifier,
    spacing: Int = 0,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Start,
) = Text(
    text,
    color = color,
    fontSize = size.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    letterSpacing = spacing.sp,
    maxLines = 1,
    textAlign = align,
    modifier = modifier,
)

private fun statLine(
    label: String,
    value: String,
    c: AnydmColors,
) = buildAnnotatedString {
    withStyle(SpanStyle(color = c.textSecondary)) { append("$label ") }
    withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append(value) }
}

/** What stands where the speed diagram is for a real download: its connection count, and the speed it measured. */
@Composable
fun SingleConnectionSpeed(
    d: Detail,
    unit: SpeedUnit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val total = formatSpeed(d.total, unit)
    val torrent = d.item.kind == DownloadKind.Torrent
    val n = if (torrent) d.peers else connectionCount(d.item)
    val what = if (torrent) "peer" else "connection"
    Column(
        modifier
            .testTag("detail-diagram")
            .semantics { contentDescription = "Speed $total over ${if (n == 1) "one $what" else "$n ${what}s"}" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (torrent) {
            Mono("PEERS $n", c.textSecondary, 10, Modifier.testTag("detail-peers"), spacing = 1)
        } else {
            Mono(if (n == 1) "1 CONNECTION" else "$n CONNECTIONS", c.textSecondary, 10, spacing = 1)
        }
        Mono(total, c.text, 24, Modifier.testTag("detail-single-speed"), bold = true)
        // This version never sends a piece: what a torrent uploaded is always nothing, and the view says so.
        if (torrent) Mono("uploaded ${formatBytes(0)}", c.textSecondary, 10, Modifier.testTag("detail-uploaded"), spacing = 1)
    }
}

@Composable
fun SpeedDiagram(
    d: Detail,
    unit: SpeedUnit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val live = d.rows.filter { it.on }
    val fastest = live.maxByOrNull { it.speed }
    val total = formatSpeed(d.total, unit)
    Row(
        modifier
            .testTag("detail-diagram")
            .semantics { contentDescription = "Speed diagram, total $total from ${networkLabel(live.size)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            live.forEach { r ->
                Column(Modifier.height(LabelHeight), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
                    val color = networkColor(r.kind, r.color, c)
                    Mono(r.name.uppercase(), color, 10, spacing = 1)
                    Mono(formatSpeed(r.speed, unit), color, 13, Modifier.testTag("detail-diagram-speed-${r.id}"), bold = true)
                }
            }
        }
        val colors = live.map { networkColor(it.kind, it.color, c) }
        Box(Modifier.width(96.dp).height(LabelHeight * live.size.coerceAtLeast(1))) {
            Canvas(Modifier.fillMaxWidth().height(LabelHeight * live.size.coerceAtLeast(1))) {
                val mid = size.height / 2
                val joinX = size.width - 30.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                colors.forEachIndexed { i, col ->
                    val y = LabelHeight.toPx() * (i + 0.5f)
                    drawCircle(col, 3.dp.toPx(), Offset(4.dp.toPx(), y))
                    val path =
                        Path().apply {
                            moveTo(4.dp.toPx(), y)
                            cubicTo(joinX * 0.6f, y, joinX * 0.5f, mid, joinX, mid)
                        }
                    drawPath(path, col, style = Stroke(2.dp.toPx(), pathEffect = dash, cap = StrokeCap.Round))
                }
                drawCircle(c.textSecondary, 8.dp.toPx(), Offset(joinX, mid), style = Stroke(1.dp.toPx()))
                drawLine(c.text, Offset(joinX, mid), Offset(size.width - 2.dp.toPx(), mid), 3.dp.toPx(), StrokeCap.Round)
                val head =
                    Path().apply {
                        moveTo(size.width, mid)
                        lineTo(size.width - 7.dp.toPx(), mid - 4.dp.toPx())
                        lineTo(size.width - 7.dp.toPx(), mid + 4.dp.toPx())
                        close()
                    }
                drawPath(head, c.text, style = Fill)
            }
            Mono(
                "COMBINED",
                c.textSecondary,
                9,
                Modifier.align(Alignment.TopEnd).offset(
                    y =
                        LabelHeight * live.size.coerceAtLeast(1) / 2 - 24.dp,
                ),
                spacing = 1,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Mono("TOTAL SPEED", c.textSecondary, 11, spacing = 2)
            Text(
                total.let { t ->
                    buildAnnotatedString {
                        val split = t.indexOf(' ')
                        withStyle(SpanStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold)) { append(if (split > 0) t.take(split) else t) }
                        if (split > 0) withStyle(SpanStyle(fontSize = 13.sp, color = c.textSecondary)) { append(t.drop(split)) }
                    }
                },
                color = c.text,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.testTag("detail-total"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    statLine("AVG", formatSpeed(d.avg, unit), c),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.testTag("detail-avg"),
                )
                Text(
                    statLine("PEAK", formatSpeed(d.peak, unit), c),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.testTag("detail-peak"),
                )
            }
            if (d.aloneFactor != null && fastest != null) {
                Spacer(Modifier.height(8.dp))
                Mono(
                    "${String.format(Locale.ROOT, "%.1f", d.aloneFactor)}× ${fastest.name.uppercase()} ALONE",
                    networkColor(fastest.kind, fastest.color, c),
                    11,
                    Modifier
                        .testTag("detail-alone")
                        .border(1.dp, c.border, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    spacing = 1,
                    bold = true,
                )
            }
        }
    }
}

private fun tickLabel(
    bytesPerSec: Long,
    unit: SpeedUnit,
): String {
    val v = if (unit == SpeedUnit.MBps) bytesPerSec / MIB else bytesPerSec * 8 / MIB
    val num = if (v % 1.0 < 0.05 || v % 1.0 > 0.95) "${Math.round(v)}" else String.format(Locale.ROOT, "%.1f", v)
    return "$num ${if (unit == SpeedUnit.MBps) "MB/s" else "Mbps"}"
}

@Composable
fun ThroughputGraph(
    d: Detail,
    unit: SpeedUnit,
    modifier: Modifier = Modifier,
    title: String = "THROUGHPUT · LAST 60 S",
    startLabel: String = "60s ago",
    endLabel: String = "now",
    plotHeight: Dp = PlotHeight,
) {
    val c = colors
    val ticks = niceTicks(d.peak, unit)
    val top = ticks.last().toFloat()
    // A real download has the one measured series and no networks to colour it by.
    val live = if (d.real) listOf(0) else d.rows.indices.filter { d.rows[it].on }
    val fills = if (d.real) listOf(c.accent) else live.map { networkColor(d.rows[it].kind, d.rows[it].color, c) }
    Column(modifier) {
        Mono(title, c.textSecondary, 10, spacing = 1)
        Spacer(Modifier.height(6.dp))
        Row {
            Box(Modifier.width(56.dp).height(plotHeight)) {
                ticks.forEachIndexed { i, t ->
                    Mono(
                        tickLabel(t, unit),
                        c.textSecondary,
                        10,
                        Modifier
                            .fillMaxWidth()
                            .offset(y = plotHeight * (1f - (i + 1) / 3f) - 6.dp)
                            .testTag("detail-tick-$i"),
                    )
                }
            }
            Canvas(
                Modifier
                    .testTag("detail-graph")
                    .semantics { contentDescription = graphDescription(d, unit) }
                    .weight(1f)
                    .height(plotHeight),
            ) {
                ticks.indices.forEach { i ->
                    val y = size.height * (1f - (i + 1) / 3f)
                    drawLine(c.border, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                drawLine(c.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                val n = d.series.firstOrNull()?.size ?: return@Canvas
                val stepX = size.width / (n - 1)
                val lower = FloatArray(n)
                live.forEachIndexed { k, row ->
                    val upper = FloatArray(n) { i -> lower[i] + d.series[row][i] }
                    val area =
                        Path().apply {
                            moveTo(0f, size.height * (1 - lower[0] / top))
                            for (i in 0 until n) lineTo(i * stepX, size.height * (1 - upper[i] / top))
                            for (i in n - 1 downTo 0) lineTo(i * stepX, size.height * (1 - lower[i] / top))
                            close()
                        }
                    drawPath(area, fills[k].copy(alpha = 0.6f), style = Fill)
                    val line =
                        Path().apply {
                            for (i in 0 until n) {
                                (
                                    if (i ==
                                        0
                                    ) {
                                        ::moveTo
                                    } else {
                                        ::lineTo
                                    }
                                )(i * stepX, size.height * (1 - upper[i] / top))
                            }
                        }
                    drawPath(line, c.text.copy(alpha = if (k == live.lastIndex) 0.9f else 0.4f), style = Stroke(1.5.dp.toPx()))
                    upper.copyInto(lower)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, top = 4.dp)) {
            Mono(startLabel, c.textSecondary, 10)
            Spacer(Modifier.weight(1f))
            Mono(endLabel, c.textSecondary, 10)
        }
    }
}

@Composable
fun ChunkMap(
    d: Detail,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val unitName = if (d.item.kind == DownloadKind.Torrent) "pieces" else "chunks"
    val done = d.cells.count { it.state == CellState.Done }
    Column(
        modifier
            .fillMaxWidth()
            .testTag("detail-map")
            .semantics { contentDescription = "Chunk map, ${d.cells.size} $unitName, $done done" }
            .background(c.surface, RoundedCornerShape(Radius))
            .border(1.dp, c.border, RoundedCornerShape(Radius))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            d.rows.filter { it.on }.forEach { r ->
                Row(
                    Modifier.testTag("detail-legend-${r.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).background(networkColor(r.kind, r.color, c), CircleShape))
                    Text(r.name, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Mono(
                if (d.groupedPieces > 0) {
                    "${d.groupedPieces} $unitName · ${formatBytes(d.cellBytes)} per square"
                } else {
                    "${d.cells.size} $unitName · ${formatBytes(d.cellBytes)} each"
                },
                c.textSecondary,
                11,
                Modifier.testTag("detail-map-caption"),
            )
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = ((maxWidth + CellGap) / (CellSize + CellGap)).toInt().coerceAtLeast(1)
            val rows = (d.cells.size + cols - 1) / cols
            // A real download has no networks to colour by: its done cells take the accent.
            val coloursByRow = if (d.real) listOf(c.accent) else d.rows.map { networkColor(it.kind, it.color, c) }
            val full = (CellSize + CellGap) * rows - CellGap
            val scrolls = d.item.kind == DownloadKind.Torrent && rows > PIECE_ROWS
            val frame =
                if (scrolls) {
                    Modifier
                        .height(
                            (CellSize + CellGap) * PIECE_ROWS - CellGap,
                        ).testTag("detail-map-scroll")
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
            Box(frame.fillMaxWidth()) {
                Canvas(Modifier.fillMaxWidth().height(full)) {
                    val cell = CellSize.toPx()
                    val gap = CellGap.toPx()
                    val radius = CornerRadius(3.dp.toPx())
                    d.cells.forEachIndexed { i, cellInfo ->
                        val topLeft = Offset((i % cols) * (cell + gap), (i / cols) * (cell + gap))
                        when (cellInfo.state) {
                            CellState.Done -> {
                                drawRoundRect(coloursByRow[cellInfo.network.coerceAtLeast(0)], topLeft, Size(cell, cell), radius)
                            }

                            CellState.Pending -> {
                                drawRoundRect(c.track, topLeft, Size(cell, cell), radius)
                            }

                            CellState.InFlight -> {
                                drawRoundRect(c.track, topLeft, Size(cell, cell), radius)
                                drawRoundRect(c.text.copy(alpha = 0.8f), topLeft, Size(cell, cell), radius, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One segment per enabled network, sized by its share; shares sum to 100. */
@Composable
fun ContributionBar(
    d: Detail,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val parts = d.rows.filter { it.on && it.share > 0 }
    val description = parts.joinToString(", ") { "${it.name} ${it.share}%" }
    Row(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .testTag("detail-contribution")
            .semantics { contentDescription = "Contribution by network, $description" }
            .background(c.track, RoundedCornerShape(5.dp)),
    ) {
        parts.forEachIndexed { i, r ->
            val shape =
                RoundedCornerShape(
                    topStart = if (i == 0) 5.dp else 0.dp,
                    bottomStart = if (i == 0) 5.dp else 0.dp,
                    topEnd = if (i == parts.lastIndex) 5.dp else 0.dp,
                    bottomEnd = if (i == parts.lastIndex) 5.dp else 0.dp,
                )
            Box(Modifier.weight(r.share.toFloat()).height(10.dp).background(networkColor(r.kind, r.color, c), shape))
        }
    }
}
