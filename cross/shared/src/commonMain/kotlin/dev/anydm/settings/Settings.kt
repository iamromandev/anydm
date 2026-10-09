package dev.anydm.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where Play sends a finished file's URL. */
@Serializable
sealed interface Player {
    /** The system's handler for a URL: usually the browser, which plays mp4 and webm. */
    @Serializable
    @SerialName("system")
    data object System : Player

    /** A player that streams HTTP, launched with the URL: VLC, IINA, mpv. */
    @Serializable
    @SerialName("command")
    data class Command(
        val path: String,
    ) : Player
}

/** What this app remembers. The key stays in a file only its owner can read (spec: no keychain in v1). */
@Serializable
data class Settings(
    val serverUrl: String = "",
    val apiKey: String? = null,
    val defaultPreset: String = "best",
    val confirmBeforeRemove: Boolean = true,
    val player: Player = Player.System,
    /** The category new links save in, as last chosen; null until one is. */
    val addCategoryId: String? = null,
)
