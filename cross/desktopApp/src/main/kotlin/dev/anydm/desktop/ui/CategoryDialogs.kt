package dev.anydm.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.desktop.chrome.FormField
import dev.anydm.desktop.chrome.Glyphs
import dev.anydm.desktop.chrome.NativeDialog
import dev.anydm.desktop.chrome.PushButton
import dev.anydm.desktop.chrome.ToolButton
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.model.CategoryDto
import dev.anydm.model.Task
import dev.anydm.store.CategoryState
import dev.anydm.store.CategoryStore
import kotlinx.coroutines.launch

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

/**
 * The Categories manager, without its window: a row per category to rename, repoint, reorder or delete,
 * then the add row. Stateless but for each row's drafts, so it is tested with plain state and lambdas.
 */
@Composable
fun CategoriesContent(
    state: CategoryState,
    onCreate: (String, String) -> Unit,
    onUpdate: (String, String?, String?) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (CategoryDto) -> Unit,
) {
    val t = LocalTokens.current
    var newName by remember { mutableStateOf("") }
    var newFolder by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "New downloads save in their category's folder, under the download folder. Changing a folder moves nothing already there.",
            color = t.secondaryText,
            fontSize = 12.sp,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.categories.forEachIndexed { index, category ->
                CategoryRow(category, index, state.categories.size, onUpdate, onMove, onDelete)
            }
        }
        HorizontalDivider(color = t.separator)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { FormField("Name", newName, { newName = it }, tag = "category-new-name") }
            Box(Modifier.weight(1f)) {
                FormField(
                    "Folder",
                    newFolder,
                    { newFolder = it },
                    tag = "category-new-folder",
                    placeholder = "blank for the download folder",
                )
            }
            PushButton("Add", enabled = newName.isNotBlank()) {
                onCreate(newName.trim(), newFolder.trim())
                newName = ""
                newFolder = ""
            }
        }
        state.error?.let { Text(it, color = t.error, fontSize = 12.sp) }
    }
}

/** One category's row: its drafts, Save, the order arrows, its count, and Delete unless it is built in. */
@Composable
private fun CategoryRow(
    category: CategoryDto,
    index: Int,
    total: Int,
    onUpdate: (String, String?, String?) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (CategoryDto) -> Unit,
) {
    val t = LocalTokens.current
    // Drafts start from the category, and start over whenever it changes (after a save, or a reload).
    var name by remember(category) { mutableStateOf(category.name) }
    var folder by remember(category) { mutableStateOf(category.folder) }
    val nameChanged = name.trim() != category.name
    val folderChanged = !category.builtin && folder.trim() != category.folder
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f)) { FormField("Name", name, { name = it }, tag = "category-name-${category.id}") }
        Box(Modifier.weight(1f)) {
            FormField(
                "Folder",
                folder,
                { folder = it },
                tag = "category-folder-${category.id}",
                enabled = !category.builtin,
                placeholder = "blank for the download folder",
            )
        }
        Text("${category.count}", fontSize = 12.sp, color = t.secondaryText, modifier = Modifier.width(28.dp))
        PushButton("Save", enabled = (nameChanged || folderChanged) && name.isNotBlank()) {
            onUpdate(
                category.id,
                if (nameChanged) name.trim() else null,
                if (folderChanged) folder.trim() else null,
            )
        }
        ToolButton(Glyphs.ChevronUp, "Move ${category.name} up", enabled = index > 0) { onMove(index, -1) }
        ToolButton(Glyphs.ChevronDown, "Move ${category.name} down", enabled = index < total - 1) { onMove(index, 1) }
        if (!category.builtin) {
            ToolButton(Glyphs.Remove, "Delete ${category.name}") { onDelete(category) }
        }
    }
}

/**
 * The Categories window, opened from Settings. It loads the list when it opens; a delete asks first, and
 * says the folder and its files stay on disk. Esc closes it.
 */
@Composable
fun CategoriesWindow(
    store: CategoryStore,
    onClose: () -> Unit,
) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<CategoryDto?>(null) }
    LaunchedEffect(Unit) { store.load() }
    Window(
        onCloseRequest = onClose,
        title = "Categories",
        state = rememberWindowState(size = DpSize(520.dp, 560.dp)),
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        },
    ) {
        DesktopTheme {
            Box(Modifier.fillMaxSize().background(LocalTokens.current.content)) {
                CategoriesContent(
                    state = state,
                    onCreate = { name, folder -> scope.launch { store.create(name, folder) } },
                    onUpdate = { id, name, folder -> scope.launch { store.update(id, name, folder) } },
                    onMove = { index, delta -> scope.launch { store.move(index, delta) } },
                    onDelete = { deleting = it },
                )
                deleting?.let { category ->
                    NativeDialog(
                        title = "Delete ${category.name}?",
                        body = "Its folder and files stay on disk.",
                        confirmLabel = "Delete",
                        destructive = true,
                        onCancel = { deleting = null },
                        onConfirm = {
                            deleting = null
                            scope.launch { store.delete(category.id) }
                        },
                    )
                }
            }
        }
    }
}
