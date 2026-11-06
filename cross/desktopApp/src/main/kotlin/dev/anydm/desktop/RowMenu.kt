package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private val MenuWidth = 208.dp

// Where each action sits in the menu, in sections: the state commands, the file ones, the queue moves, then Remove.
private val sections: List<List<Pair<MenuAction, String>>> =
    listOf(
        listOf(MenuAction.PAUSE to "Pause", MenuAction.RESUME to "Resume", MenuAction.RETRY to "Retry"),
        listOf(MenuAction.OPEN to "Open", MenuAction.REVEAL to "Show in folder", MenuAction.COPY_LINK to "Copy link"),
        listOf(MenuAction.MOVE_TOP to "Move to top", MenuAction.MOVE_UP to "Move up", MenuAction.MOVE_DOWN to "Move down"),
        listOf(MenuAction.REMOVE to "Remove"),
    )

/** The popup that [HomeScreen] opens at the pointer ([at], in window coordinates) on a right-click. */
@Composable
fun RowMenu(
    menu: RowMenu,
    at: Offset,
    onAction: (MenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val position =
        remember(at) {
            object : PopupPositionProvider {
                // The menu's corner goes where the pointer was, moved back inside the window when it would run out of it.
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ) = IntOffset(
                    at.x
                        .toInt()
                        .coerceAtMost(windowSize.width - popupContentSize.width)
                        .coerceAtLeast(0),
                    at.y
                        .toInt()
                        .coerceAtMost(windowSize.height - popupContentSize.height)
                        .coerceAtLeast(0),
                )
            }
        }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .width(MenuWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgSecondary)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(6.dp)
                .focusRequester(focus)
                .focusTarget()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val shown = sections.map { section -> section.filter { (action, _) -> action in menu.actions } }.filter { it.isNotEmpty() }
            shown.forEachIndexed { i, section ->
                if (i > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(0.5.dp)
                            .background(colors.border),
                    )
                }
                for ((action, label) in section) {
                    val enabled =
                        when (action) {
                            MenuAction.OPEN -> menu.openable
                            MenuAction.REVEAL -> menu.locatable
                            else -> true
                        }
                    MenuItem(label, enabled, danger = action == MenuAction.REMOVE, tag = "menu-${action.name}") { onAction(action) }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    enabled: Boolean,
    danger: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = Plexo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .semantics { testTag = tag }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Txt(
            label,
            Plexo.type.body,
            color =
                when {
                    !enabled -> colors.textTertiary
                    danger -> colors.danger
                    else -> colors.text
                },
        )
    }
}
