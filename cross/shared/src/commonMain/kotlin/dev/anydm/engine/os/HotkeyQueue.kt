package dev.anydm.engine.os

import dev.anydm.engine.Engine
import dev.anydm.engine.ftp.stripUserInfo
import dev.anydm.engine.http.percentDecodedBytes
import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.Clipboard
import dev.anydm.engine.platform.Notifier
import kotlinx.coroutines.CancellationException

/**
 * What a press of the global hotkey does (OSI-06): reads the clipboard, adds what is there through the engine's intake with
 * the default profile, and says in one notification what came of it: added, already there, no link, or why it could not be
 * added. The notification follows the notifications setting ([notificationsOn]) like every other, and the add does not wait
 * on it or depend on it. Whether the clipboard watch is on makes no difference.
 */
class HotkeyQueue(
    private val engine: Engine,
    private val clipboard: Clipboard,
    private val notifier: Notifier,
    private val notificationsOn: suspend () -> Boolean,
    private val log: Log = Log.None,
) {
    suspend fun press() {
        val parsed = readClipboard()?.let { ClipboardLinks.parse(it, allowFiles = true) }
        tell(if (parsed == null) NO_LINK else add(parsed))
    }

    // The engine can refuse in ways that are values, and fail in ways that are not (it has stopped, the store broke): the user
    // is told either way, since a hotkey has no other place to say so.
    private suspend fun add(parsed: ParsedLink): String =
        try {
            when (val result = engine.addLink(parsed.link, EntryPoint.HOTKEY)) {
                is AddLinkResult.Added -> "Added: ${NotificationText.clean(nameOf(parsed.link), NAME_MAX)}"
                is AddLinkResult.Duplicate -> "Already in the list"
                AddLinkResult.NotALink -> NO_LINK
                is AddLinkResult.Failed -> "Could not add: ${NotificationText.clean(result.reason, REASON_MAX)}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Could not add: ${NotificationText.clean(e.message ?: e::class.simpleName ?: "something went wrong", REASON_MAX)}"
        }

    private suspend fun readClipboard(): String? =
        try {
            clipboard.text()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun tell(message: String) {
        try {
            if (notificationsOn()) notifier.notify(TITLE, message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("hotkey") { "the result could not be shown: ${e.message}" }
        }
    }

    // What the user will know the download by: a magnet's display name, else the last part of the path, else the host.
    private fun nameOf(text: String): String {
        val bare = stripUserInfo(text)
        if (bare.startsWith("magnet:", ignoreCase = true)) {
            val name =
                bare
                    .substringAfter('?')
                    .split('&')
                    .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
                    ?.substring(3)
            return name?.let { decoded(it.replace('+', ' ')) }?.takeIf { it.isNotBlank() } ?: "a torrent"
        }
        val afterScheme = bare.substringAfter("://", bare)
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val path =
            afterScheme
                .substringAfter('/', "")
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('/')
        val last = decoded(path.substringAfterLast('/'))
        return last.takeIf { it.isNotBlank() } ?: host.ifBlank { "a link" }
    }

    private fun decoded(text: String): String = percentDecodedBytes(text)?.decodeToString() ?: text

    private companion object {
        const val TITLE = "anydm"
        const val NO_LINK = "No link on the clipboard"
        const val NAME_MAX = 60
        const val REASON_MAX = 80
    }
}
