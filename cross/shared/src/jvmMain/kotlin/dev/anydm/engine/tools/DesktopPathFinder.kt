package dev.anydm.engine.tools

import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path as NioPath

/** Searches the folders of `PATH` (or whatever [path] returns) for a file the user can run. */
class DesktopPathFinder(
    private val path: () -> String? = { System.getenv("PATH") },
) : PathFinder {
    override fun find(executable: String): Path? =
        path()
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.firstNotNullOfOrNull { folder -> candidate(folder, executable) }
            ?.let { Path(it.toString()) }

    private fun candidate(
        folder: String,
        executable: String,
    ): NioPath? =
        try {
            NioPath.of(folder, executable).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
        } catch (e: InvalidPathException) {
            null
        }
}
