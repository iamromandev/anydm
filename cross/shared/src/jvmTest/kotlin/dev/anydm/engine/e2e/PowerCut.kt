package dev.anydm.engine.e2e

import dev.anydm.engine.core.partialPath
import dev.anydm.engine.core.targetPath
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * A power cut, as far as a test can make one (#576): after a kill, every partial byte above its stored watermark is
 * replaced with garbage, since bytes the engine never synced may be lost or torn. Bytes below a watermark were synced
 * before it was saved, so they stay. SQLite's own crash safety is trusted, not emulated.
 */
internal suspend fun spoilUnsynced(
    databaseFile: Path,
    saveFolder: Path,
    random: Random,
) {
    val store = Store(openDatabaseFile(databaseFile), Dispatchers.IO)
    try {
        for (download in store.downloads.list()) {
            for (file in store.downloads.files(download.id)) {
                val partial = java.io.File(partialPath(targetPath(saveFolder, file.path), download.id).toString())
                if (!partial.exists()) continue
                RandomAccessFile(partial, "rw").use { out ->
                    for (segment in store.downloads.segments(file.id)) {
                        val length = (segment.end - segment.watermark).toInt()
                        if (length <= 0) continue
                        out.seek(segment.watermark)
                        out.write(random.nextBytes(length))
                    }
                }
            }
        }
    } finally {
        store.close()
    }
}
