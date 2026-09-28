package dev.anydm.desktop.files

import dev.anydm.settings.Player
import java.awt.Desktop
import java.net.URI

private val MEDIA = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")

/** Whether a file name is something a player plays (web: `hasMediaExtension`). */
fun isMedia(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MEDIA

/** The command a chosen player is started with, or `null` for the system's own handler. */
fun playCommand(
    player: Player,
    url: String,
    os: String = System.getProperty("os.name"),
): List<String>? =
    when (player) {
        Player.System -> {
            null
        }

        is Player.Command -> {
            if (os.lowercase().startsWith("mac") && player.path.endsWith(".app")) {
                listOf("open", "-a", player.path, url)
            } else {
                listOf(player.path, url)
            }
        }
    }

/** Plays [url]; `null` when it started, else what to tell the person. */
fun play(
    player: Player,
    url: String,
): String? {
    val command = playCommand(player, url)
    return try {
        if (command == null) Desktop.getDesktop().browse(URI(url)) else ProcessBuilder(command).start()
        null
    } catch (error: Exception) {
        if (player is Player.Command) "Couldn't start ${player.path}" else "Couldn't open $url"
    }
}
