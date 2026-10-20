package dev.anydm.engine.torrent

import dev.anydm.engine.protocol.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.libtorrent4j.LibTorrent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val JNI_PATH_PROPERTY = "libtorrent4j.jni.path"

/**
 * Where libtorrent4j's native library sits in its jar for a system, or null when the library has none for it (the jars
 * ship macOS on Apple silicon, Linux on x86-64 and Windows on x86-64). [os] and [arch] are `os.name` and `os.arch`.
 */
fun nativeResource(
    os: String,
    arch: String,
): String? {
    val system = os.lowercase()
    val cpu =
        when (arch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x86_64"
            else -> return null
        }
    return when {
        "mac" in system || "darwin" in system -> "lib/arm64/libtorrent4j.dylib".takeIf { cpu == "arm64" }
        "windows" in system -> "lib/x86_64/libtorrent4j.dll".takeIf { cpu == "x86_64" }
        "linux" in system -> "lib/x86_64/libtorrent4j.so".takeIf { cpu == "x86_64" }
        else -> null
    }
}

/**
 * Makes libtorrent4j's native library loadable. The library reads it from the file named by `libtorrent4j.jni.path` and
 * doesn't unpack it from its jar, so this copies it out of the jar once into a folder named by its length (a new version
 * of it is another length, so another folder) and sets the property.
 */
class TorrentNatives(
    private val extractTo: Path,
    private val loader: ClassLoader = TorrentNatives::class.java.classLoader,
    private val os: String = System.getProperty("os.name"),
    private val arch: String = System.getProperty("os.arch"),
    /** Told before libtorrent loads, to keep the JVM's signal handlers; used by [LibTorrentBackend] too. */
    val signals: SignalGuard = SignalGuard.None,
) {
    private var loaded = false

    /** Extracts the library and loads it; a failure says torrents aren't supported on this machine. */
    suspend fun load(): StepResult<Unit> = withContext(Dispatchers.IO) { ensure() }

    /** [load] for code that can't suspend. Quick once the library is loaded. */
    fun ensure(): StepResult<Unit> =
        synchronized(this) {
            if (loaded) return StepResult.Ok(Unit)
            when (val extracted = extract()) {
                is StepResult.Failed -> return extracted
                is StepResult.Ok -> Unit
            }
            signals.snapshot()
            try {
                // Touching the library is what loads it; an error here is a library for another system or a damaged one.
                LibTorrent.version()
            } catch (e: LinkageError) {
                return unsupported("the library can't be loaded (${e.message})")
            } catch (e: RuntimeException) {
                return unsupported("the library can't be loaded (${e.message})")
            }
            loaded = true
            StepResult.Ok(Unit)
        }

    /** Copies the library out of the jar, unless it is already there, and points `libtorrent4j.jni.path` at it. */
    internal fun extract(): StepResult<File> {
        val resource = nativeResource(os, arch) ?: return unsupported("there is no libtorrent for $os on $arch")
        val url = loader.getResource(resource) ?: return unsupported("$resource isn't in the application")
        try {
            val length = url.openConnection().contentLengthLong
            val folder = File(extractTo.toString(), length.toString()).also { it.mkdirs() }
            val target = File(folder, resource.substringAfterLast('/'))
            if (!target.isFile || target.length() != length) {
                val partial = File.createTempFile("libtorrent4j", ".tmp", folder)
                try {
                    (loader.getResourceAsStream(resource) ?: return unsupported("$resource isn't in the application")).use { input ->
                        partial.outputStream().use { input.copyTo(it) }
                    }
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    partial.delete()
                }
            }
            System.setProperty(JNI_PATH_PROPERTY, target.absolutePath)
            return StepResult.Ok(target)
        } catch (e: java.io.IOException) {
            return unsupported("the library can't be unpacked (${e.message})")
        }
    }

    private fun unsupported(why: String): StepResult.Failed = StepResult.Failed(null, "torrents aren't supported here: $why")
}
