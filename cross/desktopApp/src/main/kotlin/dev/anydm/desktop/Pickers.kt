package dev.anydm.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/** The OS's file choosers the add dialog and settings open; null when the user cancels. */
interface Pickers {
    fun torrentFile(): Path?

    fun folder(): Path?

    fun keyFile(): Path?

    /** Where to write a settings file, starting at [name]. */
    fun saveSettingsFile(name: String): Path? = null

    fun settingsFile(): Path? = null

    /** Where to write the diagnostics bundle, starting at [name]. */
    fun saveDiagnosticsFile(name: String): Path? = null
}

/** AWT's native file dialog: Finder's on macOS, the system one elsewhere. */
class DesktopPickers : Pickers {
    override fun torrentFile(): Path? = choose("Open a .torrent file") { _, name -> name.endsWith(".torrent", ignoreCase = true) }

    // macOS picks a folder only when the dialog is told to; elsewhere the user picks a file in it and its folder is taken.
    override fun folder(): Path? {
        val mac = IsMac
        if (mac) System.setProperty("apple.awt.fileDialogForDirectories", "true")
        return try {
            choose("Choose a folder")?.let { if (mac) it else it.parent }
        } finally {
            if (mac) System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    override fun keyFile(): Path? = choose("Choose an SSH private key")

    override fun saveSettingsFile(name: String): Path? {
        val dialog = FileDialog(null as Frame?, "Export settings", FileDialog.SAVE)
        dialog.file = name
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return Path.of(dialog.directory ?: "", file)
    }

    override fun saveDiagnosticsFile(name: String): Path? {
        val dialog = FileDialog(null as Frame?, "Export diagnostics", FileDialog.SAVE)
        dialog.file = name
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return Path.of(dialog.directory ?: "", file)
    }

    override fun settingsFile(): Path? = choose("Import settings") { _, name -> name.endsWith(".json", ignoreCase = true) }

    private fun choose(
        title: String,
        filter: ((java.io.File, String) -> Boolean)? = null,
    ): Path? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (filter != null) dialog.setFilenameFilter(filter)
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return Path.of(dialog.directory ?: "", file)
    }
}
