package dev.anydm.engine.platform

import java.awt.Desktop
import java.nio.file.NoSuchFileException
import java.nio.file.Path as NioPath

/** Where [DesktopFileSystem.moveToTrash] sends things; swapped in tests so they never fill the real trash. */
internal interface TrashBin {
    /** Asked on every call: it depends on the session (headless, Linux desktop). */
    val isAvailable: Boolean

    /** Moves [item] to the trash; false if the OS refused. */
    fun moveToTrash(item: NioPath): Boolean
}

/**
 * The Trash on macOS and the Recycle Bin on Windows, through AWT. The JDK has none on Linux or when
 * headless. AWT starts only when a trash is actually needed.
 */
internal object DesktopTrashBin : TrashBin {
    override val isAvailable: Boolean
        get() = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)

    override fun moveToTrash(item: NioPath): Boolean =
        try {
            Desktop.getDesktop().moveToTrash(item.toFile())
        } catch (e: IllegalArgumentException) {
            // AWT's way of saying it vanished after the caller checked.
            throw NoSuchFileException(item.toString()).apply { initCause(e) }
        }
}
