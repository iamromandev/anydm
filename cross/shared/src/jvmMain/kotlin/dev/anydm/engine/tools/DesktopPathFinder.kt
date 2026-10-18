package dev.anydm.engine.tools

import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path as NioPath

/** Searches the folders of `PATH` (or whatever [path] returns) for a file the user can run. */
class DesktopPathFinder(
    /** Searched after `PATH`: an app started from Finder gets a short `PATH` that leaves out Homebrew. */
    private val extraFolders: List<String> = extraFoldersFor(System.getProperty("os.name")),
    private val path: () -> String? = { System.getenv("PATH") },
) : PathFinder {
    override fun find(executable: String): Path? =
        ((path()?.split(File.pathSeparator).orEmpty()) + extraFolders)
            .filter { it.isNotBlank() }
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

    companion object {
        fun extraFoldersFor(osName: String): List<String> =
            if (osName.startsWith(
                    "Mac",
                    ignoreCase = true,
                )
            ) {
                listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin")
            } else {
                emptyList()
            }
    }
}
