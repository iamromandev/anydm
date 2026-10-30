package dev.anydm.desktop

import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Links and files the OS hands to the running app as events instead of arguments (#611): on macOS, a click on a `magnet:` link
 * or a `.torrent` arrives as an Apple event, even on the first launch. Events that come before the window model exists are
 * kept and delivered when [attach] gives them somewhere to go.
 */
class OpenEvents {
    private val lock = Any()
    private val waiting = mutableListOf<List<String>>()
    private var sink: ((List<String>) -> Unit)? = null

    fun deliver(arguments: List<String>) {
        val target = synchronized(lock) { sink.also { if (it == null) waiting += arguments } }
        target?.invoke(arguments)
    }

    fun attach(target: (List<String>) -> Unit) {
        val queued =
            synchronized(lock) {
                sink = target
                waiting.toList().also { waiting.clear() }
            }
        queued.forEach(target)
    }

    /** Listens for the OS's open-link and open-file events where there are any; elsewhere it does nothing. */
    fun install() {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { deliver(listOf(openArgument(it.uri))) }
        }
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
                desktop.setOpenFileHandler { event -> deliver(event.files.map(::openArgument)) }
            }
        }
    }
}

internal fun openArgument(uri: URI): String = uri.toString()

internal fun openArgument(file: File): String = file.path
