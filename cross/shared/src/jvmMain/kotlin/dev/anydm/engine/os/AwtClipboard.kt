package dev.anydm.engine.os

import dev.anydm.engine.platform.Clipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.AWTError
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * [Clipboard] over an AWT clipboard (OSI-05, OSI-06). The text is read off the caller's thread. A clipboard that holds no text
 * is null; one that can't be read right now (another program holds it, the flavor can't be fetched) throws, so the caller can
 * tell the two apart and look again.
 */
class AwtClipboard(
    private val clipboard: java.awt.datatransfer.Clipboard,
) : Clipboard {
    override suspend fun text(): String? =
        withContext(Dispatchers.IO) {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
        }
}

/** The system clipboard of this machine; none without a display. The desktop passes it to the clipboard watch and the hotkey. */
fun systemClipboard(): Clipboard =
    if (GraphicsEnvironment.isHeadless()) {
        Clipboard.None
    } else {
        try {
            AwtClipboard(Toolkit.getDefaultToolkit().systemClipboard)
        } catch (_: Exception) {
            Clipboard.None
        } catch (_: AWTError) {
            Clipboard.None
        }
    }
