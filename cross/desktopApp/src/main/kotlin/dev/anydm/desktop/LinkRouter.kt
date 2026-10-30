package dev.anydm.desktop

import dev.anydm.engine.os.LinkIntake

/** What a launch asks for: the links to add, and whether to start without showing the window. */
data class LaunchRoute(
    val links: List<String>,
    val hidden: Boolean,
)

/**
 * Reads the arguments of a launch, the first one or a later one handed to the running app (#610). Each argument is a link
 * the OS or the browser passed (`magnet:`, a `.torrent`, a web, FTP or SFTP address, `anydm://add?url=…`); anything else
 * is dropped, so a stray argument never becomes a download. The links go to the add review, not straight to the engine.
 */
object LinkRouter {
    const val HIDDEN = "--hidden"

    fun route(arguments: List<String>): LaunchRoute =
        LaunchRoute(
            links = arguments.mapNotNull { LinkIntake.parse(it)?.link }.distinct(),
            hidden = HIDDEN in arguments,
        )
}
