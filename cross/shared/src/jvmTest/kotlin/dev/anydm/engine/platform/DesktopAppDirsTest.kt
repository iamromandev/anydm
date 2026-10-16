package dev.anydm.engine.platform

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DesktopAppDirsTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun macOsUsesItsLibraryFolders() {
        desktopDirLayout(DesktopOs.MACOS, "/Users/me", env()) shouldBe
            DirLayout(
                data = "/Users/me/Library/Application Support/anydm",
                logs = "/Users/me/Library/Logs/anydm",
                cache = "/Users/me/Library/Caches/anydm",
            )
    }

    @Test
    fun macOsIgnoresXdgVariables() {
        val xdg = env("XDG_DATA_HOME" to "/data", "XDG_STATE_HOME" to "/state", "XDG_CACHE_HOME" to "/cache")
        desktopDirLayout(DesktopOs.MACOS, "/Users/me", xdg) shouldBe desktopDirLayout(DesktopOs.MACOS, "/Users/me", env())
    }

    @Test
    fun windowsKeepsDataRoamingAndTheRestLocal() {
        val vars =
            env(
                "APPDATA" to "C:\\Users\\me\\AppData\\Roaming",
                "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local",
            )
        desktopDirLayout(DesktopOs.WINDOWS, "C:\\Users\\me", vars) shouldBe
            DirLayout(
                data = "C:\\Users\\me\\AppData\\Roaming\\anydm",
                logs = "C:\\Users\\me\\AppData\\Local\\anydm\\logs",
                cache = "C:\\Users\\me\\AppData\\Local\\anydm\\cache",
            )
    }

    @Test
    fun windowsFallsBackToTheProfile() {
        val expected =
            DirLayout(
                data = "C:\\Users\\me\\AppData\\Roaming\\anydm",
                logs = "C:\\Users\\me\\AppData\\Local\\anydm\\logs",
                cache = "C:\\Users\\me\\AppData\\Local\\anydm\\cache",
            )
        desktopDirLayout(DesktopOs.WINDOWS, "C:\\Users\\me", env()) shouldBe expected
        desktopDirLayout(DesktopOs.WINDOWS, "C:\\Users\\me", env("APPDATA" to "", "LOCALAPPDATA" to "")) shouldBe expected
        desktopDirLayout(DesktopOs.WINDOWS, "C:\\Users\\me", env("APPDATA" to "AppData", "LOCALAPPDATA" to "AppData")) shouldBe expected
    }

    @Test
    fun linuxDefaultsToXdgFolders() {
        desktopDirLayout(DesktopOs.LINUX, "/home/me", env()) shouldBe
            DirLayout(
                data = "/home/me/.local/share/anydm",
                logs = "/home/me/.local/state/anydm/logs",
                cache = "/home/me/.cache/anydm",
            )
    }

    @Test
    fun linuxFollowsAbsoluteXdgVariables() {
        val vars = env("XDG_DATA_HOME" to "/data/", "XDG_STATE_HOME" to "/state/", "XDG_CACHE_HOME" to "/cache/")
        desktopDirLayout(DesktopOs.LINUX, "/home/me", vars) shouldBe
            DirLayout(
                data = "/data/anydm",
                logs = "/state/anydm/logs",
                cache = "/cache/anydm",
            )
    }

    @Test
    fun linuxIgnoresEmptyAndRelativeXdgVariables() {
        val defaults = desktopDirLayout(DesktopOs.LINUX, "/home/me", env())
        val empty = env("XDG_DATA_HOME" to "", "XDG_STATE_HOME" to "", "XDG_CACHE_HOME" to "")
        val relative = env("XDG_DATA_HOME" to "data", "XDG_STATE_HOME" to "./state", "XDG_CACHE_HOME" to "cache/x")
        desktopDirLayout(DesktopOs.LINUX, "/home/me", empty) shouldBe defaults
        desktopDirLayout(DesktopOs.LINUX, "/home/me", relative) shouldBe defaults
    }

    @Test
    fun osNamesMapToTheirFamily() {
        DesktopOs.of("Mac OS X") shouldBe DesktopOs.MACOS
        DesktopOs.of("Darwin") shouldBe DesktopOs.MACOS
        DesktopOs.of("Windows 11") shouldBe DesktopOs.WINDOWS
        DesktopOs.of("Windows Server 2022") shouldBe DesktopOs.WINDOWS
        DesktopOs.of("Linux") shouldBe DesktopOs.LINUX
        DesktopOs.of("FreeBSD") shouldBe DesktopOs.LINUX
    }

    @Test
    fun thisMachinesFoldersAreSeparate() {
        val dirs = desktopAppDirs()
        setOf(dirs.data, dirs.logs, dirs.cache).size shouldBe 3
        listOf(dirs.data, dirs.logs, dirs.cache).forEach { it.toString().contains("anydm") shouldBe true }
    }
}
