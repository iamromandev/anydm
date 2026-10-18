package dev.anydm.engine.tools

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.platform.toFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipInputStream

/**
 * Zips are read with the JDK; `.tar.xz` goes to the system `tar` (on every Linux and macOS and Windows 10+) through the
 * [ProcessRunner], so no archive library is needed.
 */
class DesktopUnpacker(
    private val runner: ProcessRunner,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Unpacker {
    override suspend fun unpack(
        archive: Path,
        kind: ArchiveKind,
        into: Path,
    ): FileResult<Unit> =
        when (kind) {
            ArchiveKind.NONE -> FileResult.Ok(Unit)
            ArchiveKind.ZIP -> withContext(io) { unzip(archive, into) }
            ArchiveKind.TAR_XZ -> untar(archive, into)
        }

    override suspend fun markExecutable(path: Path): FileResult<Unit> =
        withContext(io) {
            val file = java.io.File(path.toString())
            when {
                !file.exists() -> FileResult.NotFound(path)
                file.setExecutable(true, false) || file.canExecute() || onWindows -> FileResult.Ok(Unit)
                else -> FileResult.AccessDenied(path, "can't make it executable")
            }
        }

    private fun unzip(
        archive: Path,
        into: Path,
    ): FileResult<Unit> {
        val source =
            java.nio.file.Path
                .of(archive.toString())
        if (!Files.exists(source)) return FileResult.NotFound(archive)
        val base =
            java.nio.file.Path
                .of(into.toString())
                .toAbsolutePath()
                .normalize()
        return try {
            var entries = 0
            ZipInputStream(Files.newInputStream(source)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    val name = entry.name
                    val target = base.resolve(name).normalize()
                    val drive = name.length > 1 && name[1] == ':'
                    if (name.startsWith("/") || name.startsWith("\\") || drive || !target.startsWith(base)) {
                        return FileResult.InvalidName(Path(into, name), "the archive entry '$name' would leave the folder")
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target, REPLACE_EXISTING)
                    }
                }
            }
            if (entries == 0) FileResult.IoError(archive, "not a zip archive, or an empty one") else FileResult.Ok(Unit)
        } catch (e: IOException) {
            e.toFailure(archive)
        }
    }

    private suspend fun untar(
        archive: Path,
        into: Path,
    ): FileResult<Unit> {
        val folder =
            java.nio.file.Path
                .of(into.toString())
        if (!withContext(io) {
                Files.exists(
                    java.nio.file.Path
                        .of(archive.toString()),
                )
            }
        ) {
            return FileResult.NotFound(archive)
        }
        try {
            withContext(io) { Files.createDirectories(folder) }
        } catch (e: IOException) {
            return e.toFailure(into)
        }
        return when (val ran = runner.run(ProcessSpec(listOf("tar", "-xJf", archive.toString(), "-C", into.toString())))) {
            is ProcessOutcome.Failed -> {
                FileResult.IoError(archive, "can't run tar: ${ran.failure}")
            }

            is ProcessOutcome.Ok -> {
                if (ran.value.exitCode == 0) {
                    FileResult.Ok(Unit)
                } else {
                    FileResult.IoError(archive, "tar failed (${ran.value.exitCode}): ${ran.value.stderr.take(500)}")
                }
            }
        }
    }

    private companion object {
        val onWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}
