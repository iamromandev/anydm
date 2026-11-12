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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Footer(
    freeBytes: Long,
    slow: Boolean,
    onSlow: (Boolean) -> Unit,
    unit: SpeedUnit,
    onUnit: (SpeedUnit) -> Unit,
    dark: Boolean,
    onTheme: () -> Unit,
) {
    val c = colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${formatBytes(freeBytes)} free",
            color = c.textSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.testTag("free-space"),
        )
        Spacer(Modifier.weight(1f))
        Text("Slow mode", color = c.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        AnydmSwitch(slow, onSlow, "slow-mode")
        Spacer(Modifier.width(16.dp))
        Row(
            Modifier.border(1.dp, c.border, RoundedCornerShape(Radius)).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Tags are used by LimitsDialogTest ("unit-mbps" is the Mbps segment, "unit-mbps-bytes" the MB/s one); keep them stable.
            Segment("MB/s", unit == SpeedUnit.MBps, "unit-mbps-bytes") { onUnit(SpeedUnit.MBps) }
            Segment("Mbps", unit == SpeedUnit.Mbps, "unit-mbps") { onUnit(SpeedUnit.Mbps) }
        }
        Spacer(Modifier.width(12.dp))
        IconBox(
            "theme-toggle",
            if (dark) "Switch to light theme" else "Switch to dark theme",
            onTheme,
            bordered = true,
        ) { ThemeIcon(it, dark) }
    }
}

@Composable
private fun Segment(
    label: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val c = colors
    Box(
        Modifier
            .testTag(tag)
            .semantics { contentDescription = "$label ${if (active) "selected" else ""}".trim() }
            .background(if (active) c.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (active) c.onAccent else c.textSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
