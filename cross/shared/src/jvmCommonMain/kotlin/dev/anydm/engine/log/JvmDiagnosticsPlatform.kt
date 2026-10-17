package dev.anydm.engine.log

import dev.anydm.engine.platform.FileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Path as NioPath

/** The bundle's platform on the JVM (desktop and Android): log files from [logsFolder], zips with java.util.zip. */
class JvmDiagnosticsPlatform(
    logsFolder: Path,
) : DiagnosticsPlatform {
    private val dir: NioPath = NioPath.of(logsFolder.toString())

    override suspend fun logFiles(): List<BundleEntry> =
        withContext(Dispatchers.IO) {
            val names =
                try {
                    Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() }
                } catch (_: IOException) {
                    emptyList()
                }
            names
                .mapNotNull { name -> LOG_NAME.matchEntire(name)?.let { name to (it.groupValues[1].toIntOrNull() ?: 0) } }
                .sortedBy { it.second }
                .mapNotNull { (name, _) ->
                    try {
                        BundleEntry(name, Files.readAllBytes(dir.resolve(name)))
                    } catch (_: IOException) {
                        null
                    }
                }
        }

    override suspend fun writeZip(
        target: Path,
        entries: List<BundleEntry>,
    ): FileResult<Unit> =
        withContext(Dispatchers.IO) {
            val file = NioPath.of(target.toString())
            val part = file.resolveSibling(file.fileName.toString() + ".part")
            try {
                ZipOutputStream(Files.newOutputStream(part)).use { zip ->
                    for (entry in entries) {
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(entry.bytes)
                        zip.closeEntry()
                    }
                }
                try {
                    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING)
                }
                FileResult.Ok(Unit)
            } catch (e: IOException) {
                try {
                    Files.deleteIfExists(part)
                } catch (_: IOException) {
                    // Nothing more to clean.
                }
                FileResult.IoError(target, e.message)
            }
        }

    private companion object {
        val LOG_NAME = Regex("""anydm(?:\.(\d+))?\.log""")
    }
}
