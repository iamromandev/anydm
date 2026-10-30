package dev.anydm.desktop

import java.nio.file.Files
import java.nio.file.Path

/** Whether the welcome screen has been seen. */
interface FirstRun {
    fun isDone(): Boolean

    fun markDone()
}

/** Remembered by a marker file in [folder]; a folder that can't be written to shows the welcome again next time. */
class FileFirstRun(
    private val folder: Path,
) : FirstRun {
    private val marker get() = folder.resolve("first-run-done")

    override fun isDone(): Boolean = Files.exists(marker)

    override fun markDone() {
        runCatching {
            Files.createDirectories(folder)
            Files.writeString(marker, "")
        }
    }
}
