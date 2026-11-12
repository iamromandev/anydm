package dev.anydm.desktop

import kotlinx.serialization.SerializationException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface LoadResult {
    data object Missing : LoadResult

    data class Loaded(
        val file: SettingsFile,
    ) : LoadResult

    /** The file could not be used and was set aside (or could not be read at all); the app starts with defaults. */
    data class Reset(
        val reason: String,
    ) : LoadResult
}

sealed interface SaveResult {
    data object Ok : SaveResult

    data class Failed(
        val reason: String,
    ) : SaveResult
}

/** Both calls block on the disk: call them off the UI thread. */
interface SettingsStore {
    fun load(): LoadResult

    fun save(file: SettingsFile): SaveResult
}

/** Every disk operation the settings store needs, so tests can fail any of them. */
interface FileOps {
    /** At most [maxBytes] + 1 bytes, so a huge file is never read whole; null when the file does not exist. */
    fun read(
        path: Path,
        maxBytes: Int,
    ): ByteArray?

    /** Writes [bytes] to a new file inside [dir] (the same folder as the target, so the rename stays on one volume). */
    fun writeTemp(
        dir: Path,
        bytes: ByteArray,
    ): Path

    fun moveAtomic(
        from: Path,
        to: Path,
    )

    fun moveReplace(
        from: Path,
        to: Path,
    )

    fun ensureDir(path: Path)

    fun delete(path: Path)

    /** The files directly inside [dir]; empty when the folder does not exist. */
    fun listFiles(dir: Path): List<Path>
}

object RealFileOps : FileOps {
    override fun read(
        path: Path,
        maxBytes: Int,
    ): ByteArray? {
        if (!Files.exists(path)) return null
        return Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
    }

    override fun writeTemp(
        dir: Path,
        bytes: ByteArray,
    ): Path {
        val tmp = Files.createTempFile(dir, "settings", ".tmp")
        try {
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { ch ->
                ch.write(ByteBuffer.wrap(bytes))
                ch.force(true)
            }
        } catch (e: IOException) {
            Files.deleteIfExists(tmp)
            throw e
        }
        return tmp
    }

    override fun moveAtomic(
        from: Path,
        to: Path,
    ) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun moveReplace(
        from: Path,
        to: Path,
    ) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun ensureDir(path: Path) {
        Files.createDirectories(path)
    }

    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    override fun listFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { it.toList() }
    }
}

const val MAX_SETTINGS_BYTES = 100 * 1024

/**
 * `settings.json` in [dir]. Saves go to a temp file in the same folder and are renamed over the old file, so a crash never
 * leaves half a file. A file that cannot be used is renamed to `settings.json.bad` (replacing an older one), never deleted.
 * If the file can be neither read nor set aside, it is left alone and every save of this session fails: saving over it
 * would destroy what the user may still be able to recover.
 */
class FileSettingsStore(
    private val dir: Path,
    private val files: FileOps = RealFileOps,
) : SettingsStore {
    private val target = dir.resolve(SETTINGS_FILE_NAME)
    private val bad = dir.resolve("$SETTINGS_FILE_NAME.bad")

    /** Set when the file on disk could not be read or set aside; saves are refused for the rest of the session. */
    private var held = false

    @Synchronized
    override fun load(): LoadResult {
        removeLeftOverTemps()
        val bytes =
            try {
                files.read(target, MAX_SETTINGS_BYTES) ?: return LoadResult.Missing
            } catch (_: IOException) {
                held = true
                return LoadResult.Reset("unreadable")
            } catch (_: SecurityException) {
                held = true
                return LoadResult.Reset("unreadable")
            }
        return when (val parsed = parse(bytes)) {
            is Parsed.Usable -> {
                LoadResult.Loaded(parsed.file)
            }

            is Parsed.Unusable -> {
                setAside()
                LoadResult.Reset(parsed.reason)
            }
        }
    }

    /** A crash between the temp write and the rename leaves `settings<digits>.tmp` behind; none is ever in use at start. */
    private fun removeLeftOverTemps() {
        try {
            files.listFiles(dir).filter { it.fileName.toString().let { n -> n.startsWith("settings") && n.endsWith(".tmp") } }.forEach {
                try {
                    files.delete(it)
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    private sealed interface Parsed {
        class Usable(
            val file: SettingsFile,
        ) : Parsed

        /** The reason never quotes the file. */
        class Unusable(
            val reason: String,
        ) : Parsed
    }

    private fun parse(bytes: ByteArray): Parsed {
        if (bytes.size > MAX_SETTINGS_BYTES) return Parsed.Unusable("too large")
        val file =
            try {
                settingsJson.decodeFromString(SettingsFile.serializer(), bytes.decodeToString())
            } catch (_: SerializationException) {
                return Parsed.Unusable("not valid settings")
            } catch (_: IllegalArgumentException) {
                return Parsed.Unusable("not valid settings")
            }
        return validate(file)?.let { Parsed.Unusable(it) } ?: Parsed.Usable(file)
    }

    private fun setAside() {
        try {
            files.moveReplace(target, bad)
        } catch (_: IOException) {
            // The file stays where it is, so it must not be saved over.
            held = true
        } catch (_: SecurityException) {
            held = true
        }
    }

    @Synchronized
    override fun save(file: SettingsFile): SaveResult {
        if (held) return SaveResult.Failed("the existing settings file could not be read or set aside")
        val bytes = settingsJson.encodeToString(SettingsFile.serializer(), file).encodeToByteArray()
        var temp: Path? = null
        return try {
            files.ensureDir(dir)
            temp = files.writeTemp(dir, bytes)
            try {
                files.moveAtomic(temp, target)
            } catch (_: AtomicMoveNotSupportedException) {
                // This file system cannot rename atomically: a replacing move is the next best thing.
                files.moveReplace(temp, target)
            }
            SaveResult.Ok
        } catch (_: IOException) {
            discard(temp)
            SaveResult.Failed("couldn't write the settings file")
        } catch (_: SecurityException) {
            discard(temp)
            SaveResult.Failed("couldn't write the settings file")
        }
    }

    private fun discard(temp: Path?) {
        if (temp == null) return
        try {
            files.delete(temp)
        } catch (_: IOException) {
        }
    }
}

/** Settings kept in memory: the default for [HomeScreen], so a test or a preview never writes a file. */
class InMemorySettingsStore(
    initial: SettingsFile? = null,
) : SettingsStore {
    @Volatile
    private var file: SettingsFile? = initial
    val saves = CopyOnWriteArrayList<SettingsFile>()

    override fun load(): LoadResult = file?.let { LoadResult.Loaded(it) } ?: LoadResult.Missing

    override fun save(file: SettingsFile): SaveResult {
        this.file = file
        saves.add(file)
        return SaveResult.Ok
    }
}

const val FLUSH_TIMEOUT_MS = 2_000L

/** A snapshot of the settings as [AppStores.changed] saw it; [seq] grows with every change, so an older one is never written after a newer. */
class Change(
    val seq: Long,
    val file: SettingsFile,
)

/**
 * The two stores the app persists to. [inMemory] is the default everywhere, so a test never reaches the real app-data folder
 * or the real keychain; only `main` builds [real].
 */
class AppStores(
    val settings: SettingsStore,
    val secrets: SecretStore,
    private val flushTimeoutMs: Long = FLUSH_TIMEOUT_MS,
) {
    private val pending = AtomicReference<Change?>(null)
    private var counter = 0L

    /** Saves go one at a time; [lastWritten] (guarded by it) is the newest snapshot on disk, so a slow older save cannot undo a newer one. */
    private val writeLock = Any()
    private var lastWritten = 0L

    @Volatile
    private var saved: SettingsFile? = null

    private val flushStarted = AtomicBoolean(false)
    private val flushDone = CountDownLatch(1)

    private val secretLock = ReentrantLock()
    private val secretsIdle = secretLock.newCondition()
    private var secretWrites = 0

    /** What the file already holds, so a state equal to it is never written again. */
    fun loaded(file: SettingsFile?) {
        saved = file
    }

    /** Every change as it happens (before the debounce): the latest is what [flush] writes if the app exits first. */
    @Synchronized
    fun changed(file: SettingsFile): Change = Change(++counter, file).also { pending.set(it) }

    /** Blocks on the disk: call it off the UI thread. A snapshot older than one already written is skipped. */
    fun save(change: Change): SaveResult =
        synchronized(writeLock) {
            val file = change.file
            if (change.seq <= lastWritten) {
                pending.compareAndSet(change, null)
                return SaveResult.Ok
            }
            if (file == saved) {
                lastWritten = change.seq
                pending.compareAndSet(change, null)
                return SaveResult.Ok
            }
            val result = settings.save(file)
            if (result is SaveResult.Ok) {
                saved = file
                lastWritten = change.seq
                pending.compareAndSet(change, null)
            }
            result
        }

    /**
     * Marks a keychain write as started and returns the call that marks it over, so [flush] can wait for it. Call it before the
     * write is launched, so an exit right after cannot miss it.
     */
    fun secretWriteStarted(): () -> Unit {
        secretLock.withLock { secretWrites++ }
        val ended = AtomicBoolean(false)
        return {
            if (ended.compareAndSet(false, true)) {
                secretLock.withLock {
                    secretWrites--
                    if (secretWrites == 0) secretsIdle.signalAll()
                }
            }
        }
    }

    private fun awaitSecretWrites(deadline: Long): Boolean =
        secretLock.withLock {
            var left = deadline - System.nanoTime()
            while (secretWrites > 0) {
                if (left <= 0) return false
                left = secretsIdle.awaitNanos(left)
            }
            true
        }

    /**
     * What the app does on its way out: writes a change that is still waiting for its debounce and waits for keychain writes in
     * flight, all within [flushTimeoutMs] together (an exit must not hang on a slow disk or keychain), then closes the keychain.
     * Only the first call does this; every exit path may call it, and a later call just waits for the first to be over.
     */
    fun flush() {
        if (!flushStarted.compareAndSet(false, true)) {
            try {
                flushDone.await(flushTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return
        }
        try {
            flushNow(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushTimeoutMs))
        } finally {
            flushDone.countDown()
        }
    }

    private fun flushNow(deadline: Long) {
        val change = pending.getAndSet(null)
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "anydm-flush").apply { isDaemon = true } }
        try {
            val write = if (change != null && change.file != saved) worker.submit { save(change) } else null
            val keychainIdle = awaitSecretWrites(deadline)
            write?.get(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            if (keychainIdle) closeSecrets()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // A timeout or a failing store: the exit goes on, and the previous file is still intact.
        } finally {
            worker.shutdownNow()
        }
    }

    private fun closeSecrets() {
        try {
            (secrets as? AutoCloseable)?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun inMemory() = AppStores(InMemorySettingsStore(), InMemorySecretStore())

        fun real(platform: Platform): AppStores {
            val dir =
                appDataDir(platform, System.getenv(), System.getProperty("user.home").orEmpty(), System.getProperty("os.name").orEmpty())
            return AppStores(FileSettingsStore(dir), KeyringSecretStore.system())
        }
    }
}
