package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** The sections the number keys go to, in the sidebar's order: Command or Control with 1 to 6. */
internal val SectionKeys: List<Pair<Key, Filter>> =
    listOf(
        Key.One to Filter.All,
        Key.Two to Filter.Status(StatusGroup.ACTIVE),
        Key.Three to Filter.Status(StatusGroup.QUEUED),
        Key.Four to Filter.Status(StatusGroup.PAUSED),
        Key.Five to Filter.Status(StatusGroup.COMPLETED),
        Key.Six to Filter.Status(StatusGroup.FAILED),
    )

internal val IsMac: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

/** Runs the shortcut [event] stands for, if it is one; returns whether it was. None runs under the welcome. */
internal fun AppModel.handleKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || state.value.showWelcome) return false
    if (event.key == Key.Escape) {
        if (state.value.selection.isEmpty()) return false
        clearSelection()
        return true
    }
    // Command on a Mac, Control elsewhere; Alt is left out because Windows reports AltGr as Control and Alt.
    val command = if (IsMac) event.isMetaPressed else event.isCtrlPressed
    if (!command || event.isAltPressed || event.isShiftPressed) return false
    if (event.key == Key.Comma) {
        navigate(Route.Settings)
        return true
    }
    val filter = SectionKeys.firstOrNull { it.first == event.key }?.second ?: return false
    navigate(Route.Downloads(filter))
    return true
}
