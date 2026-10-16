package dev.anydm.engine.platform

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.Test

class DesktopTrashBinTest {
    // Opt-in: leaves one small anydm-trash-check-*.txt in this machine's real trash.
    @Test
    fun movesAFileToTheRealTrash() {
        assumeTrue("set ANYDM_REAL_TRASH=1 to run", System.getenv("ANYDM_REAL_TRASH") == "1")
        assumeTrue("no trash for Java on this desktop", DesktopTrashBin.isAvailable)
        val file = Files.createTempFile("anydm-trash-check-", ".txt")
        runTest { DesktopFileSystem().moveToTrash(Path(file.toString())) shouldBe FileResult.Ok(Unit) }
        Files.exists(file) shouldBe false
    }
}
