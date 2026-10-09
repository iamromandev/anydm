package dev.anydm.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.anydm.desktop.chrome.NativeDialog
import dev.anydm.model.CategoryDto
import dev.anydm.model.Task

/** Pick the category to move a row to. Its choices sit in the dialog's extra slot; Move sends the one picked. */
@Composable
fun CategoryPickerDialog(
    task: Task,
    categories: List<CategoryDto>,
    onCancel: () -> Unit,
    onMove: (String) -> Unit,
) {
    var chosen by remember(task.id) { mutableStateOf(task.category?.id) }
    NativeDialog(
        title = "Move to category",
        body = task.title,
        confirmLabel = "Move",
        destructive = false,
        onCancel = onCancel,
        // Moving to the category the row is already in is a no-op: close without a call.
        onConfirm = { chosen?.takeIf { it != task.category?.id }?.let(onMove) ?: onCancel() },
    ) {
        Column {
            categories.forEach { category ->
                Choice(category.name, selected = chosen == category.id) { chosen = category.id }
            }
        }
    }
}
