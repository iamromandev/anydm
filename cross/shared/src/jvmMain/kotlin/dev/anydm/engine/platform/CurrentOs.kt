package dev.anydm.engine.platform

import dev.anydm.engine.files.OsProfile

/** The file-name rules of the OS this JVM runs on (FIL-03); the desktop passes it to the engine. */
fun OsProfile.Companion.current(): OsProfile = osProfileFor(System.getProperty("os.name"))

internal fun osProfileFor(osName: String): OsProfile =
    when (DesktopOs.of(osName)) {
        DesktopOs.MACOS -> OsProfile.MACOS
        DesktopOs.WINDOWS -> OsProfile.WINDOWS
        DesktopOs.LINUX -> OsProfile.LINUX
    }
