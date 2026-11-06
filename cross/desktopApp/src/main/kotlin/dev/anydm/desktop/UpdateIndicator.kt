package dev.anydm.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * The header's update button: an icon and, for some states, a word beside it; its tooltip repeats the [UpdateIndicatorModel] label.
 * A click opens the menu of what can be done now. [onMenuOpen] tells the window to hold its shortcuts while the menu is up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UpdateIndicator(
    session: UpdateSession,
    onMenuOpen: (Boolean) -> Unit = {},
) {
    val colors = Plexo.colors
    val state by session.controls.state.collectAsState()
    val auto by session.controls.autoUpdate.collectAsState()
    val button = UpdateIndicatorModel.button(state, auto)
    var open by remember { mutableStateOf(false) }
    DisposableEffect(open) {
        onMenuOpen(open)
        onDispose { onMenuOpen(false) }
    }
    val tint =
        when (state) {
            is UpdateState.Error -> colors.danger
            UpdateState.Idle, UpdateState.Checking -> colors.textTertiary
            else -> colors.accent
        }
    Box {
        TooltipArea(
            tooltip = {
                Box(Modifier.clip(RoundedCornerShape(5.dp)).background(colors.bgTertiary).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Txt(button.label, Plexo.type.detail, maxLines = 2)
                }
            },
        ) {
            Row(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(PlexoRadius))
                    .clickable(role = Role.Button) { open = true }
                    .padding(horizontal = 8.dp)
                    .testTag("update-button")
                    .semantics { contentDescription = button.label },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (button.icon) {
                    UpdateIcon.UP -> GlyphIcon(Glyph.ARROW_UP, tint, size = 14.dp)
                    UpdateIcon.ALERT -> GlyphIcon(Glyph.ALERT, tint, size = 14.dp)
                    UpdateIcon.SPINNER -> Spinner(tint)
                }
                button.text?.let { Txt(it, Plexo.type.button, color = tint) }
            }
        }
        if (open) {
            UpdateMenu(state, auto, session, onDismiss = { open = false })
        }
    }
}

@Composable
private fun Spinner(tint: Color) {
    val turn = rememberInfiniteTransition(label = "update-spinner")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), "angle")
    GlyphIcon(Glyph.SPINNER, tint, Modifier.rotate(angle), size = 14.dp)
}

@Composable
private fun UpdateMenu(
    state: UpdateState,
    auto: Boolean,
    session: UpdateSession,
    onDismiss: () -> Unit,
) {
    val colors = Plexo.colors
    val controls = session.controls
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, with(LocalDensity.current) { 36.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(248.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgSecondary)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(6.dp)
                .testTag("update-menu"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (item in UpdateIndicatorModel.items(state)) {
                val run: () -> Unit = {
                    onDismiss()
                    when (item.action) {
                        UpdateAction.RESTART -> session.restart()
                        UpdateAction.DOWNLOAD -> controls.download()
                        UpdateAction.WEBSITE -> (state as? UpdateState.Error)?.pageUrl?.let(session::openWebsite)
                        UpdateAction.CHECK -> controls.check()
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .testTag(item.action.tag)
                        .clickable(enabled = item.enabled, role = Role.Button, onClick = run)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Txt(item.text, Plexo.type.body, color = if (item.enabled) colors.text else colors.textTertiary)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.border))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .testTag(UpdateIndicatorModel.AUTO_TAG)
                    .toggleable(value = auto, role = Role.Checkbox) { controls.setAutoUpdate(it) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(16.dp)) {
                    if (auto) GlyphIcon(Glyph.CHECK, colors.accent, size = 16.dp)
                }
                Txt(UpdateIndicatorModel.AUTO_LABEL, Plexo.type.body)
            }
        }
    }
}

/** The dialog [session] has open, if any: a result of a check the user asked for, or the question before a restart. */
@Composable
fun UpdateDialogs(
    session: UpdateSession,
    keys: WindowKeys,
) {
    val notice by session.notice.collectAsState()
    when (val now = notice ?: return) {
        UpdateNotice.UpToDate -> {
            NoticeDialog("You're up to date", "This is the latest version of anydm.", keys, session::dismiss)
        }

        UpdateNotice.CheckFailed -> {
            NoticeDialog(
                "Couldn't check for updates",
                "anydm could not reach the release server. Check your connection and try again.",
                keys,
                session::dismiss,
            )
        }

        is UpdateNotice.OpenFailed -> {
            NoticeDialog(
                "Couldn't open the page",
                "${now.message} Open the release page from your browser instead.",
                keys,
                session::dismiss,
            )
        }

        is UpdateNotice.ConfirmRestart -> {
            BlockWindowKeys(keys)
            Box(Modifier.fillMaxSize().testTag("update-confirm-dialog")) {
                val count = now.activeDownloads
                ConfirmDialog(
                    title = "Restart to update?",
                    body =
                        (if (count == 1) "1 download is active and will pause" else "$count downloads are active and will pause") +
                            ". anydm quits so the installer can run; start it again to resume.",
                    confirmText = "Restart",
                    onConfirm = session::confirmRestart,
                    onCancel = session::dismiss,
                )
            }
        }
    }
}

/** A message with one button; Escape, Enter or OK close it. */
@Composable
private fun NoticeDialog(
    title: String,
    body: String,
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
            .testTag("update-notice-dialog")
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
                .width(400.dp)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .semantics { contentDescription = title }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt(title, Plexo.type.menu)
            Txt(body, Plexo.type.hint, color = colors.textSecondary, maxLines = 4)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                PButton("OK", onDismiss, modifier = Modifier.testTag("update-notice-ok"), kind = ButtonKind.PRIMARY)
            }
        }
    }
}
