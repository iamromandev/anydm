package dev.anydm.engine.platform

import kotlinx.io.files.Path

/** anydm's folders on this machine, from `os.name`, `user.home` and the environment. */
fun desktopAppDirs(): AppDirs {
    val layout = desktopDirLayout(DesktopOs.of(System.getProperty("os.name")), System.getProperty("user.home"), System::getenv)
    return AppDirs(data = Path(layout.data), logs = Path(layout.logs), cache = Path(layout.cache))
}

/** Desktop OS families with their own folder conventions; anything else follows XDG. */
internal enum class DesktopOs {
    MACOS,
    WINDOWS,
    LINUX,
    ;

    companion object {
        fun of(osName: String): DesktopOs =
            when {
                osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> MACOS
                osName.startsWith("Windows", ignoreCase = true) -> WINDOWS
                else -> LINUX
            }
    }
}

/** The three folders as strings in that OS's own syntax, so every layout is testable on any host. */
internal data class DirLayout(
    val data: String,
    val logs: String,
    val cache: String,
)

internal fun desktopDirLayout(
    os: DesktopOs,
    home: String,
    env: (String) -> String?,
): DirLayout {
    require(home.isNotBlank()) { "home must not be blank" }
    return when (os) {
        DesktopOs.MACOS -> {
            val library = home.trimEnd('/') + "/Library"
            DirLayout("$library/Application Support/$APP", "$library/Logs/$APP", "$library/Caches/$APP")
        }

        DesktopOs.WINDOWS -> {
            val profile = home.trimEnd('\\', '/')
            val roaming = windowsDir(env, "APPDATA") ?: "$profile\\AppData\\Roaming"
            val local = windowsDir(env, "LOCALAPPDATA") ?: "$profile\\AppData\\Local"
            DirLayout("$roaming\\$APP", "$local\\$APP\\logs", "$local\\$APP\\cache")
        }

        DesktopOs.LINUX -> {
            val base = home.trimEnd('/')
            DirLayout(
                data = (xdgDir(env, "XDG_DATA_HOME") ?: "$base/.local/share") + "/$APP",
                // A folder of its own, so the diagnostics bundle (DIA-04) can take it whole.
                logs = (xdgDir(env, "XDG_STATE_HOME") ?: "$base/.local/state") + "/$APP/logs",
                cache = (xdgDir(env, "XDG_CACHE_HOME") ?: "$base/.cache") + "/$APP",
            )
        }
    }
}

private const val APP = "anydm"

// The XDG spec: a relative path in these variables is invalid and ignored; an empty one is unset.
private fun xdgDir(
    env: (String) -> String?,
    name: String,
): String? = env(name)?.takeIf { it.startsWith("/") }?.trimEnd('/')

private fun windowsDir(
    env: (String) -> String?,
    name: String,
): String? = env(name)?.takeIf { windowsAbsolute.containsMatchIn(it) }?.trimEnd('\\', '/')

private val windowsAbsolute = Regex("""^([A-Za-z]:[\\/]|\\\\)""")
