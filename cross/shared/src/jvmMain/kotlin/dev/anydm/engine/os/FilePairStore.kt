package dev.anydm.engine.os

import dev.anydm.engine.log.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Instant

/**
 * [PairStore] in one JSON file, `bridge-pairs.json` in the app's data folder: `{"version":1,"pairs":[...]}`. It holds the
 * secrets that make browsers trusted, so it is for its owner only: a save writes a temporary file that is created private
 * (never readable by others, not even for a moment), flushes it to disk and moves it over the old one in one step, so a
 * reader sees the old file or the new and never a cut one. On Windows, which has no file modes, the folder's own ACL (the
 * user's profile) is what protects it. A file that is damaged (not text, not JSON, not a pairings file) is moved aside to
 * `bridge-pairs.json.bad` (or, if an earlier one is there, `.bad.1`, `.bad.2`, ...: what was set aside before is never
 * replaced) and counts as no pairings; entries of it that are whole are written back. A file that can't be read for now (the
 * read fails, or the folder can't be entered) is not damaged: [load] throws, and the file is left as it is. So is a file of a
 * `version` this code does not know (a newer anydm wrote it, perhaps): it can't be read, but it is not damaged, and it is not
 * moved, deleted or written over. Temporary files of a save that was cut short are deleted at [load]. Saves and loads of every
 * store in this process take turns.
 */
class FilePairStore(
    private val file: File,
    private val log: Log = Log.None,
) : PairStore {
    private val target: Path = file.toPath().toAbsolutePath()

    override fun load(): List<PairRecord> =
        synchronized(LOCK) {
            removeStaleTemporaries()
            // "Not there" only when the system says so: a file that can't be told to be there (a folder we can't enter) may be
            // there, and is read below, where it fails.
            if (Files.notExists(target)) return emptyList()
            keepPrivate(target)
            val text =
                try {
                    if (Files.size(target) > MAX_SIZE) null else Files.readString(target)
                } catch (_: java.nio.charset.CharacterCodingException) {
                    // Not text: a damaged file (this is an IOException too, so it comes first).
                    null
                } catch (e: IOException) {
                    log.warn("bridge", e) { "the pairings file could not be read; it is left as it is" }
                    throw e
                }
            val parsed = text?.let(::parse)
            if (parsed == null || parsed.dropped) {
                setAside()
                log.warn(
                    "bridge",
                ) { "the pairings file was damaged; ${parsed?.records?.size ?: 0} pairings kept, the rest need a new pairing" }
                if (parsed != null && parsed.records.isNotEmpty()) {
                    try {
                        write(parsed.records)
                    } catch (e: IOException) {
                        log.warn("bridge", e) { "the good pairings could not be written back" }
                    }
                }
            }
            parsed?.records ?: emptyList()
        }

    override fun save(records: List<PairRecord>) {
        synchronized(LOCK) { write(records) }
    }

    private class Parsed(
        val records: List<PairRecord>,
        val dropped: Boolean,
    )

    // A save that was cut short leaves its temporary file. Only our own kind of name, only files; failing to look is not a failure.
    private fun removeStaleTemporaries() {
        val directory = target.parent ?: return
        try {
            Files.newDirectoryStream(directory, "$TEMP_PREFIX*$TEMP_SUFFIX").use { names ->
                for (path in names) {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                    try {
                        Files.deleteIfExists(path)
                    } catch (e: IOException) {
                        log.warn("bridge", e) { "a leftover temporary file could not be deleted" }
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            // No folder yet: nothing was ever written.
        } catch (e: IOException) {
            log.warn("bridge", e) { "leftover temporary files could not be looked for" }
        }
    }

    // Null if the file is not a pairings file at all.
    private fun parse(text: String): Parsed? {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        // A `version` that is anything but the number 1 (another number, a string, null, a list) is a file of another format, or of
        // a version this code can't tell: not ours to move aside or to write over. Without the key it is no pairings file at all.
        val versionElement = root["version"] ?: return null
        val version = (versionElement as? JsonPrimitive)?.takeIf { !it.isString }?.content
        if (version != VERSION.toString()) {
            val shown = if (versionElement is JsonPrimitive) versionElement.content else "of another kind"
            log.warn("bridge") { "the pairings file has a version ($shown) which this anydm does not know; it is left as it is" }
            throw IOException("the pairings file has a version ($shown) which this anydm does not know")
        }
        val entries = root["pairs"] as? JsonArray ?: return null
        val seen = HashSet<String>()
        var dropped = false
        val records =
            entries.mapNotNull { entry ->
                val record = (entry as? JsonObject)?.let(::record)
                if (record == null || !seen.add(record.id)) {
                    dropped = true
                    null
                } else {
                    record
                }
            }
        return Parsed(records, dropped)
    }

    private fun record(entry: JsonObject): PairRecord? {
        fun text(name: String) = (entry[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun time(name: String) = text(name)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val id = text("id")?.takeIf { ID.matches(it) } ?: return null
        val secret = text("secret")?.takeIf { SECRET.matches(it) } ?: return null
        val label = cleanLabel(text("label") ?: return null).ifEmpty { "Browser" }
        return PairRecord(id, label, secret, time("createdAt") ?: return null, time("lastUsed") ?: return null)
    }

    // The damaged file goes aside whole, with its mode, for a person to look at. Beside the ones set aside before, never over
    // them: the first free of `.bad`, `.bad.1`, `.bad.2`, ... (a move that does not replace fails if the name is taken).
    private fun setAside() {
        try {
            for (n in 0 until MAX_ASIDE) {
                val name = if (n == 0) "${target.fileName}.bad" else "${target.fileName}.bad.$n"
                try {
                    Files.move(target, target.resolveSibling(name))
                    return
                } catch (_: FileAlreadyExistsException) {
                    // Taken: the next name.
                }
            }
            throw IOException("$MAX_ASIDE damaged copies are kept aside already")
        } catch (e: IOException) {
            log.warn("bridge", e) { "the damaged pairings file could not be moved aside" }
            runCatching { Files.deleteIfExists(target) }
        }
    }

    private fun write(records: List<PairRecord>) {
        val directory = target.parent
        Files.createDirectories(directory)
        val bytes =
            buildJsonObject {
                put("version", VERSION)
                put(
                    "pairs",
                    buildJsonArray {
                        records.forEach { r ->
                            add(
                                buildJsonObject {
                                    put("id", r.id)
                                    put("label", r.label)
                                    put("secret", r.secret)
                                    put("createdAt", r.createdAt.toString())
                                    put("lastUsed", r.lastUsed.toString())
                                },
                            )
                        }
                    },
                )
            }.toString().encodeToByteArray()
        val temporary = createPrivate(directory)
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw e
        }
    }

    private fun createPrivate(directory: Path): Path =
        if (POSIX) {
            Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } else {
            Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX)
        }

    // A file made by hand, or by an older version, that others can read.
    private fun keepPrivate(path: Path) {
        if (!POSIX) return
        try {
            if (Files.getPosixFilePermissions(path) != OWNER_ONLY) Files.setPosixFilePermissions(path, OWNER_ONLY)
        } catch (e: IOException) {
            log.warn("bridge", e) { "the pairings file could not be made private" }
        }
    }

    companion object {
        const val FILE_NAME = "bridge-pairs.json"

        private const val TEMP_PREFIX = "bridge-pairs"
        private const val TEMP_SUFFIX = ".tmp"
        private const val VERSION = 1
        private const val MAX_SIZE = 1L shl 20

        // How many damaged copies are kept aside at most; after that the damaged file is deleted, as when it can't be moved.
        private const val MAX_ASIDE = 100
        private val LOCK = Any()
        private val POSIX =
            java.nio.file.FileSystems
                .getDefault()
                .supportedFileAttributeViews()
                .contains("posix")
        private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
        private val ID = Regex("[0-9a-f]{32}")
        private val SECRET = Regex("[0-9a-f]{64}")
    }
}
