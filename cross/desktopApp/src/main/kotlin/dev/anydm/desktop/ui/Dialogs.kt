package dev.anydm.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import dev.anydm.store.RemovePrompt

/** The remove dialog, with the web's wording; the keep-files box shows only when the API allows it. */
@Composable
fun RemoveDialog(
    title: String,
    prompt: RemovePrompt,
    onCancel: () -> Unit,
    onConfirm: (deleteFiles: Boolean) -> Unit,
) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(prompt.heading) },
        text = {
            Column {
                Text(title)
                Text(prompt.body)
                if (prompt.canKeepFiles) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteFiles, { deleteFiles = it })
                        Text("Also delete the downloaded files")
                    }
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(if (prompt.canKeepFiles) deleteFiles else true) }) { Text(prompt.confirmLabel) } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

/** Clearing can't be undone, so it asks first (web: the bulk prompt). */
@Composable
fun ClearFinishedDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Clear finished downloads?") },
        text = {
            Text(
                "Completed downloads leave the list and keep their files. " +
                    "Anything that failed is discarded along with whatever it had downloaded.",
            )
        },
        confirmButton = { TextButton(onConfirm) { Text("Clear finished") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}
