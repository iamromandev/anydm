package dev.anydm.desktop.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.api.Duplicate
import dev.anydm.desktop.theme.LocalTokens

/** What an add says when the list already holds it. */
fun duplicateMessage(held: Duplicate): String {
    val status = if (held.status.isEmpty()) "" else " (${held.status})"
    val playlist = if (held.collectionId == null) "" else ", in ${held.collectionTitle?.takeIf { it.isNotEmpty() } ?: "a playlist"}"
    return "Already in your list: ${held.title}$status$playlist"
}

/**
 * The strip under the toolbar for an add the list already holds: [onOpen] selects that row, and
 * [onAddAnyway] (left out for a torrent, which the API holds once) adds a second copy.
 */
@Composable
fun DuplicateStrip(
    held: Duplicate,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onAddAnyway: (() -> Unit)? = null,
) {
    val t = LocalTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .background(t.bar)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(t.accent))
        Text(duplicateMessage(held), Modifier.weight(1f), color = t.text, maxLines = 2)
        PushButton("Open", primary = true, onClick = onOpen)
        onAddAnyway?.let { PushButton("Add anyway", onClick = it) }
        Text(
            "✕",
            Modifier.clip(RoundedCornerShape(5.dp)).clickable(onClick = onDismiss).padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 11.sp,
            color = t.secondaryText,
        )
    }
}
