package dev.anydm.engine.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.test.Test

class TrashBinsTest {
    private val no: (String) -> String? = { null }

    @Test
    fun macosKeepsTheDesktopBin() {
        trashBinFor(DesktopOs.MACOS, "/Users/a", no, { 501 }) shouldBe DesktopTrashBin
    }

    @Test
    fun windowsGuardsTheDesktopBin() {
        trashBinFor(DesktopOs.WINDOWS, "C:\\Users\\a", no, { 0 }).shouldBeInstanceOf<WindowsGuardedTrashBin>()
    }

    @Test
    fun linuxUsesTheFreedesktopTrashUnderXdgDataHome() {
        val bin = trashBinFor(DesktopOs.LINUX, "/home/a", { if (it == "XDG_DATA_HOME") "/data" else null }, { 1000 })

        bin.shouldBeInstanceOf<FreedesktopTrashBin>().homeTrash shouldBe Path.of("/data/Trash")
    }

    @Test
    fun linuxFallsBackToTheHomeFolder() {
        val bin = trashBinFor(DesktopOs.LINUX, "/home/a", { if (it == "XDG_DATA_HOME") "" else null }, { 1000 })

        bin.shouldBeInstanceOf<FreedesktopTrashBin>().homeTrash shouldBe Path.of("/home/a/.local/share/Trash")
    }
}
