package dev.anydm.desktop.list

import androidx.compose.ui.input.key.Key

/** What the keyboard or the menu bar asks the list to do (spec: "Selection and keys"). */
enum class Command {
    PAUSE_RESUME,
    OPEN,
    SAVE,
    REMOVE,
    COPY,
    PASTE,
    FOCUS_LINK,
    OPEN_TORRENT,
    SETTINGS,
    RETRY,
    SELECT_ALL,
    CLEAR,
    UP,
    DOWN,
    EXTEND_UP,
    EXTEND_DOWN,
}

/**
 * The command a key press means to the list, or `null` to let it through. The menu bar's accelerators
 * (⌘L, ⌘O, ⌘,, ⌘S, ⌘R) aren't here, so they never fire twice; a focused text field keeps every key here.
 */
fun commandFor(
    key: Key,
    meta: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    mac: Boolean,
    inText: Boolean,
): Command? {
    if (inText) return null
    val mod = if (mac) meta else ctrl
    return when {
        mod && key == Key.A -> Command.SELECT_ALL
        mod && key == Key.C -> Command.COPY
        mod && key == Key.V -> Command.PASTE
        mod || meta || ctrl -> null
        key == Key.Spacebar -> Command.PAUSE_RESUME
        key == Key.Enter -> Command.OPEN
        key == Key.Backspace || key == Key.Delete -> Command.REMOVE
        key == Key.Escape -> Command.CLEAR
        key == Key.DirectionUp -> if (shift) Command.EXTEND_UP else Command.UP
        key == Key.DirectionDown -> if (shift) Command.EXTEND_DOWN else Command.DOWN
        else -> null
    }
}
