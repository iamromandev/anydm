package dev.anydm.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/** What the add-links input made of its text. */
internal sealed interface LinkInputRead {
    data class Links(
        val links: List<String>,
    ) : LinkInputRead

    data class Invalid(
        val message: String,
    ) : LinkInputRead
}

private const val MAX_LINES_SHOWN = 2

/** The links in [text], one per line, read as launch arguments are ([LinkRouter]); one line that is not a link makes the whole input invalid. */
internal fun readLinkInput(text: String): LinkInputRead {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return LinkInputRead.Invalid("Paste a link or a magnet first.")
    lines.firstOrNull { LinkRouter.route(listOf(it)).links.isEmpty() }?.let {
        return LinkInputRead.Invalid("Not a link anydm can add: ${it.take(60)}")
    }
    return LinkInputRead.Links(LinkRouter.route(lines).links)
}

/** The add-links input above the queue: links or magnets, one per line, sent to the add review like the Add dialog's. */
@Composable
internal fun LinkInput(model: AppModel) {
    val draft by model.linkDraft.collectAsState()
    // The field's own value (cursor, IME composition) follows the draft the model holds.
    var value by remember { mutableStateOf(TextFieldValue(draft)) }
    if (value.text != draft) value = TextFieldValue(draft)
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        when (val read = readLinkInput(value.text)) {
            is LinkInputRead.Links -> {
                model.openAdd(read.links)
                model.linkDraft.value = ""
                value = TextFieldValue("")
                error = null
            }

            is LinkInputRead.Invalid -> {
                error = read.message
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
            // A plain field, one line high: it grows with typed lines, up to two, so the list keeps its room.
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    model.linkDraft.value = it.text
                    error = null
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                minLines = 1,
                maxLines = MAX_LINES_SHOWN,
                modifier =
                    Modifier
                        .weight(1f)
                        .onFocusChanged { model.typing = it.isFocused }
                        // Enter adds; Shift+Enter is left to the field, which makes a new line. Not while an IME is composing.
                        .onPreviewKeyEvent { event ->
                            if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed) {
                                if (event.type == KeyEventType.KeyDown && value.composition == null) submit()
                                true
                            } else {
                                false
                            }
                        }.testTag("link-input"),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .border(
                                Dp.Hairline,
                                if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                                MaterialTheme.shapes.small,
                            ).padding(horizontal = Space.sm, vertical = Space.sm),
                    ) {
                        if (value.text.isEmpty()) {
                            Text(
                                "Paste links or magnets here, one per line",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("link-input-hint"),
                            )
                        }
                        inner()
                    }
                },
            )
            Button(onClick = ::submit, modifier = Modifier.testTag("link-input-add")) { Text("Add") }
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("link-input-error"),
            )
        }
    }
}
