package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The keyboard shortcuts list, generated from the command table. A modal like [RemoveDialog]: Esc, the button or a click outside closes it.
 * It takes no focus: Home swallows the keys while it is open and Esc closes it, so the control that had the focus keeps it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShortcutsOverlay(
    platform: Platform,
    onClose: () -> Unit,
) {
    val c = colors
    val entries = remember(platform) { overlayEntries(platform) }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("shortcuts-scrim")
            // Nothing in the overlay takes the focus, so a click on Close cannot pull it away from the control below.
            .focusProperties { canFocus = false }
            .background(Color.Black.copy(alpha = if (c.dark) 0.6f else 0.4f))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .testTag("shortcuts-overlay")
                .padding(24.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(Radius))
                .border(1.dp, c.border, RoundedCornerShape(Radius))
                .pointerInput(Unit) { detectTapGestures {} },
        ) {
            Text(
                "Keyboard shortcuts",
                color = c.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Divider()
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                for (group in Group.entries) {
                    Text(
                        group.title.uppercase(),
                        color = c.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    for (e in entries.filter { it.group == group }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical = 4.dp,
                                ).semantics(mergeDescendants = true) {}
                                .testTag("shortcut-row-${e.label}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(e.label, color = c.text, fontSize = 14.sp, maxLines = 1)
                            Text(
                                e.shortcut,
                                color = c.textSecondary,
                                fontSize = 13.sp,
                                // Not monospace: that font has no glyph for the Backspace sign.
                                fontFamily = FontFamily.Default,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlineButton("Close", "shortcuts-close", onClose)
            }
        }
    }
}
