package dev.anydm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.anydm.engine.torrent.MAX_TORRENT_BYTES
import dev.anydm.engine.torrent.TorrentResult
import dev.anydm.engine.torrent.readTorrent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.io.FilenameFilter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JFileChooser

/** Native pickers. Both block until the user answers, so call them from a click handler; null means cancelled. */
interface FilePicker {
    fun pickTorrent(): Path?

    fun pickFolder(current: String): String?
}

/** Reads and parses a `.torrent` off the UI thread. */
interface TorrentLoader {
    suspend fun load(path: Path): TorrentResult
}

/** Reads from disk on [io]. The size is checked before reading, and the read itself is capped, so a huge file is never loaded. */
class DiskTorrentLoader(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TorrentLoader {
    override suspend fun load(path: Path): TorrentResult =
        withContext(io) {
            try {
                // A directory or a pipe is no torrent, and opening a pipe would block this thread forever.
                if (!Files.isRegularFile(path)) {
                    TorrentResult.Err("Can't read file")
                } else if (Files.size(path) > MAX_TORRENT_BYTES) {
                    TorrentResult.Err("Too large")
                } else {
                    readTorrent(Files.newInputStream(path).use { it.readNBytes(MAX_TORRENT_BYTES + 1) })
                }
            } catch (_: IOException) {
                TorrentResult.Err("Can't read file")
            } catch (_: SecurityException) {
                TorrentResult.Err("Can't read file")
            }
        }
}

/** The path a native dialog answered with, or null when the platform cannot represent it. */
fun pickedPath(
    directory: String?,
    file: String,
): Path? =
    try {
        Path.of(directory ?: "", file)
    } catch (_: InvalidPathException) {
        null
    }

/** The AWT pickers. Only [FilesEnv.real] builds this, and only the app's `main` uses that: tests inject fakes. */
class AwtFilePicker : FilePicker {
    override fun pickTorrent(): Path? {
        if (GraphicsEnvironment.isHeadless()) return null
        val dialog = FileDialog(null as Frame?, "Open .torrent", FileDialog.LOAD)
        dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(".torrent", ignoreCase = true) }
        dialog.file = "*.torrent"
        return try {
            dialog.isVisible = true
            dialog.file?.let { pickedPath(dialog.directory, it) }
        } finally {
            dialog.dispose()
        }
    }

    override fun pickFolder(current: String): String? {
        if (GraphicsEnvironment.isHeadless()) return null
        return if (System.getProperty("os.name").startsWith("Mac")) pickFolderMac(current) else pickFolderSwing(current)
    }

    private fun pickFolderMac(current: String): String? {
        val key = "apple.awt.fileDialogForDirectories"
        val before = System.getProperty(key)
        System.setProperty(key, "true")
        val dialog = FileDialog(null as Frame?, "Choose a folder", FileDialog.LOAD)
        if (File(current).isDirectory) dialog.directory = current
        return try {
            dialog.isVisible = true
            dialog.file?.let { pickedPath(dialog.directory, it)?.toString() }
        } finally {
            dialog.dispose()
            if (before == null) System.clearProperty(key) else System.setProperty(key, before)
        }
    }

    private fun pickFolderSwing(current: String): String? {
        val chooser = JFileChooser(File(current).takeIf { it.isDirectory })
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }
}

/** Everything platform-specific the Home screen needs for files. Tests pass fakes. */
data class FilesEnv(
    val picker: FilePicker,
    val loader: TorrentLoader,
    val home: String,
    val drops: DropController = DropController(),
) {
    companion object {
        fun real(): FilesEnv = FilesEnv(AwtFilePicker(), DiskTorrentLoader(), System.getProperty("user.home").orEmpty())

        /** Cancels every pick and cannot read files; the default for [HomeScreen], so nothing ever opens an OS dialog by accident. */
        fun inert(): FilesEnv =
            FilesEnv(
                object : FilePicker {
                    override fun pickTorrent(): Path? = null

                    override fun pickFolder(current: String): String? = null
                },
                object : TorrentLoader {
                    override suspend fun load(path: Path): TorrentResult = TorrentResult.Err("Can't read file")
                },
                "/home/user",
            )
    }
}

private fun String.separator() = if ('\\' in this && '/' !in this) "\\" else "/"

private fun String.stripTrailingSeparators() = trimEnd('/', '\\')

/** Replaces a leading [home] folder in [path] with "~". Only a whole leading folder matches. */
fun displayFolder(
    path: String,
    home: String,
): String {
    val h = home.stripTrailingSeparators()
    if (h.isEmpty()) return path
    if (!path.startsWith(h)) return path
    val rest = path.substring(h.length)
    return when {
        rest.isEmpty() || rest.all { it == '/' || it == '\\' } -> "~"
        rest[0] == '/' || rest[0] == '\\' -> "~$rest"
        else -> path
    }
}

/** The save folder used until the user picks another: `<home>/Downloads`. */
fun defaultFolder(home: String): String = home.stripTrailingSeparators() + home.separator() + "Downloads"

/** The folder a download shows in its detail view: its own, or the default, with home shortened to "~". */
fun folderLabel(
    item: DownloadItem,
    home: String,
): String = displayFolder(item.folder ?: defaultFolder(home), home)

/** What was dragged onto the window: file paths and/or text. */
data class DropItems(
    val files: List<Path>,
    val text: String?,
)

sealed interface DropAction {
    /** [more] is true when other usable links were in the same drop. */
    data class Link(
        val text: String,
        val more: Boolean = false,
    ) : DropAction

    /** [more] is true when other usable items were in the same drop. */
    data class Torrent(
        val path: Path,
        val more: Boolean,
    ) : DropAction

    data object Rejected : DropAction
}

/** A `.torrent` file wins over text; else the first http(s) or magnet line. Anything else is rejected. */
fun classifyDrop(items: DropItems): DropAction {
    val torrents = items.files.filter { it.fileName?.toString()?.endsWith(".torrent", ignoreCase = true) == true }
    val links =
        items.text
            ?.lines()
            .orEmpty()
            .mapNotNull { pasteLink(it, false) }
    return when {
        torrents.isNotEmpty() -> DropAction.Torrent(torrents.first(), more = torrents.size > 1 || links.isNotEmpty())
        links.isNotEmpty() -> DropAction.Link(links.first(), more = links.size > 1)
        else -> DropAction.Rejected
    }
}

/** Reads files and text off a drag. Never throws: anything unreadable counts as absent. */
fun dropItemsOf(t: Transferable): DropItems {
    val files =
        try {
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>().map { it.toPath() }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    val text =
        try {
            if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) t.getTransferData(DataFlavor.stringFlavor) as? String else null
        } catch (_: Exception) {
            null
        }
    return DropItems(files, text)
}

/** The window's drop target pushes into this; Home observes it. */
class DropController {
    var hovering by mutableStateOf(false)
        private set

    private val events = MutableSharedFlow<DropItems>(extraBufferCapacity = 16)
    val drops: SharedFlow<DropItems> = events

    fun hover(on: Boolean) {
        hovering = on
    }

    fun drop(items: DropItems) {
        hovering = false
        events.tryEmit(items)
    }
}
