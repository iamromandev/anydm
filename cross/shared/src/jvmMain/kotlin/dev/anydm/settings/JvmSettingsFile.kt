package dev.anydm.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The OS's app-data folder for anydm: Application Support on macOS, `%APPDATA%` on
 * Windows, `$XDG_CONFIG_HOME` (or `~/.config`) elsewhere.
 */
fun defaultSettingsPath(
    os: String = System.getProperty("os.name"),
    env: Map<String, String> = System.getenv(),
    home: String = System.getProperty("user.home"),
): Path {
    val name = os.lowercase()
    val base =
        when {
            name.startsWith("mac") -> Path.of(home, "Library", "Application Support")
            name.startsWith("windows") -> Path.of(env["APPDATA"] ?: Path.of(home, "AppData", "Roaming").toString())
            else -> env["XDG_CONFIG_HOME"]?.let { Path.of(it) } ?: Path.of(home, ".config")
        }
    return base.resolve("anydm").resolve("settings.json")
}

/** The settings file, written whole through a temporary file and readable by its owner alone where the OS allows. */
class JvmSettingsFile(
    private val path: Path,
) : SettingsFile {
    override fun read(): String? = if (Files.exists(path)) Files.readString(path) else null

    override fun write(text: String) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "settings", ".tmp")
        if (Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
        }
        Files.writeString(temp, text)
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
