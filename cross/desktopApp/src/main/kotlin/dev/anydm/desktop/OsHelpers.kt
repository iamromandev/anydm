package dev.anydm.desktop

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

/** What the app does with a finished file; each returns why it could not, or null when it did. */
interface FileActions {
    fun open(path: Path): String?

    /** Shows the file in the OS's file manager (Finder on macOS). */
    fun reveal(path: Path): String?

    fun copy(text: String): String?

    /**
     * Takes [text] off the clipboard, if it is still what the clipboard holds: something the user copied since is left alone.
     * For a secret that [copy] put there. Best effort; nothing is told if it can't.
     */
    fun clearCopied(text: String)
}

/** The OS's own: its default app, its file manager, its trash and the clipboard. */
class DesktopFileActions : FileActions {
    override fun open(path: Path): String? =
        whenThere(path) {
            if (!desktop(Desktop.Action.OPEN)) return@whenThere "This system can't open files from anydm."
            Desktop.getDesktop().open(path.toFile())
            null
        }

    // Where the OS can't point at the file itself, its folder is opened.
    override fun reveal(path: Path): String? =
        whenThere(path) {
            when {
                desktop(Desktop.Action.BROWSE_FILE_DIR) -> Desktop.getDesktop().browseFileDirectory(path.toFile())
                desktop(Desktop.Action.OPEN) -> Desktop.getDesktop().open(path.parent.toFile())
                else -> return@whenThere "This system can't show files from anydm."
            }
            null
        }

    override fun copy(text: String): String? =
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            null
        } catch (e: Exception) {
            "The path could not be copied: ${e.message ?: e::class.simpleName}"
        }

    override fun clearCopied(text: String) {
        // Reading the clipboard can wait for the program that owns it, so it runs off the calling (UI) thread. This call still
        // waits for it, up to CLIPBOARD_WAIT_MS (500 ms), so it can block the caller that long; after that the read is left behind.
        runOffThread(CLIPBOARD_WAIT_MS) {
            try {
                clearIfStill(Toolkit.getDefaultToolkit().systemClipboard, text)
            } catch (_: Exception) {
                // No clipboard to clear (a headless session, say).
            }
        }
    }

    private fun desktop(action: Desktop.Action) = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)

    private fun whenThere(
        path: Path,
        block: () -> String?,
    ): String? {
        if (!Files.exists(path)) return "$path is no longer there."
        return try {
            block()
        } catch (e: Exception) {
            "$path could not be opened: ${e.message ?: e::class.simpleName}"
        }
    }
}

private const val CLIPBOARD_WAIT_MS = 500L

/**
 * Runs [work] on a thread of its own and waits for it at most [waitMillis] (so a call made when the app is quitting has done its
 * work when it returns, and one that hangs does not hold the caller). Whatever [work] throws is its own concern.
 */
internal fun runOffThread(
    waitMillis: Long,
    work: () -> Unit,
) {
    val thread =
        Thread {
            try {
                work()
            } catch (_: Throwable) {
                // Not for the caller to hear of.
            }
        }
    thread.isDaemon = true
    thread.name = "anydm-os-helper"
    thread.start()
    thread.join(waitMillis)
}

/**
 * Empties [board] if it holds exactly [text] (as text), and says whether it did. Anything else on it, or a clipboard that
 * can't be read, is left as it is.
 */
internal fun clearIfStill(
    board: Clipboard,
    text: String,
): Boolean =
    try {
        val same = board.isDataFlavorAvailable(DataFlavor.stringFlavor) && board.getData(DataFlavor.stringFlavor) == text
        if (same) board.setContents(StringSelection(""), null)
        same
    } catch (_: Exception) {
        false
    }

/** The OS's file choosers; null when the user cancels. */
interface Pickers {
    fun torrentFile(): Path?

    fun folder(): Path?
}

/** AWT's native file dialog: Finder's on macOS, the system one elsewhere. */
class DesktopPickers : Pickers {
    override fun torrentFile(): Path? = choose("Open a .torrent file") { _, name -> name.endsWith(".torrent", ignoreCase = true) }

    // macOS picks a folder only when the dialog is told to; elsewhere the user picks a file in it and its folder is taken.
    override fun folder(): Path? {
        if (IsMac) System.setProperty("apple.awt.fileDialogForDirectories", "true")
        return try {
            choose("Choose a folder")?.let { if (IsMac) it else it.parent }
        } finally {
            if (IsMac) System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    private fun choose(
        title: String,
        filter: ((java.io.File, String) -> Boolean)? = null,
    ): Path? {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        if (filter != null) dialog.setFilenameFilter(filter)
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return Path.of(dialog.directory ?: "", file)
    }
}

/**
 * The bytes free for a new file in [folder], measured at the nearest part of it that exists (the folder may not be made
 * yet); null for a relative path or when nothing can be measured.
 */
internal fun freeDiskSpace(folder: Path): Long? {
    if (!folder.isAbsolute) return null
    val existing = generateSequence(folder) { it.parent }.firstOrNull { Files.exists(it) } ?: return null
    return try {
        Files.getFileStore(existing).usableSpace
    } catch (_: java.io.IOException) {
        null
    }
}
