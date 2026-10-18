package dev.anydm.engine.tools

/** The OS and CPU this JVM runs on; anything that isn't macOS or Windows is treated as Linux. */
fun Platform.Companion.current(): Platform = platformOf(System.getProperty("os.name"), System.getProperty("os.arch"))

internal fun platformOf(
    osName: String,
    osArch: String,
): Platform {
    val os =
        when {
            osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> OsFamily.MACOS
            osName.startsWith("Windows", ignoreCase = true) -> OsFamily.WINDOWS
            else -> OsFamily.LINUX
        }
    val arch = if (osArch.equals("aarch64", ignoreCase = true) || osArch.equals("arm64", ignoreCase = true)) Arch.ARM64 else Arch.X64
    return Platform(os, arch)
}
