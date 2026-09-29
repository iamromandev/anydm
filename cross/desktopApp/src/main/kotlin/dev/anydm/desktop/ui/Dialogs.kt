package dev.anydm.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.anydm.desktop.chrome.NativeDialog
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.store.RemovePrompt

/** The remove dialog, with the web's wording; the keep-files box shows only when the API allows it. */
@Composable
fun RemoveDialog(
    title: String,
    prompt: RemovePrompt,
    onCancel: () -> Unit,
    onConfirm: (deleteFiles: Boolean) -> Unit,
) {
    val t = LocalTokens.current
    var deleteFiles by remember { mutableStateOf(false) }
    NativeDialog(
        prompt.heading,
        prompt.body,
        prompt.confirmLabel,
        destructive = true,
        onCancel = onCancel,
        onConfirm = { onConfirm(if (prompt.canKeepFiles) deleteFiles else true) },
    ) {
        Text(title, color = t.text, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (prompt.canKeepFiles) {
            Row(
                Modifier.toggleable(deleteFiles, role = Role.Checkbox) { deleteFiles = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(deleteFiles, null)
                Text("Also delete the downloaded files", color = t.text)
            }
        }
    }
}

/** Clearing can't be undone, so it asks first (web: the bulk prompt). */
@Composable
fun ClearFinishedDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    NativeDialog(
        "Clear finished downloads?",
        "Completed downloads leave the list and keep their files. Anything that failed is discarded along with whatever it had downloaded.",
        "Clear finished",
        destructive = false,
        onCancel = onCancel,
        onConfirm = onConfirm,
    )
}
