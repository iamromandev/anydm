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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal const val BINDING_TITLE = "Only your default network can be used"

/**
 * Tells the user that this system lets no app send through a chosen network, so every connection uses the default one.
 * Escape, Enter or Got it dismiss it ([onDismiss]); a click outside does not, as it is a notice and not a question.
 */
@Composable
fun BindingDialog(
    keys: WindowKeys,
    onDismiss: () -> Unit,
) {
    BlockWindowKeys(keys)
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .focusRequester(focus)
            .focusTarget()
            .testTag("binding-dialog")
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Escape || it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }.pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .semantics { contentDescription = BINDING_TITLE }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt(BINDING_TITLE, Plexo.type.menu)
            Txt(
                "Apps can't send through a specific network on this system, so all connections use the default network. " +
                    "Other networks show no ping and add no speed.",
                Plexo.type.hint,
                color = colors.textSecondary,
                maxLines = 6,
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                PButton("Got it", onDismiss, modifier = Modifier.testTag("binding-ok"), kind = ButtonKind.PRIMARY)
            }
        }
    }
}
