package dev.anydm.engine.platform

import java.nio.file.Files
import java.nio.file.Path as NioPath

/** The trash for this machine's OS (FIL-09). */
internal fun trashBinForCurrentOs(): TrashBin =
    trashBinFor(
        DesktopOs.of(System.getProperty("os.name")),
        System.getProperty("user.home"),
        System::getenv,
    ) { (Files.getAttribute(NioPath.of(System.getProperty("user.home")), "unix:uid") as Int) }

internal fun trashBinFor(
    os: DesktopOs,
    home: String,
    env: (String) -> String?,
    uid: () -> Int,
): TrashBin =
    when (os) {
        DesktopOs.MACOS -> {
            DesktopTrashBin
        }

        DesktopOs.WINDOWS -> {
            WindowsGuardedTrashBin(DesktopTrashBin, JnaDriveInfo)
        }

        DesktopOs.LINUX -> {
            val data = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { NioPath.of(it) } ?: NioPath.of(home, ".local", "share")
            FreedesktopTrashBin(data.resolve("Trash"), uid())
        }
    }
