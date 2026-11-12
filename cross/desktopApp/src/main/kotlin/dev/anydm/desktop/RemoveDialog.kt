package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RemoveDialog(
    count: Int,
    onRemove: (deleteFiles: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val c = colors
    var deleteFiles by remember { mutableStateOf(false) }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            // Keyboard modality: Tab cannot leave the dialog for the Home controls under the scrim.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .background(Color.Black.copy(alpha = if (c.dark) 0.6f else 0.4f))
            .pointerInput(Unit) { detectTapGestures { onCancel() } }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onCancel()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .testTag("remove-dialog")
                .padding(24.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(Radius))
                .border(1.dp, c.border, RoundedCornerShape(Radius))
                .pointerInput(Unit) { detectTapGestures {} },
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    removeTitle(count),
                    color = c.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("remove-title"),
                )
                Text(
                    "They are removed from the list.",
                    color = c.textSecondary,
                    fontSize = 13.sp,
                )
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckBox(deleteFiles, { deleteFiles = it }, "remove-delete", "Also delete the file")
                    Text(
                        "Also delete the file",
                        color = c.text,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 10.dp).testTag("remove-delete-label"),
                    )
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OutlineButton("Cancel", "remove-cancel", onCancel, modifier = Modifier.focusRequester(cancelFocus))
                PrimaryButton("Remove", "remove-confirm", true) { onRemove(deleteFiles) }
            }
        }
    }
}
