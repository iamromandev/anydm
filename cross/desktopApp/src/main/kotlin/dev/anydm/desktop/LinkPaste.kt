package dev.anydm.desktop

import dev.anydm.engine.os.LinkIntake
import dev.anydm.engine.platform.Clipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How much of a pasted text is looked at: what follows is ignored. */
internal const val MAX_PASTE_CHARS = 64 * 1024

/** The longest line (and so the longest link) that is taken; a longer one is no link anyone means to download. */
internal const val MAX_LINK_CHARS = 8 * 1024

/**
 * The first line of pasted text that is a link anydm can download (web, magnet, FTP, a .torrent address), or null. Only the
 * first [MAX_PASTE_CHARS] are scanned and lines over [MAX_LINK_CHARS] are passed over, so a huge clipboard costs little.
 */
internal fun pastedLink(text: String?): String? =
    text
        // The line the cut lands in is dropped whole: half a link is a different link.
        ?.let { if (it.length > MAX_PASTE_CHARS) it.take(MAX_PASTE_CHARS).substringBeforeLast('\n', "") else it }
        ?.lineSequence()
        ?.filter { it.length <= MAX_LINK_CHARS }
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() && LinkIntake.parse(it) != null }

/** The link on [clipboard], read and scanned on a background thread; null when there is none or it can't be read now. */
internal suspend fun clipboardLink(clipboard: Clipboard): String? =
    withContext(Dispatchers.Default) {
        try {
            pastedLink(clipboard.text())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

/** The `.torrent` files among dropped [paths]; anything else dropped on the window is left alone. */
internal fun droppedTorrents(paths: List<String>): List<String> = paths.filter { it.endsWith(".torrent", ignoreCase = true) }
