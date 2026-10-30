package dev.anydm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.anydm.engine.queue.RemoveFiles

@Composable
internal fun Dialogs(
    dialog: Dialog?,
    model: AppModel,
) {
    when (dialog) {
        null -> Unit
        is Dialog.ConfirmRemove -> RemoveDialog(dialog, model)
        is Dialog.Relink -> RelinkDialog(dialog, model)
    }
}

@Composable
private fun RemoveDialog(
    dialog: Dialog.ConfirmRemove,
    model: AppModel,
) {
    val count = dialog.ids.size
    AlertDialog(
        onDismissRequest = model::closeDialog,
        title = { Text(if (count == 1) "Remove this download?" else "Remove $count downloads?") },
        text = { Text("Its files can stay where they are or go to the trash.") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.confirmRemove(RemoveFiles.KEEP) }, modifier = Modifier.testTag("remove-keep")) {
                    Text("Remove, keep files")
                }
                Button(onClick = { model.confirmRemove(RemoveFiles.TRASH) }, modifier = Modifier.testTag("remove-trash")) {
                    Text("Remove and move to trash")
                }
            }
        },
        dismissButton = { TextButton(onClick = model::closeDialog, modifier = Modifier.testTag("remove-cancel")) { Text("Cancel") } },
        modifier = Modifier.testTag("remove-dialog"),
    )
}

@Composable
private fun RelinkDialog(
    dialog: Dialog.Relink,
    model: AppModel,
) {
    var url by remember(dialog.id, dialog.mirrorId) { mutableStateOf(dialog.url) }
    AlertDialog(
        onDismissRequest = model::closeDialog,
        title = { Text("Relink download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A new address for the same file; the download goes on from where it stopped.")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    modifier = Modifier.testTag("relink-url"),
                )
                dialog.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("relink-error"))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { model.confirmRelink(url) },
                enabled = url.isNotBlank() && !dialog.busy,
                modifier = Modifier.testTag("relink-ok"),
            ) {
                Text("Relink")
            }
        },
        dismissButton = { TextButton(onClick = model::closeDialog, modifier = Modifier.testTag("relink-cancel")) { Text("Cancel") } },
        modifier = Modifier.testTag("relink-dialog"),
    )
}
