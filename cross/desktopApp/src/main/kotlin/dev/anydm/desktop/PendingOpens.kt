package dev.anydm.desktop

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Links and files the app was opened with (first launch, a second launch, an OS open event) and the menu's "Add…", on their way
 * to the new-download dialog. They wait here until the window takes them from [requests]: one request is the links or `.torrent`
 * paths to open, or none for a blank dialog.
 */
class PendingOpens {
    private val waiting = Channel<List<String>>(Channel.UNLIMITED)

    /** The arguments an open brought; none (a plain launch) is not an open. */
    fun offer(arguments: List<String>) {
        if (arguments.isEmpty()) return
        waiting.trySend(arguments)
    }

    /** A blank dialog: File > Add. */
    fun openBlank() {
        waiting.trySend(emptyList())
    }

    val requests: ReceiveChannel<List<String>> get() = waiting
}
