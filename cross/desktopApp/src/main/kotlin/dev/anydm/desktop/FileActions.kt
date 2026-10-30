package dev.anydm.desktop

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

/** What the app does with a finished file; each returns why it could not, or null when it did. */
interface FileActions {
    fun open(path: Path): String?

    fun reveal(path: Path): String?

    fun copy(text: String): String?
}

/** The OS's own: its default app, its file manager and the clipboard. */
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
