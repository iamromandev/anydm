package dev.anydm.desktop.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import dev.anydm.desktop.theme.LocalTokens

/**
 * A native-shaped sheet over the window (spec: "Dialogs"): a title, one sentence, [extra], then Cancel and
 * the action on the right. ⏎ confirms and Esc cancels; clicks behind it are swallowed.
 */
@Composable
fun NativeDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val t = LocalTokens.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = t.bar,
            border = BorderStroke(1.dp, t.separator),
            shadowElevation = 16.dp,
            modifier =
                Modifier
                    .width(380.dp)
                    .testTag("dialog")
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                onConfirm()
                                true
                            }

                            Key.Escape -> {
                                onCancel()
                                true
                            }

                            else -> {
                                false
                            }
                        }
                    },
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                Text(body, color = t.secondaryText)
                extra()
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    PushButton("Cancel", onClick = onCancel)
                    PushButton(confirmLabel, primary = true, fill = if (destructive) t.destructive else t.selection, onClick = onConfirm)
                }
            }
        }
    }
}
