package dev.anydm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anydm.store.RetryTone

/** One row of the list, drawn from its [CardView]. */
@Composable
fun TaskCard(
    view: CardView,
    saving: String?,
    onAction: (CardAction) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(view.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (view.meta.isNotEmpty()) Text(view.meta, style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(onClick = {}, label = { Text(view.status) })
            }
            LinearProgressIndicator(progress = { view.progress }, modifier = Modifier.fillMaxWidth())
            if (view.detail.isNotEmpty()) Text(view.detail, style = MaterialTheme.typography.bodySmall)
            if (saving != null) Text(saving, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            view.retry?.let { retry ->
                val colour = if (retry.tone == RetryTone.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                Text(retry.headline, color = colour, style = MaterialTheme.typography.bodySmall)
                retry.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                view.actions.forEach { action -> TextButton(onClick = { onAction(action) }) { Text(actionLabel(action)) } }
            }
        }
    }
}
