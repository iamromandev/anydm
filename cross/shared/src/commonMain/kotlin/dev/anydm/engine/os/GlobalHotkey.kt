package dev.anydm.engine.os

/** Whether the global hotkey works, and if not why not (OSI-06), for a UI to explain. */
sealed interface HotkeyStatus {
    /** Switched off, or not asked for. */
    data object Off : HotkeyStatus

    /** Listening: the key combination queues the link on the clipboard. */
    data object Active : HotkeyStatus

    /** The OS wants the user to allow it first (macOS Accessibility). It works once they do. */
    data object NeedsPermission : HotkeyStatus

    /** This system can't do it, and no setting changes that (Linux under Wayland, no library for this CPU). */
    data class Unsupported(
        val reason: String,
    ) : HotkeyStatus

    /** It should have worked and did not. */
    data class Failed(
        val reason: String,
    ) : HotkeyStatus
}

/**
 * Listens for the hotkey system-wide (OSI-06), desktop only. It reports only that the combination was pressed: no other key
 * is passed on, stored or logged.
 */
interface GlobalHotkey {
    /**
     * Starts listening. Each press of the combination calls [onPress], from any thread, once per press and without blocking.
     * Returns [HotkeyStatus.Active], or why it can't (nothing is left running then). Throws nothing it can name.
     */
    suspend fun start(onPress: () -> Unit): HotkeyStatus

    /** Stops listening and lets go of the keys. Stopping what is not started changes nothing. */
    suspend fun stop()
}
