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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionHeader(
    key: String,
    title: String,
    count: Int,
    allSelected: Boolean,
    onSelectAll: (Boolean) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val c = colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckBox(allSelected, onSelectAll, "select-all-$key", "Select all $title")
            Spacer(Modifier.width(12.dp))
            Text(
                "$title $count",
                color = c.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (onClear != null) {
                Text(
                    "Clear finished list",
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .testTag("clear-finished")
                            .clickable(role = Role.Button, onClick = onClear)
                            .padding(4.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
    }
}

@Composable
fun DownloadRow(
    item: DownloadItem,
    selected: Boolean,
    unit: SpeedUnit,
    onSelect: (Boolean) -> Unit,
    onTogglePause: () -> Unit,
) {
    val c = colors
    val active = item.status != DownloadStatus.Finished
    Row(
        Modifier
            .testTag("row-${item.id}")
            .fillMaxWidth()
            .background(if (selected) c.accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(selected, onSelect, "select-${item.id}", "Select ${item.name}")
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(40.dp)
                .background(c.surface, RoundedCornerShape(Radius))
                .border(1.dp, c.border, RoundedCornerShape(Radius)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badgeFor(item.name),
                color = c.textSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    color = c.text,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (item.status == DownloadStatus.Paused) "Paused" else formatSpeed(item.bytesPerSec, unit),
                        color = c.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.testTag("speed-${item.id}"),
                    )
                }
            }
            if (active) {
                Spacer(Modifier.height(5.dp))
                ProgressBar(progress(item))
            }
            Spacer(Modifier.height(5.dp))
            Text(
                details(item),
                color = c.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (active) {
            val paused = item.status == DownloadStatus.Paused
            IconBox("pause-${item.id}", if (paused) "Resume" else "Pause", onTogglePause) {
                if (paused) PlayIcon(it) else PauseIcon(it)
            }
        } else {
            Spacer(Modifier.size(32.dp))
        }
        IconBox("chevron-${item.id}", "Open ${item.name}", {}) { ChevronIcon(it) }
    }
}

private fun details(item: DownloadItem): String =
    if (item.status == DownloadStatus.Finished) {
        formatBytes(item.totalBytes)
    } else {
        val pct = percent(item)
        val base = "$pct% · ${formatBytes(item.doneBytes)} of ${formatBytes(item.totalBytes)}"
        if (item.status == DownloadStatus.Downloading && item.bytesPerSec > 0) {
            "$base · ${formatEta((item.totalBytes - item.doneBytes).coerceAtLeast(0) / item.bytesPerSec)}"
        } else {
            base
        }
    }

@Composable
private fun ProgressBar(fraction: Float) {
    val c = colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.track),
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(c.accent))
    }
}
