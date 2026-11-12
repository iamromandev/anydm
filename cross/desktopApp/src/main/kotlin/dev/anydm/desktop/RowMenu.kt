package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RowMenuWidth = 200.dp

/** Top-left of a [w]x[h] card wanted at ([x], [y]), shifted so it stays inside a [maxW]x[maxH] window. */
fun clampMenu(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    maxW: Int,
    maxH: Int,
): IntOffset = IntOffset(x.coerceAtMost(maxW - w).coerceAtLeast(0), y.coerceAtMost(maxH - h).coerceAtLeast(0))

private fun label(a: MenuAction): String =
    when (a) {
        MenuAction.Pause -> "Pause"
        MenuAction.Resume -> "Resume"
        MenuAction.Open -> "Open"
        MenuAction.ShowInFolder -> "Show in folder"
        MenuAction.Remove -> "Remove"
    }

private fun tagOf(a: MenuAction): String =
    when (a) {
        MenuAction.Pause -> "menu-pause"
        MenuAction.Resume -> "menu-resume"
        MenuAction.Open -> "menu-open"
        MenuAction.ShowInFolder -> "menu-folder"
        MenuAction.Remove -> "menu-remove"
    }

/** The context menu card at the pointer. Closing on an outside press and on Esc is done by the Home root. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RowMenu(
    state: RowMenuState,
    actions: List<MenuAction>,
    onAction: (MenuAction) -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val c = colors
    val focus = LocalFocusManager.current
    val first = remember { FocusRequester() }
    LaunchedEffect(state) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    val at = clampMenu(state.x.toInt(), state.y.toInt(), p.width, p.height, constraints.maxWidth, constraints.maxHeight)
                    layout(p.width, p.height) { p.place(at) }
                }.onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .testTag("row-menu")
                .width(RowMenuWidth)
                .background(c.surface, RoundedCornerShape(Radius))
                .border(1.dp, c.border, RoundedCornerShape(Radius))
                .pointerInput(Unit) { detectTapGestures {} }
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                        focus.moveFocus(FocusDirection.Down)
                        true
                    } else if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) {
                        focus.moveFocus(FocusDirection.Up)
                        true
                    } else {
                        false
                    }
                }.padding(vertical = 4.dp),
        ) {
            actions.forEachIndexed { i, a ->
                val danger = a == MenuAction.Remove
                Text(
                    label(a),
                    color = if (danger) c.danger else c.text,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .testTag(tagOf(a))
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onAction(a) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
