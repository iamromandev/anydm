package dev.anydm.engine.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import java.nio.file.Files
import kotlin.io.path.isDirectory
import java.nio.file.Path as NioPath

/** What the Windows guard needs to know about a drive. */
internal interface DriveInfo {
    fun isFixedDrive(path: NioPath): Boolean

    fun totalSpace(path: NioPath): Long
}

/** Windows' own answers, through JNA; only touched on Windows. */
internal object JnaDriveInfo : DriveInfo {
    override fun isFixedDrive(path: NioPath): Boolean {
        val root = path.root?.toString() ?: return false
        return Kernel32.INSTANCE.GetDriveType(root) == WinBase.DRIVE_FIXED
    }

    override fun totalSpace(path: NioPath): Long = Files.getFileStore(path).totalSpace
}

/**
 * The Recycle Bin deletes for good when an item is on a network or removable drive, or too big for the bin (FIL-09),
 * so those are refused here and the files stay where they are. "Too big" is more than a tenth of the drive, the
 * bin's default size; the user's own setting for it isn't read.
 */
internal class WindowsGuardedTrashBin(
    private val inner: TrashBin,
    private val drive: DriveInfo,
) : TrashBin {
    override val isAvailable: Boolean get() = inner.isAvailable

    override fun moveToTrash(item: NioPath): Boolean {
        if (item.toString().startsWith("\\\\")) return false
        if (!drive.isFixedDrive(item)) return false
        if (sizeOf(item) > drive.totalSpace(item) / 10) return false
        return inner.moveToTrash(item)
    }

    private fun sizeOf(item: NioPath): Long =
        if (item.isDirectory()) {
            Files.walk(item).use { paths ->
                paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
            }
        } else {
            Files.size(item)
        }
}
