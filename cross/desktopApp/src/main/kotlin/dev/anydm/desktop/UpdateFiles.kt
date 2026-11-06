package dev.anydm.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** Where the [Updater] keeps what it downloads. */
interface UpdateFiles {
    /** A new, empty folder for one download. */
    suspend fun createDirectory(): Path

    /** A new file [name] (a plain file name) in [directory], a folder made by [createDirectory]; fails when it exists. */
    suspend fun create(
        directory: Path,
        name: String,
    ): UpdateSink

    /** The SHA-256 of [file] in lowercase hex, or null when it is no longer a file there. */
    suspend fun sha256(file: Path): String?

    /** Removes [directory] and what is in it, if it is one made by [createDirectory]; never fails. */
    suspend fun deleteAll(directory: Path)

    /** Removes download folders that an earlier run left behind (more than a day old, the user's own); never fails. */
    suspend fun sweepOld()
}

interface UpdateSink {
    suspend fun write(
        bytes: ByteArray,
        length: Int,
    )

    suspend fun close()
}

/**
 * [UpdateFiles] on disk: each download gets a fresh folder under [root] (the system's temp folder by default; a new folder the
 * owner alone can use where the system allows, `rwx------` on POSIX), the file is created new inside it, and only folders made
 * here are ever removed by [deleteAll]. Folders are removed without following links. [io] runs the blocking calls.
 */
class DiskUpdateFiles(
    private val root: Path? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Who the user is, for [sweepOld]: only folders this principal owns are swept; null (unknown) sweeps nothing. */
    private val owner: () -> UserPrincipal? = ::currentUser,
) : UpdateFiles {
    private val made: MutableSet<Path> = Collections.synchronizedSet(mutableSetOf())

    private val parent: Path get() = root ?: Path.of(System.getProperty("java.io.tmpdir"))

    override suspend fun createDirectory(): Path {
        val created = AtomicReference<Path?>(null)
        try {
            return withContext(io) {
                Files.createTempDirectory(parent, PREFIX).also {
                    created.set(it)
                    made.add(it)
                }
            }
        } catch (e: CancellationException) {
            // The folder may exist although the caller was cancelled before it got the answer; nobody else knows of it.
            created.get()?.let { forget(it) }
            throw e
        }
    }

    override suspend fun create(
        directory: Path,
        name: String,
    ): UpdateSink {
        val opened = AtomicReference<OutputStream?>(null)
        try {
            return withContext(io) {
                require(directory in made) { "$directory is not a download folder" }
                val path = directory.resolve(name)
                require(path.normalize().parent == directory) { "$name is not a plain file name" }
                val out: OutputStream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                opened.set(out)
                object : UpdateSink {
                    override suspend fun write(
                        bytes: ByteArray,
                        length: Int,
                    ) = withContext(io) { out.write(bytes, 0, length) }

                    override suspend fun close() = withContext(io) { out.close() }
                }
            }
        } catch (e: CancellationException) {
            runCatching { opened.get()?.close() }
            throw e
        }
    }

    override suspend fun sha256(file: Path): String? =
        withContext(io) {
            try {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@withContext null
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                digest.digest().toHex()
            } catch (e: IOException) {
                null
            }
        }

    override suspend fun deleteAll(directory: Path) {
        // Also while the caller is being cancelled: a half-written download must not stay behind.
        withContext(NonCancellable + io) {
            if (made.remove(directory)) removeTree(directory)
        }
    }

    override suspend fun sweepOld() {
        withContext(io) {
            val me = runCatching(owner).getOrNull() ?: return@withContext
            val cutoff = FileTime.from(Instant.now().minus(STALE_AFTER_DAYS, ChronoUnit.DAYS))
            try {
                Files.newDirectoryStream(parent, "$PREFIX*").use { entries ->
                    for (entry in entries) {
                        try {
                            val old =
                                entry !in made &&
                                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) &&
                                    Files.getOwner(entry, LinkOption.NOFOLLOW_LINKS) == me &&
                                    Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS) < cutoff
                            if (old) removeTree(entry)
                        } catch (e: Exception) {
                            // This one stays; the rest still get their turn.
                        }
                    }
                }
            } catch (e: Exception) {
                // No temp folder to look in, or a listing that broke half way (DirectoryIteratorException): nothing more to sweep.
            }
        }
    }

    private fun forget(directory: Path) {
        made.remove(directory)
        removeTree(directory)
    }

    private companion object {
        const val PREFIX = "anydm-update-"
        const val STALE_AFTER_DAYS = 1L
    }
}

private fun currentUser(): UserPrincipal? =
    runCatching {
        FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    }.getOrNull()

// Deletes [directory] and what is in it. A link inside is removed as a link, never followed. Whatever cannot be deleted stays.
private fun removeTree(directory: Path) {
    try {
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    runCatching { Files.delete(dir) }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (e: IOException) {
        // Gone already, or not readable: nothing more to do.
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
