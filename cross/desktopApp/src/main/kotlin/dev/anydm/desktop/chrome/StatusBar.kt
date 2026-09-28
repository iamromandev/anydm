package dev.anydm.desktop.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.desktop.ui.connectionLabel
import dev.anydm.desktop.ui.formatBytes
import dev.anydm.desktop.ui.formatSpeed
import dev.anydm.model.DiskDto
import dev.anydm.model.Task
import dev.anydm.store.Connection

/** The 22 dp bar under the list: speeds, the connection, free disk, and what's saving now. */
@Composable
fun StatusBar(
    tasks: List<Task>,
    connection: Connection,
    disk: DiskDto?,
    saving: String?,
) {
    val t = LocalTokens.current
    HorizontalDivider(color = t.separator)
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(t.bar)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("↓ ${formatSpeed(tasks.sumOf { it.downloadSpeed })}", fontSize = 11.sp, color = t.secondaryText)
        Text("↑ ${formatSpeed(tasks.sumOf { it.uploadSpeed })}", fontSize = 11.sp, color = t.secondaryText)
        Text(connectionLabel(connection), fontSize = 11.sp, color = t.secondaryText)
        disk?.let { Text("${formatBytes(it.freeBytes)} free", fontSize = 11.sp, color = t.secondaryText) }
        Spacer(Modifier.weight(1f))
        saving?.let { Text(it, fontSize = 11.sp, color = t.accent) }
    }
}
