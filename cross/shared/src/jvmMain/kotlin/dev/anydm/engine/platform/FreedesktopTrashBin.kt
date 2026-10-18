package dev.anydm.engine.platform

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.file.Path as NioPath

/** Which volume a path is on, so the trash can be chosen the way the freedesktop.org spec says. */
internal interface Volumes {
    fun sameVolume(
        a: NioPath,
        b: NioPath,
    ): Boolean

    /** The top folder of the volume that holds [path]. */
    fun mountRoot(path: NioPath): NioPath

    object Real : Volumes {
        override fun sameVolume(
            a: NioPath,
            b: NioPath,
        ): Boolean = Files.getFileStore(existing(a)) == Files.getFileStore(existing(b))

        override fun mountRoot(path: NioPath): NioPath {
            val store = Files.getFileStore(existing(path))
            var top = path.toAbsolutePath()
            while (true) {
                val parent = top.parent ?: return top
                if (Files.getFileStore(parent) != store) return top
                top = parent
            }
        }

        private fun existing(path: NioPath): NioPath {
            var current = path.toAbsolutePath()
            while (!Files.exists(current)) current = current.parent ?: return current
            return current
        }
    }
}

/**
 * The freedesktop.org Trash (FIL-09) for Linux, where the JDK has none: the home trash for files on the home volume,
 * `$topdir/.Trash-$uid` for other volumes. Each trashed item gets a `.trashinfo` file made first with `CREATE_NEW`, so
 * two deletions never take the same name. Nothing is ever deleted: if the move fails, only the info file is removed.
 */
internal class FreedesktopTrashBin(
    val homeTrash: NioPath,
    private val uid: Int,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val volumes: Volumes = Volumes.Real,
) : TrashBin {
    override val isAvailable: Boolean
        get() =
            try {
                makeTrash(homeTrash)
                true
            } catch (e: IOException) {
                false
            }

    override fun moveToTrash(item: NioPath): Boolean {
        if (!Files.exists(item, NOFOLLOW_LINKS)) throw NoSuchFileException(item.toString())
        val absolute = item.toAbsolutePath()
        val folder = absolute.parent ?: return false
        makeTrash(homeTrash)
        val onHomeVolume = volumes.sameVolume(folder, homeTrash)
        val top = if (onHomeVolume) null else volumes.mountRoot(folder)
        val trash = if (top == null) homeTrash else top.resolve(".Trash-$uid")
        if (top != null) makeTrash(trash)
        val recorded = if (top == null) absolute.toString() else top.relativize(absolute).toString()
        val info = trash.resolve("info")
        val files = trash.resolve("files")
        var number = 1
        while (true) {
            val name = numbered(absolute.fileName.toString(), number++)
            val infoFile = info.resolve("$name.trashinfo")
            try {
                Files
                    .newByteChannel(
                        infoFile,
                        CREATE_NEW,
                        WRITE,
                    ).use { it.write(java.nio.ByteBuffer.wrap(infoText(recorded).toByteArray())) }
            } catch (e: FileAlreadyExistsException) {
                continue
            }
            val target = files.resolve(name)
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                Files.delete(infoFile)
                continue
            }
            try {
                Files.move(absolute, target, ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.deleteIfExists(infoFile)
                throw e
            }
            return true
        }
    }

    private fun infoText(recorded: String) =
        "[Trash Info]\nPath=${encoded(recorded)}\nDeletionDate=${now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}\n"

    private fun makeTrash(trash: NioPath) {
        for (name in listOf("files", "info")) {
            val folder = trash.resolve(name)
            try {
                Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } catch (e: UnsupportedOperationException) {
                Files.createDirectories(folder)
            }
        }
    }

    /** `a.txt`, then `a.2.txt`, `a.3.txt`: the number goes before the last extension. */
    private fun numbered(
        name: String,
        number: Int,
    ): String {
        if (number == 1) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + ".$number" + name.substring(dot) else "$name.$number"
    }

    /** Percent-encoded UTF-8; letters, digits, `/` and `-_.~` stay as they are. */
    private fun encoded(text: String): String =
        text.toByteArray().joinToString("") { byte ->
            val c = (byte.toInt() and 0xff).toChar()
            if (byte >= 0 && (c.isLetterOrDigit() || c in "/-_.~")) c.toString() else "%" + "%02X".format(byte.toInt() and 0xff)
        }
}
