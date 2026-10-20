plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.sqldelight) apply false
}

// The OS whose libtorrent native an app (and the tests) carries (BIN-05): this machine's, or -Panydm.nativeOs=macos|linux|windows
// to look at another's, say for ./gradlew :desktopApp:checkDesktopNatives. Each installer is built on its own OS, so it
// carries one native, not the three.
val nativeOs: String =
    (findProperty("anydm.nativeOs") as String?)?.lowercase()
        ?: System.getProperty("os.name").lowercase().let {
            when {
                it.startsWith("mac") -> "macos"
                it.startsWith("windows") -> "windows"
                else -> "linux"
            }
        }
require(nativeOs in setOf("macos", "linux", "windows")) { "anydm.nativeOs must be macos, linux or windows: $nativeOs" }
extra["libtorrentNativeOs"] = nativeOs
