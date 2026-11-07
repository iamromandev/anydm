package dev.anydm.desktop

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/** Reads text from the system clipboard; null when it holds no text or access fails. */
fun systemClipboardText(): String? =
    try {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    } catch (_: Exception) {
        null
    }
