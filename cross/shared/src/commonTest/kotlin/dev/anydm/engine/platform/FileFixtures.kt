package dev.anydm.engine.platform

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random

// Test-side files on kotlinx-io, so contracts stay common.

internal fun newTempFolder(prefix: String): Path =
    Path(SystemTemporaryDirectory, prefix + Random.nextLong().toULong().toString(16))
        .also { SystemFileSystem.createDirectories(it, mustCreate = true) }

internal fun writeText(
    path: Path,
    text: String,
) = SystemFileSystem.sink(path).buffered().use { it.writeString(text) }

internal fun readText(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

internal fun readBytes(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

internal fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

internal fun isFolder(path: Path): Boolean = SystemFileSystem.metadataOrNull(path)?.isDirectory == true

internal fun sizeOf(path: Path): Long? = SystemFileSystem.metadataOrNull(path)?.size

internal fun namesIn(folder: Path): Set<String> = SystemFileSystem.list(folder).map { it.name }.toSet()

internal fun deleteRecursively(path: Path) {
    val metadata = SystemFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::deleteRecursively)
    SystemFileSystem.delete(path)
}
