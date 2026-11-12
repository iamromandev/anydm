package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SelectionBar(
    count: Int,
    canPause: Boolean,
    canResume: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
) {
    val c = colors
    Column(Modifier.fillMaxWidth().testTag("selection-bar").background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                selectionLabel(count),
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.testTag("selection-count").weight(1f),
            )
            OutlineButton("Pause", "bar-pause", onPause, enabled = canPause)
            OutlineButton("Resume", "bar-resume", onResume, enabled = canResume)
            OutlineButton("Remove", "bar-remove", onRemove, danger = true)
            OutlineButton("Clear", "bar-clear", onClear)
        }
        Divider()
    }
}
