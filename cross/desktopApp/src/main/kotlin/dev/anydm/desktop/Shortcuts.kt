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
    val current = state.value
    if (event.type != KeyEventType.KeyDown || current.showWelcome) return false
    // Command on a Mac, Control elsewhere; Alt is left out because Windows reports AltGr as Control and Alt.
    val command = if (IsMac) event.isMetaPressed else event.isCtrlPressed
    // The add dialog takes Esc and Command/Control+Enter; its fields keep every other key.
    current.adding?.let { add ->
        when {
            event.key == Key.Escape -> closeAdd()
            command && event.key == Key.Enter -> add.add()
            else -> return false
        }
        return true
    }
    // A dialog and the filter field keep their keys.
    if (current.dialog != null) return false
    if (event.key == Key.Escape) {
        if (current.selection.isEmpty()) return false
        clearSelection()
        return true
    }
    if (event.isAltPressed) return false
    if (!command) return !typing && listKey(event)
    if (event.isShiftPressed) return false
    // The list's own commands act on what the list shows, so only in the downloads section and not while typing.
    val inList = current.route is Route.Downloads && !typing
    when (event.key) {
        Key.Comma -> navigate(Route.Settings)
        Key.N -> openAdd()
        Key.A -> if (inList) selectAll() else return false
        Key.R -> if (inList) run(JobCommand.RETRY) else return false
        Key.Enter -> if (inList) run(JobCommand.OPEN) else return false
        else -> navigate(Route.Downloads(SectionKeys.firstOrNull { it.first == event.key }?.second ?: return false))
    }
    return true
}

// The keys without Command or Control: arrows move the selection, Space pauses or resumes, Delete removes.
private fun AppModel.listKey(event: KeyEvent): Boolean {
    if (state.value.route !is Route.Downloads) return false
    when (event.key) {
        Key.DirectionUp -> {
            moveSelection(-1, extend = event.isShiftPressed)
        }

        Key.DirectionDown -> {
            moveSelection(1, extend = event.isShiftPressed)
        }

        Key.Spacebar -> {
            val command = listOf(JobCommand.PAUSE, JobCommand.RESUME).firstOrNull { state.value.enabled(it) } ?: return false
            run(command)
        }

        Key.Delete, Key.Backspace -> {
            run(JobCommand.REMOVE)
        }

        else -> {
            return false
        }
    }
    return true
}
